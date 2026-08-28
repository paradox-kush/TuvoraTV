package com.nuvio.tv.playback.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The sole orchestration owner. Commands, normalized engine events, and async completions converge
 * on [lane]; adapters only execute operations and report facts.
 */
class PlaybackSession(
    parentScope: CoroutineScope,
    private val requestResolver: PlaybackRequestResolver,
    private val environmentProvider: PlaybackEnvironmentProvider,
    private val requirementsResolver: PlaybackRequirementsResolver,
    private val graphProvider: PlaybackGraphProvider,
    private val engineRegistry: PlaybackEngineRegistry,
    private val outputController: PlaybackOutputController,
    private val clock: PlaybackClock,
    private val diagnostics: PlaybackDiagnostics,
    private val lifecycle: PlaybackLifecyclePort? = null,
    private val providerPlaybackResolver: ProviderPlaybackResolver? = null,
    private val compatibilityRecording: CompatibilityRecordingEnvironment? = null,
    private val policy: PlaybackPolicy = PlaybackPolicy(),
    private val releaseTimeoutMs: Long = DEFAULT_RELEASE_TIMEOUT_MS,
) {
    init {
        require(releaseTimeoutMs > 0) { "Release timeout must be positive" }
    }

    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val lane = Channel<LaneMessage>(Channel.UNLIMITED)
    private val engineEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 64)
    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    private val _surfaceAvailable = MutableStateFlow(false)
    private val intentionalReleases = ConcurrentHashMap<EngineType, Set<Long>>()

    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()
    val events: Flow<PlaybackEvent> = engineEvents.asSharedFlow()

    @Volatile
    private var machine = PlaybackMachineState()

    @Volatile
    private var preferences = PlaybackPreferences.recommended()

    private var generation: Long = 0
    private var generationJob: Job? = null
    private var reconnectJob: Job? = null
    private var activeGraph: PlaybackGraph? = null
    private var activeGraphGeneration: Long? = null
    private var resolved: GenerationValue<ResolvedPlaybackRequest>? = null
    private var requirements: GenerationValue<PlaybackRequirements>? = null
    private var latestRequirementsChangeId: Long = 0
    private var requirementsApplyJob: Job? = null
    private val outputMutex = Mutex()
    private var watchdogJob: Job? = null
    private var watchdogArm: WatchdogArm? = null
    private var nextWatchdogToken: Long = 1
    private var engineAttempt: Long = 0
    private var engineAttemptGeneration: Long? = null
    private var runtimeMetricsUnavailableAttempt: Long? = null
    private val decoderStableIds = mutableMapOf<Pair<Long, EngineType>, String>()
    private val recordedCompatibilityOutcomes = mutableSetOf<CompatibilityRecordingKey>()

    private val actorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (message in lane) process(message)
    }

    init {
        EngineType.entries.forEach { type ->
            engineRegistry.engine(type)?.let { engine ->
                scope.launch {
                    engine.events.collect { event ->
                        if (!isIntentionalTerminal(engine.type, event)) {
                            engineEvents.emit(event)
                            lane.send(LaneMessage.Reducer(PlaybackReducerInput.Event(event)))
                        }
                    }
                }
            }
        }
        lifecycle?.let { port ->
            scope.launch {
                port.events().collect { event ->
                    val input = when (event) {
                        PlaybackLifecycleEvent.ACTIVE -> PlaybackReducerInput.LifecycleChanged(active = true)
                        PlaybackLifecycleEvent.INACTIVE -> PlaybackReducerInput.LifecycleChanged(active = false)
                        PlaybackLifecycleEvent.DESTROYED ->
                            PlaybackReducerInput.Command(PlaybackCommand.Release)
                    }
                    lane.send(LaneMessage.Reducer(input))
                }
            }
        }
    }

    suspend fun dispatch(command: PlaybackCommand) {
        lane.send(LaneMessage.Reducer(PlaybackReducerInput.Command(command)))
    }

    fun tryDispatch(command: PlaybackCommand): Boolean =
        lane.trySend(LaneMessage.Reducer(PlaybackReducerInput.Command(command))).isSuccess

    suspend fun setPreviewAvailability(
        generation: Long,
        availability: PreviewAvailability,
    ) {
        lane.send(
            LaneMessage.Reducer(
                PlaybackReducerInput.PreviewAvailabilityChanged(generation, availability),
            ),
        )
    }

    suspend fun reportSourceAvailability(
        generation: Long,
        availability: StreamAvailability,
    ) {
        require(
            availability !is StreamAvailability.TerminallyUnavailable ||
                availability.evidence != TerminalAvailabilityEvidence.ALL_ELIGIBLE_GRAPHS_EXHAUSTED,
        ) { "Only session policy may report all eligible graphs exhausted" }
        lane.send(
            LaneMessage.Reducer(
                PlaybackReducerInput.StreamAvailabilityChanged(
                    generation = generation,
                    availability = availability,
                    authority = TerminalAvailabilityAuthority.SOURCE,
                ),
            ),
        )
    }

    suspend fun release() = coroutineScope {
        val alreadyStopped = snapshot.value.state == PlaybackState.STOPPED
        val releaseCompletion = if (alreadyStopped) {
            null
        } else {
            // Subscribe before dispatch and ignore the current snapshot. A pre-existing FAILED
            // snapshot is not proof that this Release command completed its adapter barrier.
            async(start = CoroutineStart.UNDISPATCHED) {
                snapshot.drop(1).first {
                    it.state == PlaybackState.STOPPED || it.state == PlaybackState.FAILED
                }
            }
        }
        dispatch(PlaybackCommand.Release)
        releaseCompletion?.await()
        sessionJob.cancelAndJoin()
        lane.close()
    }

    private suspend fun process(message: LaneMessage) {
        when (message) {
            is LaneMessage.Reducer -> applyReducer(message.input)
            is LaneMessage.ResolutionFinished -> resolutionFinished(message)
            is LaneMessage.HandoffResolutionFinished -> handoffResolutionFinished(message)
            is LaneMessage.FreshResolutionReady -> freshResolutionReady(message)
            is LaneMessage.SelectionFinished -> selectionFinished(message)
            is LaneMessage.RequirementsChangeFinished -> requirementsChangeFinished(message)
            is LaneMessage.RequirementsApplied -> requirementsApplied(message)
            is LaneMessage.BarrierFinished -> barrierFinished(message)
            is LaneMessage.WatchdogExpired -> watchdogExpired(message)
            is LaneMessage.RuntimeWindowFinished -> runtimeWindowFinished(message)
        }
    }

    private suspend fun applyReducer(input: PlaybackReducerInput) {
        if (input is PlaybackReducerInput.Command && input.value is PlaybackCommand.PreferencesChanged) {
            preferences = input.value.preferences
        }
        val engineStarting = (input as? PlaybackReducerInput.Event)?.value as? PlaybackEvent.EngineStarting
        val engineStartAccepted = engineStarting?.generation == machine.snapshot.generation &&
            machine.snapshot.state in setOf(
                PlaybackState.STARTING_PRIMARY,
                PlaybackState.RECOVERING_IN_PLACE,
                PlaybackState.LIVE_RECONNECTING,
            )
        val before = machine
        observeCompatibilityFact(before, input)
        val transition = PlaybackStateMachine.reduce(before, input)
        if (engineStartAccepted) {
            check(engineAttempt < Long.MAX_VALUE) { "Playback engine attempt exhausted" }
            engineAttempt++
            engineAttemptGeneration = engineStarting.generation
            runtimeMetricsUnavailableAttempt = null
        }
        machine = transition.state
        if (before.snapshot.generation != transition.state.snapshot.generation) {
            val activeGeneration = transition.state.snapshot.generation
            decoderStableIds.keys.removeAll { (generation, _) -> generation != activeGeneration }
            recordedCompatibilityOutcomes.removeAll { it.generation != activeGeneration }
        }
        _snapshot.value = transition.state.snapshot
        _surfaceAvailable.value = transition.state.surfaceAvailable
        reconcileWatchdog()
        recordCompatibilityOutcome(before, transition.state, input)
        transition.actions.forEach { execute(it) }
    }

    private fun observeCompatibilityFact(
        before: PlaybackMachineState,
        input: PlaybackReducerInput,
    ) {
        val event = (input as? PlaybackReducerInput.Event)?.value ?: return
        if (event.generation != before.snapshot.generation) return
        val graph = before.snapshot.graph ?: return
        val key = event.generation to graph.engine
        when (event) {
            is PlaybackEvent.EngineStarting -> decoderStableIds.remove(key)
            is PlaybackEvent.VideoDecoderInitialized -> decoderStableIds[key] = event.decoderName
            else -> Unit
        }
    }

    private suspend fun recordCompatibilityOutcome(
        before: PlaybackMachineState,
        after: PlaybackMachineState,
        input: PlaybackReducerInput,
    ) {
        val environment = compatibilityRecording ?: return
        val event = (input as? PlaybackReducerInput.Event)?.value ?: return
        val generation = event.generation
        if (generation != before.snapshot.generation || generation != after.snapshot.generation) return
        val resolvedRequest = resolved.takeIf { it?.generation == generation }?.value ?: return
        val scopeKey = resolvedRequest.compatibilityScopeKey ?: return
        val graph = before.snapshot.graph ?: return
        val outcome = compatibilityOutcome(before, after, event) ?: return
        val failure = (event as? PlaybackEvent.Failed)?.failure
        val graphFingerprint = CompatibilityGraphFingerprint(
            engine = graph.engine,
            outputProfile = graph.outputProfile,
            decoderMode = graph.decoderMode,
            audioMode = graph.audioMode,
            surfaceMode = graph.surfaceMode,
            secureOutput = graph.secureOutput,
            decoderStableId = decoderStableIds[generation to graph.engine],
        )
        val engineVersion = environment.engineVersions[graph.engine] ?: return
        val key = CompatibilityRecordingKey(generation, graphFingerprint, outcome)
        if (!recordedCompatibilityOutcomes.add(key)) return
        val now = clock.nowEpochMs()
        if (now < 0 || now == Long.MAX_VALUE) return
        val ttl = when (outcome) {
            CompatibilityOutcome.SUCCESS -> environment.successTtlMs
            CompatibilityOutcome.DETERMINISTIC_FATAL -> environment.fatalTtlMs
        }
        val expiresAt = if (ttl > Long.MAX_VALUE - now) Long.MAX_VALUE else now + ttl
        if (expiresAt <= now) return
        try {
            environment.history.record(
                CompatibilityRecord(
                    scopeKey = scopeKey,
                    graph = graphFingerprint,
                    runtime = environment.runtime,
                    outcome = outcome,
                    failureDomain = failure?.domain,
                    failureCode = failure?.code,
                    appVersion = environment.appVersion,
                    engineVersion = engineVersion,
                    recordedAtEpochMs = now,
                    expiresAtEpochMs = expiresAt,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diagnostics.record(
                PlaybackDiagnosticEvent(
                    generation,
                    PlaybackDiagnosticCode.COMPATIBILITY_HISTORY_RECORD_FAILED,
                    engine = graph.engine,
                ),
            )
        }
    }

    private fun compatibilityOutcome(
        before: PlaybackMachineState,
        after: PlaybackMachineState,
        event: PlaybackEvent,
    ): CompatibilityOutcome? {
        val provenVideo = event is PlaybackEvent.FirstVideoFrame &&
            !before.snapshot.progress.renderedVideoFrame &&
            after.snapshot.progress.renderedVideoFrame
        val provenAudioOnly = event is PlaybackEvent.FirstAudio || event is PlaybackEvent.TracksAvailable
        val acceptedAudioOnly = provenAudioOnly &&
            before.snapshot.state != PlaybackState.PLAYING &&
            after.snapshot.state == PlaybackState.PLAYING &&
            after.snapshot.progress.discoveredTracks &&
            after.snapshot.progress.renderedAudio &&
            !after.snapshot.tracks.hasVideoTrack &&
            after.snapshot.tracks.audioTrackCount > 0
        if (provenVideo || acceptedAudioOnly) return CompatibilityOutcome.SUCCESS

        val failure = (event as? PlaybackEvent.Failed)?.failure ?: return null
        val acceptedFailure = before.snapshot.state in setOf(
            PlaybackState.ATTACHING_SURFACE,
            PlaybackState.STARTING_PRIMARY,
            PlaybackState.PLAYING,
            PlaybackState.DEGRADED,
        ) && after.snapshot.failure == failure
        return CompatibilityOutcome.DETERMINISTIC_FATAL.takeIf {
            acceptedFailure && failure.deterministic &&
                isLearnableCompatibilityFailure(failure.domain, failure.code)
        }
    }

    private suspend fun execute(action: PlaybackAction) {
        when (action) {
            is PlaybackAction.ResolveRequest -> resolve(action)
            is PlaybackAction.ResolveHandoffRequest -> resolveHandoff(action)
            is PlaybackAction.SelectPrimaryGraph -> select(action, failedGraph = null)
            is PlaybackAction.SelectHandoffGraph -> select(action, failedGraph = action.failedGraph)
            is PlaybackAction.AttachSurface -> attachSurface(action)
            is PlaybackAction.StartGraph -> startGraph(action)
            is PlaybackAction.SetPaused -> setPaused(action)
            is PlaybackAction.SeekTo -> seekTo(action)
            is PlaybackAction.SetPlaybackRate -> setPlaybackRate(action)
            is PlaybackAction.SelectAudioTrack -> selectAudioTrack(action)
            is PlaybackAction.SelectSubtitleTrack -> selectSubtitleTrack(action)
            is PlaybackAction.SetSubtitlesEnabled -> setSubtitlesEnabled(action)
            is PlaybackAction.AttachExternalSubtitle -> attachExternalSubtitle(action)
            is PlaybackAction.ResolvePreferencesChange -> resolveRequirementsChange(
                generation = action.generation,
                targetProfile = null,
                preferenceSnapshot = action.preferences,
            )
            is PlaybackAction.ResolveProfileChange -> resolveRequirementsChange(
                generation = action.generation,
                previousProfile = action.previousProfile,
                targetProfile = action.profile,
                preferenceSnapshot = preferences,
            )
            is PlaybackAction.ApplyRequirementsInPlace -> applyRequirements(action)
            is PlaybackAction.ApplyPlaybackOutput -> applyPlaybackOutput(action)
            is PlaybackAction.ReleaseActiveWork -> releaseActiveWork(action)
            is PlaybackAction.RecoverInPlace -> recoverOnce(action)
            is PlaybackAction.StartLiveReconnectLoop -> startLiveReconnectLoop(action)
        }
    }

    private fun resolve(action: PlaybackAction.ResolveRequest) {
        generationScope(action.generation).launch {
            diagnostics.record(
                PlaybackDiagnosticEvent(action.generation, PlaybackDiagnosticCode.REQUEST_RESOLUTION_STARTED),
            )
            val result = resolveLaunch(
                action.launch,
                FailurePhase.REQUEST_RESOLUTION,
                ProviderResolutionContext(ProviderResolutionTrigger.INITIAL),
            )
            lane.send(LaneMessage.ResolutionFinished(action.generation, result))
        }
    }

    private fun resolveHandoff(action: PlaybackAction.ResolveHandoffRequest) {
        generationScope(action.generation).launch {
            val result = resolveLaunch(
                action.launch,
                FailurePhase.RECOVERY,
                ProviderResolutionContext(
                    ProviderResolutionTrigger.HANDOFF,
                    action.failure.toProviderResolutionFeedback(),
                ),
            )
            lane.send(
                LaneMessage.HandoffResolutionFinished(
                    action.generation,
                    action.failedGraph,
                    action.failure,
                    result,
                ),
            )
        }
    }

    private suspend fun resolutionFinished(message: LaneMessage.ResolutionFinished) {
        if (!isCurrentGeneration(message.generation)) return
        when (val result = message.result) {
            is PlaybackResult.Success -> {
                resolved = GenerationValue(message.generation, result.value)
                diagnostics.record(
                    PlaybackDiagnosticEvent(message.generation, PlaybackDiagnosticCode.REQUEST_RESOLVED),
                )
                applyReducer(
                    PlaybackReducerInput.Event(
                        PlaybackEvent.RequestResolved(
                            message.generation,
                            result.value.summary,
                            result.value.evidence,
                            result.value.request,
                        ),
                    ),
                )
            }
            is PlaybackResult.Failure -> fail(message.generation, result.failure)
        }
    }

    private suspend fun handoffResolutionFinished(message: LaneMessage.HandoffResolutionFinished) {
        if (!isCurrentGeneration(message.generation) ||
            machine.snapshot.state != PlaybackState.SELECTING_GRAPH
        ) return
        when (val result = message.result) {
            is PlaybackResult.Success -> {
                resolved = GenerationValue(message.generation, result.value)
                applyReducer(
                    PlaybackReducerInput.Event(
                        PlaybackEvent.HandoffRequestResolved(
                            message.generation,
                            result.value.summary,
                            result.value.evidence,
                            result.value.request,
                            message.failedGraph,
                            message.failure,
                        ),
                    ),
                )
            }
            is PlaybackResult.Failure -> fail(message.generation, result.failure)
        }
    }

    private suspend fun freshResolutionReady(message: LaneMessage.FreshResolutionReady) {
        if (!isCurrentGeneration(message.generation) ||
            machine.snapshot.state !in setOf(
                PlaybackState.RECOVERING_IN_PLACE,
                PlaybackState.LIVE_RECONNECTING,
            )
        ) {
            message.accepted.complete(false)
            return
        }
        resolved = GenerationValue(message.generation, message.resolved)
        requirements = GenerationValue(message.generation, message.requirements)
        applyReducer(
            PlaybackReducerInput.Event(
                PlaybackEvent.RequestRefreshed(
                    message.generation,
                    message.resolved.summary,
                    message.resolved.evidence,
                    message.resolved.request,
                ),
            ),
        )
        message.accepted.complete(true)
    }

    private fun select(action: PlaybackAction, failedGraph: PlaybackGraph?) {
        val generation = action.generation
        val evidence = when (action) {
            is PlaybackAction.SelectPrimaryGraph -> action.evidence
            is PlaybackAction.SelectHandoffGraph -> machine.evidence
            else -> null
        } ?: run {
            tryFail(generation, internalFailure(FailurePhase.GRAPH_SELECTION))
            return
        }
        val profile = when (action) {
            is PlaybackAction.SelectPrimaryGraph -> action.profile
            else -> machine.snapshot.profile
        }
        val resolvedRequest = resolved.takeIf { it?.generation == generation }?.value
        if (resolvedRequest == null) {
            tryFail(generation, internalFailure(FailurePhase.GRAPH_SELECTION))
            return
        }
        val preferenceSnapshot = preferences
        generationScope(generation).launch {
            val requirementResult = resolveRequirements(
                summary = resolvedRequest.summary,
                evidence = evidence,
                profile = profile,
                preferenceSnapshot = preferenceSnapshot,
                compatibilityScopeKey = resolvedRequest.compatibilityScopeKey,
                phase = FailurePhase.GRAPH_SELECTION,
            )
            val selectionResult = when (requirementResult) {
                is PlaybackResult.Success -> {
                    val candidates = safeResult(FailurePhase.GRAPH_SELECTION) {
                        graphProvider.candidates(PlaybackGraphInput(requirementResult.value, evidence))
                    }
                    SelectionResult(requirementResult.value, candidates)
                }
                is PlaybackResult.Failure -> SelectionResult(
                    requirements = null,
                    candidates = PlaybackResult.Failure(requirementResult.failure),
                )
            }
            lane.send(
                LaneMessage.SelectionFinished(generation, failedGraph, profile, selectionResult),
            )
        }
    }

    private suspend fun resolveRequirements(
        summary: RequestSummary,
        evidence: StreamEvidence,
        profile: SessionProfile,
        preferenceSnapshot: PlaybackPreferences,
        compatibilityScopeKey: CompatibilityScopeKey?,
        phase: FailurePhase,
    ): PlaybackResult<PlaybackRequirements> {
        val environment = safeResult(phase) {
            environmentProvider.snapshot(
                PlaybackEnvironmentInput(
                    requestSummary = summary,
                    evidence = evidence,
                    profile = profile,
                    effectivePreferences = preferenceSnapshot,
                    compatibilityScopeKey = compatibilityScopeKey,
                ),
            )
        }
        if (environment is PlaybackResult.Failure) return environment
        environment as PlaybackResult.Success
        return safeResult(phase) {
            requirementsResolver.resolve(
                PlaybackRequirementsInput(
                    requestSummary = summary,
                    evidence = evidence,
                    profile = profile,
                    effectivePreferences = environment.value.effectivePreferences,
                    environment = environment.value.snapshot,
                ),
            )
        }
    }

    private suspend fun selectionFinished(message: LaneMessage.SelectionFinished) {
        if (!isCurrentGeneration(message.generation)) return
        val effective = message.result.requirements
        val candidates = message.result.candidates
        if (effective == null || candidates is PlaybackResult.Failure) {
            val failure = (candidates as PlaybackResult.Failure).failure
            fail(message.generation, failure)
            return
        }
        candidates as PlaybackResult.Success
        val selection = if (message.failedGraph == null) {
            policy.selectPrimary(PlaybackPolicy.SelectionInput(effective, candidates.value))
        } else {
            policy.selectHandoff(effective, candidates.value, message.failedGraph)
        }
        when (selection) {
            is PlaybackPolicy.Selection.Selected -> {
                requirements = GenerationValue(message.generation, effective)
                diagnostics.record(
                    PlaybackDiagnosticEvent(
                        generation = message.generation,
                        code = PlaybackDiagnosticCode.GRAPH_SELECTED,
                        engine = selection.graph.engine,
                    ),
                )
                applyReducer(
                    PlaybackReducerInput.Event(
                        PlaybackEvent.GraphSelected(message.generation, selection.graph),
                    ),
                )
            }
            is PlaybackPolicy.Selection.Rejected -> {
                fail(message.generation, selection.failure)
                if (message.profile == SessionProfile.FULLSCREEN) {
                    applyReducer(
                        PlaybackReducerInput.StreamAvailabilityChanged(
                            generation = message.generation,
                            availability = StreamAvailability.TerminallyUnavailable(
                                StreamUnavailableReason.NO_ELIGIBLE_GRAPH,
                                TerminalAvailabilityEvidence.ALL_ELIGIBLE_GRAPHS_EXHAUSTED,
                            ),
                            authority = TerminalAvailabilityAuthority.SESSION_POLICY,
                        ),
                    )
                }
            }
        }
    }

    private fun attachSurface(action: PlaybackAction.AttachSurface) {
        val engine = engineRegistry.engine(action.graph.engine)
        if (engine == null) {
            tryFail(action.generation, internalFailure(FailurePhase.SURFACE_ATTACHMENT))
            return
        }
        generationScope(action.generation).launch {
            when (val result = safeResult(FailurePhase.SURFACE_ATTACHMENT) {
                engine.attachSurface(action.generation, action.graph)
            }) {
                is PlaybackResult.Success -> lane.send(
                    LaneMessage.Reducer(
                        PlaybackReducerInput.Event(PlaybackEvent.SurfaceAttached(action.generation)),
                    ),
                )
                is PlaybackResult.Failure -> lane.send(
                    LaneMessage.Reducer(
                        PlaybackReducerInput.Event(PlaybackEvent.Failed(action.generation, result.failure)),
                    ),
                )
            }
        }
    }

    private fun startGraph(action: PlaybackAction.StartGraph) {
        val resolvedRequest = resolved.takeIf { it?.generation == action.generation }?.value
        val effective = requirements.takeIf { it?.generation == action.generation }?.value
        val engine = engineRegistry.engine(action.graph.engine)
        if (resolvedRequest == null || effective == null || engine == null) {
            tryFail(action.generation, internalFailure(FailurePhase.ENGINE_START))
            return
        }
        activeGraph = action.graph
        activeGraphGeneration = action.generation
        generationScope(action.generation).launch {
            intentionalReleases -= engine.type
            val output = requestPlaybackOutput(
                generation = action.generation,
                effective = effective,
                facts = VideoOutputFacts(),
                committed = false,
                phase = FailurePhase.ENGINE_START,
            )
            if (output is PlaybackResult.Failure) {
                fail(action.generation, output.failure)
                return@launch
            }
            lane.send(
                LaneMessage.Reducer(
                    PlaybackReducerInput.Event(PlaybackEvent.EngineStarting(action.generation)),
                ),
            )
            val result = safeResult(FailurePhase.ENGINE_START) {
                engine.start(
                    PlaybackEngineStart(
                        generation = action.generation,
                        request = resolvedRequest.request,
                        evidence = resolvedRequest.evidence,
                        graph = action.graph,
                        requirements = effective,
                        startPaused = action.startPaused,
                        startPositionMs = machine.snapshot.positionMs,
                        playbackRate = machine.snapshot.playbackRate,
                        restorationCheckpoint = if (resolvedRequest.request.contentType == ContentType.VOD) {
                            machine.snapshot.vodCheckpoint(effective.subtitleDelayMs)
                        } else {
                            null
                        },
                    ),
                )
            }
            if (result is PlaybackResult.Failure) fail(action.generation, result.failure)
        }
    }

    private fun setPaused(action: PlaybackAction.SetPaused) {
        val engine = activeGraph?.let { engineRegistry.engine(it.engine) } ?: return
        generationScope(action.generation).launch {
            val result = safeResult(FailurePhase.PLAYBACK) {
                engine.setPaused(action.generation, action.paused)
            }
            if (result is PlaybackResult.Failure) fail(action.generation, result.failure)
        }
    }

    private fun seekTo(action: PlaybackAction.SeekTo) = engineControl(action.generation) { engine ->
        engine.seekTo(action.generation, action.positionMs)
    }

    private fun setPlaybackRate(action: PlaybackAction.SetPlaybackRate) =
        engineControl(action.generation) { engine ->
            engine.setPlaybackRate(action.generation, action.rate)
        }

    private fun selectAudioTrack(action: PlaybackAction.SelectAudioTrack) =
        engineControl(action.generation) { engine ->
            engine.selectAudioTrack(action.generation, action.trackId)
        }

    private fun selectSubtitleTrack(action: PlaybackAction.SelectSubtitleTrack) =
        engineControl(action.generation) { engine ->
            engine.selectSubtitleTrack(action.generation, action.trackId)
        }

    private fun setSubtitlesEnabled(action: PlaybackAction.SetSubtitlesEnabled) =
        engineControl(action.generation) { engine ->
            engine.setSubtitlesEnabled(action.generation, action.enabled)
        }

    private fun attachExternalSubtitle(action: PlaybackAction.AttachExternalSubtitle) =
        engineControl(action.generation) { engine ->
            engine.attachExternalSubtitle(action.generation, action.subtitleId)
        }

    private fun engineControl(
        generation: Long,
        operation: suspend (PlaybackEngine) -> PlaybackResult<Unit>,
    ) {
        val engine = activeGraph?.let { engineRegistry.engine(it.engine) } ?: return
        generationScope(generation).launch {
            val result = safeResult(FailurePhase.PLAYBACK) { operation(engine) }
            val input = when (result) {
                is PlaybackResult.Success -> PlaybackReducerInput.ControlApplied(generation)
                is PlaybackResult.Failure -> {
                    diagnostics.record(
                        PlaybackDiagnosticEvent(
                            generation = generation,
                            code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                            engine = engine.type,
                            failure = result.failure,
                        ),
                    )
                    PlaybackReducerInput.ControlRejected(generation, result.failure)
                }
            }
            lane.send(LaneMessage.Reducer(input))
        }
    }

    private fun resolveRequirementsChange(
        generation: Long,
        previousProfile: SessionProfile? = null,
        targetProfile: SessionProfile?,
        preferenceSnapshot: PlaybackPreferences,
    ) {
        val resolvedRequest = resolved.takeIf { it?.generation == generation }?.value ?: return
        val evidence = machine.evidence ?: return
        val previous = requirements.takeIf { it?.generation == generation }?.value
        val profile = targetProfile ?: machine.snapshot.profile
        val changeId = ++latestRequirementsChangeId
        generationScope(generation).launch {
            val result = resolveRequirements(
                summary = resolvedRequest.summary,
                evidence = evidence,
                profile = profile,
                preferenceSnapshot = preferenceSnapshot,
                compatibilityScopeKey = resolvedRequest.compatibilityScopeKey,
                phase = FailurePhase.PLAYBACK,
            )
            lane.send(
                LaneMessage.RequirementsChangeFinished(
                    changeId = changeId,
                    generation = generation,
                    previousProfile = previousProfile,
                    targetProfile = targetProfile,
                    previous = previous,
                    result = result,
                ),
            )
        }
    }

    private suspend fun requirementsChangeFinished(message: LaneMessage.RequirementsChangeFinished) {
        if (message.changeId != latestRequirementsChangeId || !isCurrentGeneration(message.generation)) return
        when (val result = message.result) {
            is PlaybackResult.Failure -> {
                diagnostics.record(
                    PlaybackDiagnosticEvent(
                        generation = message.generation,
                        code = PlaybackDiagnosticCode.REQUIREMENTS_CHANGE_REJECTED,
                        failure = result.failure,
                    ),
                )
                applyReducer(
                    PlaybackReducerInput.RequirementsChangeRejected(
                        generation = message.generation,
                        previousProfile = message.previous?.profile ?: message.previousProfile,
                    ),
                )
            }
            is PlaybackResult.Success -> {
                if (message.previous == result.value) {
                    requirements = GenerationValue(message.generation, result.value)
                    return
                }
                val impact = message.previous?.let {
                    PlaybackRequirementsDiffClassifier.classify(it, result.value).impact
                } ?: ChangeImpact.RESELECT_GRAPH
                // Rebuild uses this exact coherent snapshot after its release barrier. Reselection
                // will refresh it as part of selecting the new graph.
                requirements = GenerationValue(message.generation, result.value)
                applyReducer(
                    PlaybackReducerInput.RequirementsChangeResolved(
                        changeId = message.changeId,
                        generation = message.generation,
                        previousProfile = message.previousProfile,
                        targetProfile = message.targetProfile,
                        requirements = result.value,
                        impact = impact,
                    ),
                )
            }
        }
    }

    private fun applyRequirements(action: PlaybackAction.ApplyRequirementsInPlace) {
        val graph = activeGraph ?: return
        val engine = engineRegistry.engine(graph.engine) ?: return
        val previousApply = requirementsApplyJob
        requirementsApplyJob = generationScope(action.generation).launch {
            previousApply?.cancelAndJoin()
            if (action.changeId != latestRequirementsChangeId) return@launch
            val output = requestPlaybackOutput(
                generation = action.generation,
                effective = action.requirements,
                facts = machine.snapshot.videoOutputFacts,
                committed = machine.snapshot.progress.renderedVideoFrame,
                phase = FailurePhase.PLAYBACK,
            )
            if (output is PlaybackResult.Failure) {
                fail(action.generation, output.failure)
                return@launch
            }
            if (action.changeId != latestRequirementsChangeId) return@launch
            val engineResult = safeResult(FailurePhase.PLAYBACK) {
                engine.applyRequirements(action.generation, action.requirements)
            }
            lane.send(
                LaneMessage.RequirementsApplied(
                    action.generation,
                    action.changeId,
                    action.requirements,
                    engineResult,
                ),
            )
        }
    }

    private suspend fun requirementsApplied(message: LaneMessage.RequirementsApplied) {
        if (!isCurrentGeneration(message.generation) || message.changeId != latestRequirementsChangeId) return
        when (val result = message.result) {
            is PlaybackResult.Success -> requirements = GenerationValue(message.generation, message.requirements)
            is PlaybackResult.Failure -> fail(message.generation, result.failure)
        }
    }

    private fun applyPlaybackOutput(action: PlaybackAction.ApplyPlaybackOutput) {
        val effective = requirements.takeIf { it?.generation == action.generation }?.value ?: return
        generationScope(action.generation).launch {
            val output = requestPlaybackOutput(
                generation = action.generation,
                effective = effective,
                facts = action.facts,
                committed = true,
                phase = FailurePhase.PLAYBACK,
            )
            if (output is PlaybackResult.Failure) fail(action.generation, output.failure)
        }
    }

    private suspend fun requestPlaybackOutput(
        generation: Long,
        effective: PlaybackRequirements,
        facts: VideoOutputFacts,
        committed: Boolean,
        phase: FailurePhase,
    ): PlaybackResult<PlaybackOutputApplication> {
        val request = PlaybackOutputRequest(generation, effective, facts, committed)
        val result = outputMutex.withLock {
            safeResult(phase) { outputController.apply(request) }
        }
        if (result is PlaybackResult.Success) {
            if (result.value.status in NONFATAL_OUTPUT_STATUSES) {
                diagnostics.record(
                    PlaybackDiagnosticEvent(
                        generation = generation,
                        code = PlaybackDiagnosticCode.PLAYBACK_OUTPUT_NONFATAL,
                        outputStatus = result.value.status,
                    ),
                )
            }
            lane.send(
                LaneMessage.Reducer(
                    PlaybackReducerInput.PlaybackOutputApplied(request, result.value),
                ),
            )
        }
        return result
    }

    private fun releaseActiveWork(action: PlaybackAction.ReleaseActiveWork) {
        cancelWatchdog()
        val workToCancel = generationJob
        workToCancel?.cancel()
        generationJob = null
        reconnectJob = null
        requirementsApplyJob = null
        diagnostics.record(
            PlaybackDiagnosticEvent(action.generation, PlaybackDiagnosticCode.RELEASE_BARRIER_STARTED),
        )
        val graph = activeGraph ?: machine.graphBeforeRelease
        val releasedGraphGeneration = activeGraphGeneration
        val engine = graph?.let { engineRegistry.engine(it.engine) }
        scope.launch {
            workToCancel?.cancelAndJoin()
            val releaseResult = releaseAdapterUntilComplete(
                generation = action.generation,
                engine = engine,
                engineGeneration = releasedGraphGeneration,
            )
            if (releaseResult is PlaybackResult.Failure) {
                lane.send(
                    LaneMessage.BarrierFinished(
                        action.releaseEpoch,
                        action.reason,
                        releaseResult.failure,
                    ),
                )
                return@launch
            }
            val outputResult = outputMutex.withLock {
                safeResult(FailurePhase.RELEASE) {
                    outputController.reset(releasedGraphGeneration, action.reason)
                }
            }
            if (outputResult is PlaybackResult.Failure) {
                diagnostics.record(
                    PlaybackDiagnosticEvent(
                        generation = action.generation,
                        code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                        failure = outputResult.failure,
                    ),
                )
            }
            lane.send(
                LaneMessage.BarrierFinished(
                    action.releaseEpoch,
                    action.reason,
                    failure = null,
                ),
            )
        }
    }

    private suspend fun barrierFinished(message: LaneMessage.BarrierFinished) {
        if (machine.activeReleaseEpoch != message.releaseEpoch) return
        if (message.failure != null) {
            diagnostics.record(
                PlaybackDiagnosticEvent(
                    generation = machine.snapshot.generation,
                    code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                    failure = message.failure,
                ),
            )
            applyReducer(
                PlaybackReducerInput.BarrierFailed(message.releaseEpoch, message.failure),
            )
            return
        }
        activeGraph = null
        activeGraphGeneration = null
        if (message.reason in setOf(
                ActiveWorkReleaseReason.STOP,
                ActiveWorkReleaseReason.REPLACE_REQUEST,
                ActiveWorkReleaseReason.COMPLETED,
                ActiveWorkReleaseReason.FAILURE,
            )
        ) {
            resolved = null
            requirements = null
        }
        diagnostics.record(
            PlaybackDiagnosticEvent(
                machine.snapshot.generation,
                PlaybackDiagnosticCode.RELEASE_BARRIER_COMPLETED,
            ),
        )
        applyReducer(PlaybackReducerInput.BarrierCompleted(message.releaseEpoch))
    }

    private fun recoverOnce(action: PlaybackAction.RecoverInPlace) {
        val graph = activeGraph ?: return
        val engine = engineRegistry.engine(graph.engine) ?: return
        val original = machine.launch ?: return
        val cachedResolved = resolved.takeIf { it?.generation == action.generation }?.value
        val cachedEffective = requirements.takeIf { it?.generation == action.generation }?.value ?: return
        val profileSnapshot = machine.snapshot.profile
        val preferenceSnapshot = preferences
        val startPaused = machine.paused
        generationScope(action.generation).launch {
            val release = releaseAdapterUntilComplete(action.generation, engine)
            if (release is PlaybackResult.Failure) {
                recoveryFailed(action.generation, release.failure)
                return@launch
            }
            // Deferred links are resolved after every release even when the triggering failure did
            // not explicitly demand freshness: Stalker/create-link and catch-up URLs may be
            // single-use, so reopening the cached concrete URL would violate the provider contract.
            val needsFreshResolution =
                action.freshRequestRequired || original is PlaybackLaunch.DeferredProvider
            val resolvedRequest = if (needsFreshResolution) {
                when (val result = resolveLaunch(
                    original,
                    FailurePhase.RECOVERY,
                    ProviderResolutionContext(
                        ProviderResolutionTrigger.RECOVERY,
                        action.failure.toProviderResolutionFeedback(),
                    ),
                )) {
                    is PlaybackResult.Success -> result.value
                    is PlaybackResult.Failure -> {
                        recoveryFailed(action.generation, result.failure)
                        return@launch
                    }
                }
            } else {
                cachedResolved ?: return@launch
            }
            val effective = if (needsFreshResolution) {
                when (val result = resolveRequirements(
                    resolvedRequest.summary,
                    resolvedRequest.evidence,
                    profileSnapshot,
                    preferenceSnapshot,
                    resolvedRequest.compatibilityScopeKey,
                    FailurePhase.RECOVERY,
                )) {
                    is PlaybackResult.Success -> result.value
                    is PlaybackResult.Failure -> {
                        recoveryFailed(action.generation, result.failure)
                        return@launch
                    }
                }
            } else {
                cachedEffective
            }
            if (needsFreshResolution &&
                !commitFreshResolution(action.generation, resolvedRequest, effective)
            ) return@launch
            val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                awaitReconnectOutcome(action.generation)
            }
            val attached = safeResult(FailurePhase.RECOVERY) {
                engine.attachSurface(action.generation, graph)
            }
            if (attached is PlaybackResult.Failure) {
                outcome.cancel()
                recoveryFailed(action.generation, attached.failure)
                return@launch
            }
            lane.send(
                LaneMessage.Reducer(
                    PlaybackReducerInput.Event(PlaybackEvent.EngineStarting(action.generation)),
                ),
            )
            intentionalReleases -= engine.type
            val started = safeResult(FailurePhase.RECOVERY) {
                engine.start(
                    PlaybackEngineStart(
                        generation = action.generation,
                        request = resolvedRequest.request,
                        evidence = resolvedRequest.evidence,
                        graph = graph,
                        requirements = effective,
                        startPaused = startPaused,
                        startPositionMs = machine.snapshot.positionMs,
                        playbackRate = machine.snapshot.playbackRate,
                        restorationCheckpoint = if (resolvedRequest.request.contentType == ContentType.VOD) {
                            machine.snapshot.vodCheckpoint(effective.subtitleDelayMs)
                        } else {
                            null
                        },
                    ),
                )
            }
            if (started is PlaybackResult.Failure) {
                outcome.cancel()
                recoveryFailed(action.generation, started.failure)
                return@launch
            }
            when (val event = outcome.await()) {
                is PlaybackEvent.Failed -> recoveryFailed(action.generation, event.failure)
                is PlaybackEvent.PlaybackEnded -> recoveryFailed(
                    action.generation,
                    internalFailure(FailurePhase.RECOVERY),
                )
                else -> Unit
            }
        }
    }

    private fun startLiveReconnectLoop(action: PlaybackAction.StartLiveReconnectLoop) {
        if (reconnectJob?.isActive == true) return
        val graph = activeGraph ?: return
        val engine = engineRegistry.engine(graph.engine) ?: return
        val original = machine.launch ?: return
        val profileSnapshot = machine.snapshot.profile
        val preferenceSnapshot = preferences
        val reconnectFeedback = machine.snapshot.failure?.toProviderResolutionFeedback()
        reconnectJob = generationScope(action.generation).launch {
            var attempt = 0
            while (
                currentCoroutineContext().isActive &&
                isCurrentReconnect(action.generation)
            ) {
                clock.delayMs(policy.liveReconnectDelayMs(attempt))
                if (!isCurrentReconnect(action.generation)) return@launch
                if (!_surfaceAvailable.value) {
                    clock.delayMs(SURFACE_RECHECK_MS)
                    continue
                }
                diagnostics.record(
                    PlaybackDiagnosticEvent(
                        generation = action.generation,
                        code = PlaybackDiagnosticCode.LIVE_RECONNECT_ATTEMPT,
                        engine = graph.engine,
                        attempt = attempt,
                    ),
                )
                val release = releaseAdapterUntilComplete(action.generation, engine)
                if (release is PlaybackResult.Failure) {
                    liveReconnectEscalated(action.generation, release.failure)
                    return@launch
                }
                if (!isCurrentReconnect(action.generation)) return@launch

                // Always ask the resolver again. Static URLs may be returned unchanged; provider
                // adapters can mint a fresh single-use link only after the prior connection closes.
                val resolvedRequest = when (val result = resolveLaunch(
                    original,
                    FailurePhase.RECOVERY,
                    ProviderResolutionContext(
                        ProviderResolutionTrigger.RECOVERY,
                        reconnectFeedback,
                    ),
                )) {
                    is PlaybackResult.Success -> result.value
                    is PlaybackResult.Failure -> {
                        if (result.failure.shouldEscalateLiveReconnect()) {
                            liveReconnectEscalated(action.generation, result.failure)
                            return@launch
                        }
                        attempt++
                        continue
                    }
                }
                val effective = when (val result = resolveRequirements(
                    summary = resolvedRequest.summary,
                    evidence = resolvedRequest.evidence,
                    profile = profileSnapshot,
                    preferenceSnapshot = preferenceSnapshot,
                    compatibilityScopeKey = resolvedRequest.compatibilityScopeKey,
                    phase = FailurePhase.RECOVERY,
                )) {
                    is PlaybackResult.Success -> result.value
                    is PlaybackResult.Failure -> {
                        if (result.failure.shouldEscalateLiveReconnect()) {
                            liveReconnectEscalated(action.generation, result.failure)
                            return@launch
                        }
                        attempt++
                        continue
                    }
                }
                if (!commitFreshResolution(action.generation, resolvedRequest, effective)) {
                    return@launch
                }
                val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                    awaitReconnectOutcome(action.generation)
                }
                intentionalReleases -= engine.type
                val attached = safeResult(FailurePhase.RECOVERY) {
                    engine.attachSurface(action.generation, graph)
                }
                if (attached is PlaybackResult.Failure) {
                    outcome.cancel()
                    if (attached.failure.shouldEscalateLiveReconnect()) {
                        liveReconnectEscalated(action.generation, attached.failure)
                        return@launch
                    }
                    attempt++
                    continue
                }
                val output = requestPlaybackOutput(
                    generation = action.generation,
                    effective = effective,
                    facts = VideoOutputFacts(),
                    committed = false,
                    phase = FailurePhase.RECOVERY,
                )
                if (output is PlaybackResult.Failure) {
                    outcome.cancel()
                    if (output.failure.shouldEscalateLiveReconnect()) {
                        liveReconnectEscalated(action.generation, output.failure)
                        return@launch
                    }
                    attempt++
                    continue
                }
                val started = safeResult(FailurePhase.RECOVERY) {
                    engine.start(
                        PlaybackEngineStart(
                            generation = action.generation,
                            request = resolvedRequest.request,
                            evidence = resolvedRequest.evidence,
                            graph = graph,
                            requirements = effective,
                            startPaused = false,
                        ),
                    )
                }
                if (started is PlaybackResult.Failure) {
                    outcome.cancel()
                    if (started.failure.shouldEscalateLiveReconnect()) {
                        liveReconnectEscalated(action.generation, started.failure)
                        return@launch
                    }
                    attempt++
                    continue
                }
                when (val event = outcome.await()) {
                    is PlaybackEvent.FirstAudio,
                    is PlaybackEvent.FirstVideoFrame,
                    -> {
                        diagnostics.record(
                            PlaybackDiagnosticEvent(
                                generation = action.generation,
                                code = PlaybackDiagnosticCode.LIVE_RECONNECT_SUCCEEDED,
                                engine = graph.engine,
                                attempt = attempt,
                            ),
                        )
                        return@launch
                    }
                    is PlaybackEvent.Failed -> {
                        if (event.failure.shouldEscalateLiveReconnect()) {
                            liveReconnectEscalated(action.generation, event.failure)
                            return@launch
                        }
                        attempt++
                    }
                    else -> attempt++
                }
            }
        }
    }

    private suspend fun releaseAdapterUntilComplete(
        generation: Long,
        engine: PlaybackEngine?,
        engineGeneration: Long? = activeGraphGeneration,
    ): PlaybackResult<Unit> {
        if (engine == null) return PlaybackResult.Success(Unit)
        // A replacement action already owns the next session generation, while the adapter still
        // owns the graph generation being torn down. Adapter generation checks must receive that
        // old owner; using the replacement generation makes both graceful release and hard abort
        // reject as stale and leaves the decoder/surface wedged.
        val adapterGeneration = engineGeneration ?: generation
        intentionalReleases[engine.type] = setOfNotNull(generation, engineGeneration)
        val graceful = withTimeoutOrNull(releaseTimeoutMs) {
            safeResult(FailurePhase.RELEASE) { engine.release(adapterGeneration) }
        }
        if (graceful is PlaybackResult.Success) return graceful
        diagnostics.record(
            PlaybackDiagnosticEvent(
                generation = generation,
                code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                engine = engine.type,
                failure = graceful?.failureOrNull() ?: releaseFailure(),
            ),
        )

        val abort = withTimeoutOrNull(releaseTimeoutMs) {
            safeResult(FailurePhase.RELEASE) { engine.hardAbort(adapterGeneration) }
        }
        if (abort is PlaybackResult.Success) return abort
        diagnostics.record(
            PlaybackDiagnosticEvent(
                generation = generation,
                code = PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                engine = engine.type,
                failure = abort?.failureOrNull() ?: releaseFailure(),
            ),
        )
        // The adapter-specific cause is diagnostic evidence. At the session boundary, the only
        // safe semantic is that resource ownership could not be proven ended. It is fatal and can
        // never authorize a handoff, reconnect, or second provider request.
        return PlaybackResult.Failure(releaseFailure())
    }

    /**
     * Reconciles one generation/attempt-bound watchdog from immutable facts after every reducer
     * input. Timer and metric completions return through [lane] before they can cause an action.
     */
    private fun reconcileWatchdog() {
        if (!policy.watchdogEnabled()) {
            cancelWatchdog()
            return
        }
        val desired = desiredWatchdogPhase() ?: run {
            cancelWatchdog()
            return
        }
        val current = watchdogArm
        if (
            current?.generation == machine.snapshot.generation &&
            current.attempt == engineAttempt &&
            current.phase == desired
        ) {
            return
        }
        cancelWatchdog()
        check(nextWatchdogToken < Long.MAX_VALUE) { "Playback watchdog token exhausted" }
        val arm = WatchdogArm(
            token = nextWatchdogToken++,
            generation = machine.snapshot.generation,
            attempt = engineAttempt,
            phase = desired,
        )
        watchdogArm = arm
        watchdogJob = if (desired == PlaybackPolicy.WatchdogPhase.RUNTIME_VIDEO_PROGRESS) {
            startRuntimeWindow(arm)
        } else {
            generationScope(arm.generation).launch {
                val request = resolved.takeIf { it?.generation == arm.generation }?.value?.request
                    ?: return@launch
                clock.delayMs(policy.watchdogDelayMs(desired, request.contentType, request.network))
                lane.send(
                    LaneMessage.WatchdogExpired(
                        token = arm.token,
                        generation = arm.generation,
                        attempt = arm.attempt,
                        phase = arm.phase,
                    ),
                )
            }
        }
    }

    private fun desiredWatchdogPhase(): PlaybackPolicy.WatchdogPhase? {
        val snapshot = machine.snapshot
        if (
            engineAttemptGeneration != snapshot.generation ||
            !machine.sessionActive ||
            !machine.lifecycleActive ||
            activeGraphGeneration != snapshot.generation
        ) {
            return null
        }
        return when (snapshot.state) {
            PlaybackState.STARTING_PRIMARY -> when {
                !snapshot.progress.receivedBytes -> PlaybackPolicy.WatchdogPhase.FIRST_MEDIA_BYTE
                !snapshot.progress.discoveredTracks -> PlaybackPolicy.WatchdogPhase.BYTES_TO_TRACKS
                snapshot.tracks.hasVideoTrack &&
                    !(snapshot.progress.decoderReady || snapshot.progress.rendererReady) -> {
                    PlaybackPolicy.WatchdogPhase.VIDEO_TRACKS_TO_READY
                }
                snapshot.tracks.hasVideoTrack &&
                    !machine.paused &&
                    !snapshot.progress.renderedVideoFrame &&
                    (snapshot.progress.decoderReady || snapshot.progress.rendererReady) -> {
                    PlaybackPolicy.WatchdogPhase.READY_TO_FIRST_VIDEO_FRAME
                }
                else -> null
            }
            PlaybackState.PLAYING -> if (
                !machine.paused &&
                !snapshot.isBuffering &&
                snapshot.progress.discoveredTracks &&
                snapshot.tracks.hasVideoTrack &&
                snapshot.progress.renderedVideoFrame &&
                runtimeMetricsUnavailableAttempt != engineAttempt
            ) {
                PlaybackPolicy.WatchdogPhase.RUNTIME_VIDEO_PROGRESS
            } else {
                null
            }
            else -> null
        }
    }

    private fun startRuntimeWindow(arm: WatchdogArm): Job {
        val engine = activeGraph?.let { engineRegistry.engine(it.engine) }
        return generationScope(arm.generation).launch {
            val before = engine?.let { metricsSnapshot(it, arm.generation) }
            val request = resolved.takeIf { it?.generation == arm.generation }?.value?.request
                ?: return@launch
            clock.delayMs(
                policy.watchdogDelayMs(
                    PlaybackPolicy.WatchdogPhase.RUNTIME_VIDEO_PROGRESS,
                    request.contentType,
                    request.network,
                ),
            )
            val after = engine?.let { metricsSnapshot(it, arm.generation) }
            lane.send(
                LaneMessage.RuntimeWindowFinished(
                    token = arm.token,
                    generation = arm.generation,
                    attempt = arm.attempt,
                    before = before,
                    after = after,
                ),
            )
        }
    }

    private suspend fun metricsSnapshot(
        engine: PlaybackEngine,
        generation: Long,
    ): PlaybackEngineMetricsSnapshot? = try {
        when (val result = engine.snapshotMetrics(generation)) {
            is PlaybackResult.Success -> result.value.takeIf { it.generation == generation }
            is PlaybackResult.Failure -> null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun watchdogExpired(message: LaneMessage.WatchdogExpired) {
        val arm = watchdogArm
        if (
            arm?.token != message.token ||
            arm.generation != message.generation ||
            arm.attempt != message.attempt ||
            arm.phase != message.phase ||
            desiredWatchdogPhase() != message.phase
        ) {
            return
        }
        watchdogArm = null
        watchdogJob = null
        emitWatchdogFailure(message.generation, message.phase)
    }

    private suspend fun runtimeWindowFinished(message: LaneMessage.RuntimeWindowFinished) {
        val arm = watchdogArm
        if (
            arm?.token != message.token ||
            arm.generation != message.generation ||
            arm.attempt != message.attempt ||
            arm.phase != PlaybackPolicy.WatchdogPhase.RUNTIME_VIDEO_PROGRESS ||
            desiredWatchdogPhase() != PlaybackPolicy.WatchdogPhase.RUNTIME_VIDEO_PROGRESS
        ) {
            return
        }
        watchdogArm = null
        watchdogJob = null
        when {
            message.before == null || message.after == null -> {
                runtimeMetricsUnavailableAttempt = message.attempt
                reconcileWatchdog()
            }
            policy.renderedVideoAdvanced(message.before, message.after) == true -> reconcileWatchdog()
            policy.renderedVideoAdvanced(message.before, message.after) == null -> {
                runtimeMetricsUnavailableAttempt = message.attempt
                reconcileWatchdog()
            }
            else -> emitWatchdogFailure(
                message.generation,
                PlaybackPolicy.WatchdogPhase.RUNTIME_VIDEO_PROGRESS,
            )
        }
    }

    private suspend fun emitWatchdogFailure(
        generation: Long,
        phase: PlaybackPolicy.WatchdogPhase,
    ) {
        val evidence = machine.evidence ?: return
        val failure = policy.watchdogFailure(phase, evidence)
        diagnostics.record(
            PlaybackDiagnosticEvent(
                generation = generation,
                code = PlaybackDiagnosticCode.WATCHDOG_EXPIRED,
                engine = activeGraph?.engine,
                failure = failure,
            ),
        )
        val event = PlaybackEvent.Failed(generation, failure)
        engineEvents.emit(event)
        applyReducer(PlaybackReducerInput.Event(event))
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        watchdogArm = null
    }

    private fun generationScope(forGeneration: Long): CoroutineScope {
        if (generation != forGeneration || generationJob == null) {
            generationJob?.cancel()
            generation = forGeneration
            generationJob = SupervisorJob(sessionJob)
        }
        return CoroutineScope(scope.coroutineContext + requireNotNull(generationJob))
    }

    private suspend fun fail(generation: Long, failure: PlaybackFailure) {
        diagnostics.record(
            PlaybackDiagnosticEvent(
                generation,
                PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                failure = failure,
            ),
        )
        lane.send(
            LaneMessage.Reducer(
                PlaybackReducerInput.Event(PlaybackEvent.Failed(generation, failure)),
            ),
        )
    }

    private suspend fun recoveryFailed(generation: Long, failure: PlaybackFailure) {
        lane.send(
            LaneMessage.Reducer(
                PlaybackReducerInput.RecoveryAttemptFailed(generation, failure),
            ),
        )
    }

    private suspend fun liveReconnectEscalated(generation: Long, failure: PlaybackFailure) {
        lane.send(
            LaneMessage.Reducer(
                PlaybackReducerInput.LiveReconnectEscalated(generation, failure),
            ),
        )
    }

    private fun tryFail(generation: Long, failure: PlaybackFailure) {
        lane.trySend(
            LaneMessage.Reducer(
                PlaybackReducerInput.Event(PlaybackEvent.Failed(generation, failure)),
            ),
        )
    }

    private suspend fun <T> safeResult(
        phase: FailurePhase,
        block: suspend () -> PlaybackResult<T>,
    ): PlaybackResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PlaybackResult.Failure(internalFailure(phase))
    }

    /** The only provider-resolution entrance; callers invoke it after the relevant release proof. */
    private suspend fun resolveLaunch(
        launch: PlaybackLaunch,
        phase: FailurePhase,
        context: ProviderResolutionContext,
    ): PlaybackResult<ResolvedPlaybackRequest> {
        val raw = safeResult(phase) {
            when (launch) {
                is PlaybackLaunch.ConcreteRequest -> requestResolver.resolve(launch.request)
                is PlaybackLaunch.DeferredProvider -> {
                    val resolver = providerPlaybackResolver ?: return@safeResult PlaybackResult.Failure(
                        internalFailure(phase).copy(deterministic = true),
                    )
                    resolver.resolve(launch.selection, context)
                }
            }
        }
        if (raw is PlaybackResult.Failure) {
            return PlaybackResult.Failure(raw.failure.copy(phase = phase))
        }
        val resolvedRequest = (raw as PlaybackResult.Success).value
        val request = resolvedRequest.request
        val valid = when (launch) {
            is PlaybackLaunch.ConcreteRequest ->
                request.contentType == launch.request.contentType &&
                    request.contentKey == launch.request.contentKey &&
                    request.providerConnectionLimit == launch.request.providerConnectionLimit
            is PlaybackLaunch.DeferredProvider -> {
                val selection = launch.selection
                request.contentType == selection.contentType &&
                    request.contentKey?.value == selection.contentKey.value &&
                    request.providerConnectionLimit == selection.providerConnectionLimit
            }
        }
        if (!valid) {
            return PlaybackResult.Failure(internalFailure(phase).copy(deterministic = true))
        }
        val evidence = when (launch) {
            is PlaybackLaunch.ConcreteRequest -> resolvedRequest.evidence
            is PlaybackLaunch.DeferredProvider ->
                mergeEvidence(launch.selection.declaredEvidence, resolvedRequest.evidence)
        }
        return PlaybackResult.Success(
            ResolvedPlaybackRequest(
                request = request,
                summary = request.summary(),
                evidence = evidence,
                compatibilityScopeKey = resolvedRequest.compatibilityScopeKey,
            ),
        )
    }

    private suspend fun commitFreshResolution(
        generation: Long,
        resolvedRequest: ResolvedPlaybackRequest,
        effective: PlaybackRequirements,
    ): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        lane.send(
            LaneMessage.FreshResolutionReady(
                generation,
                resolvedRequest,
                effective,
                accepted,
            ),
        )
        return accepted.await()
    }

    private fun PlaybackFailure.toProviderResolutionFeedback(): ProviderResolutionFeedback {
        val transportFailure = domain in setOf(
            FailureDomain.NETWORK,
            FailureDomain.TLS,
            FailureDomain.MANIFEST,
            FailureDomain.DEMUX,
        )
        return ProviderResolutionFeedback(
            code = code,
            domain = domain,
            phase = phase,
            dialectAdvanceEligibility = if (transportFailure) {
                ProviderDialectAdvanceEligibility.TRANSPORT_OR_DEMUX_FAILURE
            } else {
                ProviderDialectAdvanceEligibility.INELIGIBLE_PLAYBACK_FAILURE
            },
        )
    }

    private fun mergeEvidence(declared: StreamEvidence, resolved: StreamEvidence): StreamEvidence =
        StreamEvidence(
            delivery = strongest(declared.delivery, resolved.delivery),
            container = strongest(declared.container, resolved.container),
            videoCodec = strongest(declared.videoCodec, resolved.videoCodec),
            audioCodec = strongest(declared.audioCodec, resolved.audioCodec),
            subtitleFormat = strongest(declared.subtitleFormat, resolved.subtitleFormat),
            drmScheme = strongest(declared.drmScheme, resolved.drmScheme),
            dimensions = strongest(declared.dimensions, resolved.dimensions),
            frameRate = strongest(declared.frameRate, resolved.frameRate),
            adaptive = strongest(declared.adaptive, resolved.adaptive),
        )

    private fun <T> strongest(
        declared: EvidenceFact<T>?,
        resolved: EvidenceFact<T>?,
    ): EvidenceFact<T>? = when {
        declared == null -> resolved
        resolved == null -> declared
        resolved.provenance.strength >= declared.provenance.strength -> resolved
        else -> declared
    }

    private val EvidenceProvenance.strength: Int
        get() = when (this) {
            EvidenceProvenance.EXTRACTOR_CONFIRMED -> 80
            EvidenceProvenance.MANIFEST_CONFIRMED -> 70
            EvidenceProvenance.HLS_CODECS_ATTRIBUTE -> 65
            EvidenceProvenance.SEGMENT_HINT -> 60
            EvidenceProvenance.PROVIDER_DECLARED -> 50
            EvidenceProvenance.HTTP_MIME_HINT -> 40
            EvidenceProvenance.URL_INFERRED -> 20
            EvidenceProvenance.UNKNOWN -> 0
        }

    private fun internalFailure(phase: FailurePhase) = PlaybackFailure(
        code = FailureCode.UNKNOWN,
        domain = FailureDomain.UNKNOWN,
        phase = phase,
        retryability = Retryability.FATAL,
    )

    private fun releaseFailure() = PlaybackFailure(
        code = FailureCode.RESOURCE_RELEASE_FAILED,
        domain = FailureDomain.DEVICE_RESOURCE,
        phase = FailurePhase.RELEASE,
        retryability = Retryability.FATAL,
        deterministic = true,
    )

    private fun isCurrentReconnect(forGeneration: Long): Boolean =
        snapshot.value.generation == forGeneration &&
            snapshot.value.state == PlaybackState.LIVE_RECONNECTING

    private fun isCurrentGeneration(forGeneration: Long): Boolean =
        machine.snapshot.generation == forGeneration

    private fun isIntentionalTerminal(engine: EngineType, event: PlaybackEvent): Boolean =
        intentionalReleases[engine]?.contains(event.generation) == true &&
            event is PlaybackEvent.PlaybackEnded &&
            event.reason in setOf(PlaybackEndReason.STOPPED, PlaybackEndReason.SHUTDOWN)

    private suspend fun awaitReconnectOutcome(generation: Long): PlaybackEvent {
        var videoExpected: Boolean? = machine.snapshot.tracks
            .takeIf { machine.snapshot.progress.discoveredTracks }
            ?.hasVideoTrack
        return engineEvents.first { event ->
            if (event.generation != generation) return@first false
            when (event) {
                is PlaybackEvent.TracksAvailable -> {
                    videoExpected = event.hasVideo
                    false
                }
                is PlaybackEvent.FirstAudio -> videoExpected == false
                is PlaybackEvent.FirstVideoFrame,
                is PlaybackEvent.Failed,
                is PlaybackEvent.PlaybackEnded,
                -> true
                else -> false
            }
        }
    }

    private fun PlaybackFailure.shouldEscalateLiveReconnect(): Boolean =
        retryability == Retryability.FATAL ||
            (retryability == Retryability.HANDOFF_ELIGIBLE && deterministic)

    private fun PlaybackResult<Unit>.failureOrNull(): PlaybackFailure? =
        (this as? PlaybackResult.Failure)?.failure

    private data class GenerationValue<T>(val generation: Long, val value: T)

    private data class SelectionResult(
        val requirements: PlaybackRequirements?,
        val candidates: PlaybackResult<List<PlaybackGraph>>,
    )

    private data class WatchdogArm(
        val token: Long,
        val generation: Long,
        val attempt: Long,
        val phase: PlaybackPolicy.WatchdogPhase,
    )

    private data class CompatibilityRecordingKey(
        val generation: Long,
        val graph: CompatibilityGraphFingerprint,
        val outcome: CompatibilityOutcome,
    )

    private sealed interface LaneMessage {
        data class Reducer(val input: PlaybackReducerInput) : LaneMessage
        data class ResolutionFinished(
            val generation: Long,
            val result: PlaybackResult<ResolvedPlaybackRequest>,
        ) : LaneMessage
        data class HandoffResolutionFinished(
            val generation: Long,
            val failedGraph: PlaybackGraph,
            val failure: PlaybackFailure,
            val result: PlaybackResult<ResolvedPlaybackRequest>,
        ) : LaneMessage
        data class FreshResolutionReady(
            val generation: Long,
            val resolved: ResolvedPlaybackRequest,
            val requirements: PlaybackRequirements,
            val accepted: CompletableDeferred<Boolean>,
        ) : LaneMessage
        data class SelectionFinished(
            val generation: Long,
            val failedGraph: PlaybackGraph?,
            val profile: SessionProfile,
            val result: SelectionResult,
        ) : LaneMessage
        data class RequirementsChangeFinished(
            val changeId: Long,
            val generation: Long,
            val previousProfile: SessionProfile?,
            val targetProfile: SessionProfile?,
            val previous: PlaybackRequirements?,
            val result: PlaybackResult<PlaybackRequirements>,
        ) : LaneMessage
        data class RequirementsApplied(
            val generation: Long,
            val changeId: Long,
            val requirements: PlaybackRequirements,
            val result: PlaybackResult<Unit>,
        ) : LaneMessage
        data class BarrierFinished(
            val releaseEpoch: Long,
            val reason: ActiveWorkReleaseReason,
            val failure: PlaybackFailure?,
        ) : LaneMessage
        data class WatchdogExpired(
            val token: Long,
            val generation: Long,
            val attempt: Long,
            val phase: PlaybackPolicy.WatchdogPhase,
        ) : LaneMessage
        data class RuntimeWindowFinished(
            val token: Long,
            val generation: Long,
            val attempt: Long,
            val before: PlaybackEngineMetricsSnapshot?,
            val after: PlaybackEngineMetricsSnapshot?,
        ) : LaneMessage
    }

    private companion object {
        const val DEFAULT_RELEASE_TIMEOUT_MS = 5_000L
        const val SURFACE_RECHECK_MS = 500L
        val NONFATAL_OUTPUT_STATUSES = setOf(
            PlaybackOutputStatus.UNSUPPORTED,
            PlaybackOutputStatus.NO_COMPATIBLE_MODE,
            PlaybackOutputStatus.APPLY_NOT_CONFIRMED,
            PlaybackOutputStatus.APPLY_FAILED,
        )
    }
}
