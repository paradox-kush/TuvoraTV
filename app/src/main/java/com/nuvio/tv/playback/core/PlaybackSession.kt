package com.nuvio.tv.playback.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    suspend fun release() {
        dispatch(PlaybackCommand.Release)
        snapshot.filter { it.state == PlaybackState.STOPPED || it.state == PlaybackState.FAILED }.first()
        sessionJob.cancelAndJoin()
        lane.close()
    }

    private suspend fun process(message: LaneMessage) {
        when (message) {
            is LaneMessage.Reducer -> applyReducer(message.input)
            is LaneMessage.ResolutionFinished -> resolutionFinished(message)
            is LaneMessage.SelectionFinished -> selectionFinished(message)
            is LaneMessage.RequirementsChangeFinished -> requirementsChangeFinished(message)
            is LaneMessage.RequirementsApplied -> requirementsApplied(message)
            is LaneMessage.BarrierFinished -> barrierFinished(message)
        }
    }

    private suspend fun applyReducer(input: PlaybackReducerInput) {
        if (input is PlaybackReducerInput.Command && input.value is PlaybackCommand.PreferencesChanged) {
            preferences = input.value.preferences
        }
        val transition = PlaybackStateMachine.reduce(machine, input)
        machine = transition.state
        _snapshot.value = transition.state.snapshot
        _surfaceAvailable.value = transition.state.surfaceAvailable
        transition.actions.forEach { execute(it) }
    }

    private suspend fun execute(action: PlaybackAction) {
        when (action) {
            is PlaybackAction.ResolveRequest -> resolve(action)
            is PlaybackAction.SelectPrimaryGraph -> select(action, failedGraph = null)
            is PlaybackAction.SelectHandoffGraph -> select(action, failedGraph = action.failedGraph)
            is PlaybackAction.AttachSurface -> attachSurface(action)
            is PlaybackAction.StartGraph -> startGraph(action)
            is PlaybackAction.SetPaused -> setPaused(action)
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
            val result = safeResult(FailurePhase.REQUEST_RESOLUTION) {
                requestResolver.resolve(action.request)
            }
            lane.send(LaneMessage.ResolutionFinished(action.generation, result))
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
                        ),
                    ),
                )
            }
            is PlaybackResult.Failure -> fail(message.generation, result.failure)
        }
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
        phase: FailurePhase,
    ): PlaybackResult<PlaybackRequirements> {
        val environment = safeResult(phase) {
            environmentProvider.snapshot(summary, evidence, profile, preferenceSnapshot)
        }
        if (environment is PlaybackResult.Failure) return environment
        environment as PlaybackResult.Success
        return safeResult(phase) {
            requirementsResolver.resolve(
                PlaybackRequirementsInput(
                    requestSummary = summary,
                    evidence = evidence,
                    profile = profile,
                    effectivePreferences = preferenceSnapshot,
                    environment = environment.value,
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
            lane.send(
                LaneMessage.Reducer(
                    PlaybackReducerInput.Event(PlaybackEvent.EngineStarting(action.generation)),
                ),
            )
            val output = safeResult(FailurePhase.ENGINE_START) {
                outputController.apply(action.generation, effective)
            }
            if (output is PlaybackResult.Failure) {
                fail(action.generation, output.failure)
                return@launch
            }
            val result = safeResult(FailurePhase.ENGINE_START) {
                engine.start(
                    PlaybackEngineStart(
                        generation = action.generation,
                        request = resolvedRequest.request,
                        evidence = resolvedRequest.evidence,
                        graph = action.graph,
                        requirements = effective,
                        startPaused = action.startPaused,
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
            val output = safeResult(FailurePhase.PLAYBACK) {
                outputController.apply(action.generation, action.requirements)
            }
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

    private fun releaseActiveWork(action: PlaybackAction.ReleaseActiveWork) {
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
            val outputResult = safeResult(FailurePhase.RELEASE) {
                outputController.reset(action.generation)
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
        val original = machine.request ?: return
        val cachedResolved = resolved.takeIf { it?.generation == action.generation }?.value
        val effective = requirements.takeIf { it?.generation == action.generation }?.value ?: return
        val startPaused = machine.paused
        generationScope(action.generation).launch {
            val release = releaseAdapterUntilComplete(action.generation, engine)
            if (release is PlaybackResult.Failure) {
                recoveryFailed(action.generation, release.failure)
                return@launch
            }
            val resolvedRequest = if (action.freshRequestRequired) {
                when (val result = safeResult(FailurePhase.RECOVERY) { requestResolver.resolve(original) }) {
                    is PlaybackResult.Success -> result.value
                    is PlaybackResult.Failure -> {
                        recoveryFailed(action.generation, result.failure)
                        return@launch
                    }
                }
            } else {
                cachedResolved ?: return@launch
            }
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
        val original = machine.request ?: return
        val profileSnapshot = machine.snapshot.profile
        val preferenceSnapshot = preferences
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
                val resolvedRequest = when (val result = safeResult(FailurePhase.RECOVERY) {
                    requestResolver.resolve(original)
                }) {
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
                val output = safeResult(FailurePhase.RECOVERY) {
                    outputController.apply(action.generation, effective)
                }
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
        intentionalReleases[engine.type] = setOfNotNull(generation, engineGeneration)
        val graceful = withTimeoutOrNull(releaseTimeoutMs) {
            safeResult(FailurePhase.RELEASE) { engine.release(generation) }
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
            safeResult(FailurePhase.RELEASE) { engine.hardAbort(generation) }
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

    private sealed interface LaneMessage {
        data class Reducer(val input: PlaybackReducerInput) : LaneMessage
        data class ResolutionFinished(
            val generation: Long,
            val result: PlaybackResult<ResolvedPlaybackRequest>,
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
    }

    private companion object {
        const val DEFAULT_RELEASE_TIMEOUT_MS = 5_000L
        const val SURFACE_RECHECK_MS = 500L
    }
}
