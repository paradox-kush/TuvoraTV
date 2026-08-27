package com.nuvio.tv.playback.core

/**
 * Pure state for [PlaybackStateMachine]. The session actor owns an instance and executes the
 * returned [PlaybackAction] values on its single serialized command lane.
 *
 * [request] is intentionally the secret-safe [PlaybackRequest] class rather than a data class;
 * diagnostic string conversion therefore remains redacted.
 */
data class PlaybackMachineState(
    val snapshot: PlaybackSnapshot = PlaybackSnapshot(),
    val request: PlaybackRequest? = null,
    val evidence: StreamEvidence? = null,
    val sessionActive: Boolean = true,
    val paused: Boolean = false,
    val surfaceAvailable: Boolean = false,
    val incident: PlaybackIncident? = null,
    val nextIncidentSequence: Long = 1,
    val afterRelease: AfterRelease = AfterRelease.NONE,
    val graphBeforeRelease: PlaybackGraph? = null,
    val activeReleaseEpoch: Long? = null,
    val nextReleaseEpoch: Long = 1,
    val lifecycleActive: Boolean = true,
    val lifecycleResume: LifecycleResume = LifecycleResume.NONE,
    val lifecycleResumeGraph: PlaybackGraph? = null,
)

/** A fault remains one incident until useful playback resumes. */
data class PlaybackIncident(
    val sequence: Long,
    val recoveryIssued: Boolean = false,
    val handoffIssued: Boolean = false,
)

enum class AfterRelease {
    NONE,
    STOP,
    START_NEW_REQUEST,
    REBUILD_CURRENT_GRAPH,
    RESELECT_GRAPH,
    HANDOFF,
    FAIL,
    SUSPEND,
}

enum class LifecycleResume {
    NONE,
    RESOLVE_REQUEST,
    SELECT_PRIMARY_GRAPH,
    REBUILD_CURRENT_GRAPH,
    SELECT_HANDOFF_GRAPH,
}

enum class ActiveWorkReleaseReason {
    STOP,
    REPLACE_REQUEST,
    REBUILD,
    RESELECT,
    HANDOFF,
    COMPLETED,
    FAILURE,
    SURFACE_LOST,
    LIFECYCLE_INACTIVE,
}

enum class TerminalAvailabilityAuthority { SOURCE, SESSION_POLICY }

/** Side effects are descriptions only. Engines and coroutines are never invoked by the reducer. */
sealed interface PlaybackAction {
    val generation: Long

    data class ResolveRequest(
        override val generation: Long,
        val request: PlaybackRequest,
    ) : PlaybackAction

    data class SelectPrimaryGraph(
        override val generation: Long,
        val summary: RequestSummary,
        val evidence: StreamEvidence,
        val profile: SessionProfile,
    ) : PlaybackAction

    data class SelectHandoffGraph(
        override val generation: Long,
        val failedGraph: PlaybackGraph,
        val failure: PlaybackFailure,
    ) : PlaybackAction

    data class AttachSurface(
        override val generation: Long,
        val graph: PlaybackGraph,
    ) : PlaybackAction

    data class StartGraph(
        override val generation: Long,
        val graph: PlaybackGraph,
        val startPaused: Boolean,
    ) : PlaybackAction

    data class SetPaused(
        override val generation: Long,
        val paused: Boolean,
    ) : PlaybackAction

    data class ResolvePreferencesChange(
        override val generation: Long,
        val preferences: PlaybackPreferences,
    ) : PlaybackAction

    data class ResolveProfileChange(
        override val generation: Long,
        val previousProfile: SessionProfile,
        val profile: SessionProfile,
    ) : PlaybackAction

    data class ApplyRequirementsInPlace(
        override val generation: Long,
        val changeId: Long,
        val requirements: PlaybackRequirements,
    ) : PlaybackAction

    /**
     * Cancels every active job, including work from superseded generations (resolution, selection,
     * recovery, reconnect), and releases the graph when one exists. [generation] identifies the
     * request waiting behind the barrier; it is not a cancellation filter. The session emits
     * [PlaybackReducerInput.BarrierCompleted] only after all active work has stopped.
     */
    data class ReleaseActiveWork(
        override val generation: Long,
        val releaseEpoch: Long,
        val reason: ActiveWorkReleaseReason,
    ) : PlaybackAction

    data class RecoverInPlace(
        override val generation: Long,
        val failure: PlaybackFailure,
        val freshRequestRequired: Boolean,
    ) : PlaybackAction

    /** One session-owned, indefinite reconnect loop; attempts never feed reducer failures back. */
    data class StartLiveReconnectLoop(override val generation: Long) : PlaybackAction
}

sealed interface PlaybackReducerInput {
    data class Command(val value: PlaybackCommand) : PlaybackReducerInput
    data class Event(val value: PlaybackEvent) : PlaybackReducerInput

    data class PreviewAvailabilityChanged(
        val generation: Long,
        val availability: PreviewAvailability,
    ) : PlaybackReducerInput

    /**
     * Terminal stream state is deliberately a separate, strongly evidenced input. A preview or
     * ordinary engine failure cannot manufacture it.
     */
    data class StreamAvailabilityChanged(
        val generation: Long,
        val availability: StreamAvailability,
        val authority: TerminalAvailabilityAuthority,
    ) : PlaybackReducerInput

    /** Result of the single session-owned VOD/catch-up recovery attempt. */
    data class RecoveryAttemptFailed(
        val generation: Long,
        val failure: PlaybackFailure,
    ) : PlaybackReducerInput

    /** Deterministic/fatal result that ends an indefinite live reconnect loop. */
    data class LiveReconnectEscalated(
        val generation: Long,
        val failure: PlaybackFailure,
    ) : PlaybackReducerInput

    /** Session-owned result after resolving and comparing effective requirements. */
    data class RequirementsChangeResolved(
        val changeId: Long,
        val generation: Long,
        val previousProfile: SessionProfile?,
        val targetProfile: SessionProfile?,
        val requirements: PlaybackRequirements,
        val impact: ChangeImpact,
    ) : PlaybackReducerInput

    data class RequirementsChangeRejected(
        val generation: Long,
        val previousProfile: SessionProfile?,
    ) : PlaybackReducerInput

    data class LifecycleChanged(val active: Boolean) : PlaybackReducerInput

    /** Completion of the generation-wide release barrier, keyed independently from generation. */
    data class BarrierCompleted(val releaseEpoch: Long) : PlaybackReducerInput

    /** The adapter could not affirm that provider and surface ownership ended. */
    data class BarrierFailed(
        val releaseEpoch: Long,
        val failure: PlaybackFailure,
    ) : PlaybackReducerInput
}

data class PlaybackTransition(
    val state: PlaybackMachineState,
    val actions: List<PlaybackAction> = emptyList(),
)

/** Deterministic playback reducer. It has no clocks, jobs, platform types, or engine imports. */
object PlaybackStateMachine {
    fun reduce(
        state: PlaybackMachineState,
        command: PlaybackCommand,
    ): PlaybackTransition = reduce(state, PlaybackReducerInput.Command(command))

    fun reduce(
        state: PlaybackMachineState,
        event: PlaybackEvent,
    ): PlaybackTransition = reduce(state, PlaybackReducerInput.Event(event))

    fun reduce(
        state: PlaybackMachineState,
        input: PlaybackReducerInput,
    ): PlaybackTransition = when (input) {
        is PlaybackReducerInput.Command -> reduceCommand(state, input.value)
        is PlaybackReducerInput.Event -> reduceEvent(state, input.value)
        is PlaybackReducerInput.PreviewAvailabilityChanged -> {
            if (input.generation != state.snapshot.generation) unchanged(state)
            else transition(state.copy(snapshot = state.snapshot.copy(previewAvailability = input.availability)))
        }
        is PlaybackReducerInput.StreamAvailabilityChanged -> {
            if (input.generation != state.snapshot.generation) unchanged(state)
            else streamAvailabilityChanged(state, input)
        }
        is PlaybackReducerInput.RecoveryAttemptFailed -> {
            if (
                input.generation != state.snapshot.generation ||
                state.snapshot.state != PlaybackState.RECOVERING_IN_PLACE
            ) {
                unchanged(state)
            } else {
                terminalFailure(
                    state.copy(snapshot = state.snapshot.copy(failure = input.failure)),
                )
            }
        }
        is PlaybackReducerInput.LiveReconnectEscalated -> {
            if (
                input.generation != state.snapshot.generation ||
                state.snapshot.state != PlaybackState.LIVE_RECONNECTING
            ) {
                unchanged(state)
            } else {
                failed(
                    state.copy(
                        snapshot = state.snapshot.copy(
                            state = PlaybackState.DEGRADED,
                            isReconnecting = false,
                        ),
                    ),
                    input.failure,
                )
            }
        }
        is PlaybackReducerInput.RequirementsChangeResolved -> {
            if (input.generation != state.snapshot.generation) unchanged(state)
            else requirementsChangeResolved(state, input)
        }
        is PlaybackReducerInput.RequirementsChangeRejected -> {
            if (input.generation != state.snapshot.generation || input.previousProfile == null) {
                unchanged(state)
            } else {
                transition(state.copy(snapshot = state.snapshot.copy(profile = input.previousProfile)))
            }
        }
        is PlaybackReducerInput.LifecycleChanged -> lifecycleChanged(state, input.active)
        is PlaybackReducerInput.BarrierCompleted -> barrierCompleted(state, input.releaseEpoch)
        is PlaybackReducerInput.BarrierFailed -> barrierFailed(state, input.releaseEpoch, input.failure)
    }

    private fun reduceCommand(
        state: PlaybackMachineState,
        command: PlaybackCommand,
    ): PlaybackTransition = when (command) {
        is PlaybackCommand.Tune -> startRequest(state, command.request, command.profile)
        is PlaybackCommand.Zap -> startRequest(state, command.request, command.profile)
        PlaybackCommand.Pause -> pause(state)
        PlaybackCommand.Resume -> resume(state)
        PlaybackCommand.Retry -> retry(state)
        is PlaybackCommand.PreferencesChanged -> requestPreferencesChange(state, command)
        is PlaybackCommand.SessionProfileChanged -> requestProfileChange(state, command)
        PlaybackCommand.SurfaceAvailable -> surfaceAvailable(state)
        PlaybackCommand.SurfaceUnavailable -> surfaceUnavailable(state)
        PlaybackCommand.Stop,
        PlaybackCommand.Release,
        -> release(state)
    }

    private fun startRequest(
        state: PlaybackMachineState,
        request: PlaybackRequest,
        profile: SessionProfile,
    ): PlaybackTransition {
        val generation = nextGeneration(state.snapshot.generation)
        val base = state.copy(
            snapshot = PlaybackSnapshot(
                generation = generation,
                state = PlaybackState.RESOLVING,
                profile = profile,
                statusCode = PlaybackStatusCode.RESOLVING,
            ),
            request = request,
            evidence = null,
            sessionActive = true,
            paused = false,
            incident = null,
            afterRelease = AfterRelease.NONE,
            lifecycleResume = LifecycleResume.NONE,
            lifecycleResumeGraph = null,
        )
        if (!state.lifecycleActive) {
            val suspended = base.copy(
                snapshot = base.snapshot.copy(
                    state = if (state.activeReleaseEpoch != null || hasActiveWork(state)) {
                        PlaybackState.RELEASING
                    } else {
                        PlaybackState.STOPPED
                    },
                ),
                lifecycleResume = LifecycleResume.RESOLVE_REQUEST,
                afterRelease = if (state.activeReleaseEpoch != null) AfterRelease.SUSPEND else AfterRelease.NONE,
            )
            return when {
                state.activeReleaseEpoch != null -> transition(
                    suspended.copy(
                        activeReleaseEpoch = state.activeReleaseEpoch,
                        graphBeforeRelease = state.graphBeforeRelease,
                    ),
                )
                hasActiveWork(state) -> beginBarrier(
                    suspended,
                    AfterRelease.SUSPEND,
                    ActiveWorkReleaseReason.REPLACE_REQUEST,
                )
                else -> transition(suspended)
            }
        }
        return if (state.activeReleaseEpoch != null) {
            // The barrier belongs to an earlier generation, but its epoch remains authoritative.
            // Coalesce to the newest request without reissuing release or waiting on a completion
            // carrying the now-superseded request generation.
            transition(
                base.copy(
                    snapshot = base.snapshot.copy(state = PlaybackState.RELEASING),
                    afterRelease = AfterRelease.START_NEW_REQUEST,
                    activeReleaseEpoch = state.activeReleaseEpoch,
                    graphBeforeRelease = state.graphBeforeRelease,
                ),
            )
        } else if (hasActiveWork(state)) {
            beginBarrier(
                base,
                AfterRelease.START_NEW_REQUEST,
                ActiveWorkReleaseReason.REPLACE_REQUEST,
            )
        } else {
            transition(base, PlaybackAction.ResolveRequest(generation, request))
        }
    }

    private fun pause(state: PlaybackMachineState): PlaybackTransition {
        if (!state.sessionActive || state.paused || !state.snapshot.state.isPlaybackActive()) {
            return unchanged(state)
        }
        return transition(
            state.copy(paused = true, snapshot = state.snapshot.copy(isPlaying = false)),
            PlaybackAction.SetPaused(state.snapshot.generation, paused = true),
        )
    }

    private fun resume(state: PlaybackMachineState): PlaybackTransition {
        if (!state.sessionActive || !state.paused || !state.snapshot.state.isPlaybackActive()) {
            return unchanged(state)
        }
        val resumed = state.copy(paused = false)
        if (state.snapshot.state == PlaybackState.DEGRADED && state.request?.contentType == ContentType.LIVE) {
            val (withIncident, incident) = resumed.ensureIncident()
            if (!incident.recoveryIssued) {
                return transition(
                    withIncident.copy(
                        snapshot = withIncident.snapshot.copy(
                            state = PlaybackState.LIVE_RECONNECTING,
                            isReconnecting = true,
                            statusCode = PlaybackStatusCode.RECONNECTING,
                        ),
                        incident = incident.copy(recoveryIssued = true),
                    ),
                    PlaybackAction.StartLiveReconnectLoop(state.snapshot.generation),
                )
            }
        }
        return transition(resumed, PlaybackAction.SetPaused(state.snapshot.generation, paused = false))
    }

    private fun lifecycleChanged(
        state: PlaybackMachineState,
        active: Boolean,
    ): PlaybackTransition {
        if (active == state.lifecycleActive) return unchanged(state)
        return if (active) lifecycleActive(state) else lifecycleInactive(state)
    }

    private fun lifecycleInactive(state: PlaybackMachineState): PlaybackTransition {
        val inactive = state.copy(
            lifecycleActive = false,
            paused = true,
            snapshot = state.snapshot.copy(isPlaying = false),
        )
        if (state.activeReleaseEpoch != null) {
            if (state.afterRelease in setOf(AfterRelease.STOP, AfterRelease.FAIL)) {
                return transition(inactive)
            }
            val resume = lifecycleResumeForAfterRelease(state.afterRelease)
            return transition(
                inactive.copy(
                    afterRelease = AfterRelease.SUSPEND,
                    lifecycleResume = resume,
                    lifecycleResumeGraph = state.graphBeforeRelease ?: state.snapshot.graph,
                ),
            )
        }
        val resume = lifecycleResumeForState(state)
        if (resume == LifecycleResume.NONE || !hasActiveWork(state)) return transition(inactive)
        return beginBarrier(
            inactive.copy(
                lifecycleResume = resume,
                lifecycleResumeGraph = state.snapshot.graph,
            ),
            AfterRelease.SUSPEND,
            ActiveWorkReleaseReason.LIFECYCLE_INACTIVE,
        )
    }

    private fun lifecycleActive(state: PlaybackMachineState): PlaybackTransition {
        val active = state.copy(lifecycleActive = true, paused = false)
        if (active.activeReleaseEpoch != null) return transition(active)
        return resumeLifecycle(active)
    }

    private fun resumeLifecycle(state: PlaybackMachineState): PlaybackTransition {
        val resume = state.lifecycleResume
        if (resume == LifecycleResume.NONE) return transition(state)
        val resumed = state.copy(
            lifecycleResume = LifecycleResume.NONE,
            lifecycleResumeGraph = null,
        )
        return when (resume) {
            LifecycleResume.NONE -> transition(resumed)
            LifecycleResume.RESOLVE_REQUEST -> {
                val request = resumed.request ?: return failedAfterBarrier(resumed)
                transition(
                    resumed.copy(
                        snapshot = resumed.snapshot.copy(
                            state = PlaybackState.RESOLVING,
                            statusCode = PlaybackStatusCode.RESOLVING,
                        ),
                    ),
                    PlaybackAction.ResolveRequest(resumed.snapshot.generation, request),
                )
            }
            LifecycleResume.SELECT_PRIMARY_GRAPH -> selectAgain(resumed)
            LifecycleResume.REBUILD_CURRENT_GRAPH -> {
                val graph = state.lifecycleResumeGraph ?: return selectAgain(resumed)
                val rebuilding = resumed.copy(
                    snapshot = resumed.snapshot.copy(
                        state = PlaybackState.ATTACHING_SURFACE,
                        graph = graph,
                        statusCode = PlaybackStatusCode.STARTING,
                    ),
                )
                if (rebuilding.surfaceAvailable) {
                    transition(
                        rebuilding,
                        PlaybackAction.AttachSurface(rebuilding.snapshot.generation, graph),
                    )
                } else {
                    transition(rebuilding)
                }
            }
            LifecycleResume.SELECT_HANDOFF_GRAPH -> {
                val graph = state.lifecycleResumeGraph ?: return failedAfterBarrier(resumed)
                val failure = state.snapshot.failure ?: return failedAfterBarrier(resumed)
                transition(
                    resumed.copy(snapshot = resumed.snapshot.copy(state = PlaybackState.SELECTING_GRAPH)),
                    PlaybackAction.SelectHandoffGraph(resumed.snapshot.generation, graph, failure),
                )
            }
        }
    }

    private fun lifecycleResumeForState(state: PlaybackMachineState): LifecycleResume = when {
        state.snapshot.state == PlaybackState.RESOLVING -> LifecycleResume.RESOLVE_REQUEST
        state.snapshot.state == PlaybackState.SELECTING_GRAPH -> LifecycleResume.SELECT_PRIMARY_GRAPH
        state.snapshot.graph != null && state.snapshot.state.isPlaybackActive() ->
            LifecycleResume.REBUILD_CURRENT_GRAPH
        else -> LifecycleResume.NONE
    }

    private fun lifecycleResumeForAfterRelease(afterRelease: AfterRelease): LifecycleResume = when (afterRelease) {
        AfterRelease.START_NEW_REQUEST -> LifecycleResume.RESOLVE_REQUEST
        AfterRelease.REBUILD_CURRENT_GRAPH -> LifecycleResume.REBUILD_CURRENT_GRAPH
        AfterRelease.RESELECT_GRAPH -> LifecycleResume.SELECT_PRIMARY_GRAPH
        AfterRelease.HANDOFF -> LifecycleResume.SELECT_HANDOFF_GRAPH
        AfterRelease.NONE,
        AfterRelease.STOP,
        AfterRelease.FAIL,
        AfterRelease.SUSPEND,
        -> LifecycleResume.NONE
    }

    private fun retry(state: PlaybackMachineState): PlaybackTransition {
        val request = state.request ?: return unchanged(state)
        if (state.snapshot.state != PlaybackState.FAILED && state.snapshot.state != PlaybackState.STOPPED) {
            return unchanged(state)
        }
        return startRequest(state, request, state.snapshot.profile)
    }

    private fun requestPreferencesChange(
        state: PlaybackMachineState,
        command: PlaybackCommand.PreferencesChanged,
    ): PlaybackTransition {
        if (state.evidence == null || state.request == null) return unchanged(state)
        return transition(
            state,
            PlaybackAction.ResolvePreferencesChange(state.snapshot.generation, command.preferences),
        )
    }

    private fun requestProfileChange(
        state: PlaybackMachineState,
        command: PlaybackCommand.SessionProfileChanged,
    ): PlaybackTransition {
        if (command.profile == state.snapshot.profile) return unchanged(state)
        val previousProfile = state.snapshot.profile
        val profiled = state.copy(snapshot = state.snapshot.copy(profile = command.profile))
        if (state.evidence == null || state.request == null) {
            return transition(profiled)
        }
        return transition(
            profiled,
            PlaybackAction.ResolveProfileChange(
                state.snapshot.generation,
                previousProfile,
                command.profile,
            ),
        )
    }

    private fun requirementsChangeResolved(
        state: PlaybackMachineState,
        change: PlaybackReducerInput.RequirementsChangeResolved,
    ): PlaybackTransition {
        val targetProfile = change.targetProfile ?: state.snapshot.profile
        val failedGuidePromotion =
            change.previousProfile == SessionProfile.GUIDE &&
                targetProfile == SessionProfile.FULLSCREEN &&
                change.impact == ChangeImpact.RESELECT_GRAPH &&
                state.snapshot.streamAvailability !is StreamAvailability.TerminallyUnavailable
        val profiled = state
        return when (change.impact) {
            ChangeImpact.APPLY_IN_PLACE -> {
                if (profiled.snapshot.graph == null) transition(profiled)
                else transition(
                    profiled,
                    PlaybackAction.ApplyRequirementsInPlace(
                        profiled.snapshot.generation,
                        change.changeId,
                        change.requirements,
                    ),
                )
            }
            ChangeImpact.REBUILD_CURRENT_GRAPH -> beginGraphReplacement(
                profiled,
                AfterRelease.REBUILD_CURRENT_GRAPH,
                ActiveWorkReleaseReason.REBUILD,
            )
            ChangeImpact.RESELECT_GRAPH -> when {
                failedGuidePromotion && profiled.activeReleaseEpoch != null -> transition(
                    profiled.copy(afterRelease = AfterRelease.RESELECT_GRAPH),
                )
                failedGuidePromotion && profiled.snapshot.state == PlaybackState.FAILED -> selectAgain(profiled)
                else -> beginGraphReplacement(
                    profiled,
                    AfterRelease.RESELECT_GRAPH,
                    ActiveWorkReleaseReason.RESELECT,
                )
            }
            ChangeImpact.NEXT_SESSION_ONLY -> unchanged(state)
        }
    }

    private fun beginGraphReplacement(
        state: PlaybackMachineState,
        afterRelease: AfterRelease,
        reason: ActiveWorkReleaseReason,
    ): PlaybackTransition {
        if (state.snapshot.graph == null || state.snapshot.state == PlaybackState.RELEASING) {
            return unchanged(state)
        }
        return beginBarrier(state, afterRelease, reason)
    }

    private fun surfaceAvailable(state: PlaybackMachineState): PlaybackTransition {
        if (state.surfaceAvailable) return unchanged(state)
        val updated = state.copy(surfaceAvailable = true)
        val graph = updated.snapshot.graph
        return if (updated.snapshot.state == PlaybackState.ATTACHING_SURFACE && graph != null) {
            transition(updated, PlaybackAction.AttachSurface(updated.snapshot.generation, graph))
        } else {
            transition(updated)
        }
    }

    private fun surfaceUnavailable(state: PlaybackMachineState): PlaybackTransition {
        if (!state.surfaceAvailable) return unchanged(state)
        val withoutSurface = state.copy(surfaceAvailable = false)
        if (state.activeReleaseEpoch != null) return transition(withoutSurface)
        return if (state.snapshot.graph != null && state.snapshot.state.isPlaybackActive()) {
            // This also cancels an indefinite live reconnect loop. Merely detaching the Surface
            // would leave provider/network work running with nowhere to render.
            beginBarrier(
                withoutSurface,
                AfterRelease.REBUILD_CURRENT_GRAPH,
                ActiveWorkReleaseReason.SURFACE_LOST,
            )
        } else {
            transition(withoutSurface)
        }
    }

    private fun release(state: PlaybackMachineState): PlaybackTransition {
        if (state.activeReleaseEpoch != null) {
            return transition(
                state.copy(
                    sessionActive = false,
                    paused = false,
                    afterRelease = AfterRelease.STOP,
                    lifecycleResume = LifecycleResume.NONE,
                    lifecycleResumeGraph = null,
                ),
            )
        }
        if (state.snapshot.state == PlaybackState.STOPPED) {
            return if (state.lifecycleResume != LifecycleResume.NONE || state.sessionActive) {
                transition(
                    state.copy(
                        sessionActive = false,
                        paused = false,
                        lifecycleResume = LifecycleResume.NONE,
                        lifecycleResumeGraph = null,
                    ),
                )
            } else {
                unchanged(state)
            }
        }
        if (state.snapshot.state == PlaybackState.IDLE) {
            return transition(
                state.copy(
                    sessionActive = false,
                    lifecycleResume = LifecycleResume.NONE,
                    lifecycleResumeGraph = null,
                    snapshot = state.snapshot.copy(
                        state = PlaybackState.STOPPED,
                        statusCode = PlaybackStatusCode.STOPPED,
                    ),
                ),
            )
        }
        return beginBarrier(
            state.copy(
                sessionActive = false,
                paused = false,
                lifecycleResume = LifecycleResume.NONE,
                lifecycleResumeGraph = null,
            ),
            AfterRelease.STOP,
            ActiveWorkReleaseReason.STOP,
        )
    }

    private fun reduceEvent(
        state: PlaybackMachineState,
        event: PlaybackEvent,
    ): PlaybackTransition {
        if (event.generation != state.snapshot.generation) return unchanged(state)
        return when (event) {
            is PlaybackEvent.RequestResolved -> requestResolved(state, event)
            is PlaybackEvent.GraphSelected -> graphSelected(state, event)
            is PlaybackEvent.SurfaceAttached -> surfaceAttached(state)
            is PlaybackEvent.EngineStarting -> engineStarting(state)
            is PlaybackEvent.BytesReceived -> progress(state) { it.copy(receivedBytes = true) }
            is PlaybackEvent.TracksAvailable -> tracksAvailable(state, event)
            is PlaybackEvent.FirstAudio -> firstAudio(state)
            is PlaybackEvent.FirstVideoFrame -> firstVideoFrame(state)
            is PlaybackEvent.BufferingStarted -> bufferingStarted(state)
            is PlaybackEvent.BufferingEnded -> bufferingEnded(state)
            is PlaybackEvent.EngineStateObserved,
            is PlaybackEvent.VideoDecoderInitialized,
            is PlaybackEvent.VideoInputFormatChanged,
            is PlaybackEvent.VideoSizeChanged,
            -> unchanged(state)
            is PlaybackEvent.PlaybackEnded -> playbackEnded(state, event.reason)
            is PlaybackEvent.Failed -> failed(state, event.failure)
            // Engine release alone cannot satisfy the barrier: resolution/recovery/reconnect jobs
            // may still be active. Only BarrierCompleted is authoritative.
            is PlaybackEvent.EngineReleased -> unchanged(state)
        }
    }

    private fun requestResolved(
        state: PlaybackMachineState,
        event: PlaybackEvent.RequestResolved,
    ): PlaybackTransition {
        if (state.snapshot.state != PlaybackState.RESOLVING) return unchanged(state)
        val updated = state.copy(
            evidence = event.evidence,
            snapshot = state.snapshot.copy(
                state = PlaybackState.SELECTING_GRAPH,
                requestSummary = event.summary,
            ),
        )
        return transition(
            updated,
            PlaybackAction.SelectPrimaryGraph(
                generation = event.generation,
                summary = event.summary,
                evidence = event.evidence,
                profile = updated.snapshot.profile,
            ),
        )
    }

    private fun graphSelected(
        state: PlaybackMachineState,
        event: PlaybackEvent.GraphSelected,
    ): PlaybackTransition {
        if (state.snapshot.state != PlaybackState.SELECTING_GRAPH) return unchanged(state)
        val updated = state.copy(
            snapshot = state.snapshot.copy(
                state = PlaybackState.ATTACHING_SURFACE,
                graph = event.graph,
                statusCode = PlaybackStatusCode.STARTING,
            ),
        )
        return if (updated.surfaceAvailable) {
            transition(updated, PlaybackAction.AttachSurface(event.generation, event.graph))
        } else {
            transition(updated)
        }
    }

    private fun surfaceAttached(state: PlaybackMachineState): PlaybackTransition {
        val graph = state.snapshot.graph ?: return unchanged(state)
        if (state.snapshot.state != PlaybackState.ATTACHING_SURFACE) return unchanged(state)
        return transition(
            state.copy(snapshot = state.snapshot.copy(state = PlaybackState.STARTING_PRIMARY)),
            PlaybackAction.StartGraph(state.snapshot.generation, graph, startPaused = state.paused),
        )
    }

    private fun engineStarting(state: PlaybackMachineState): PlaybackTransition {
        if (state.snapshot.state !in setOf(
                PlaybackState.STARTING_PRIMARY,
                PlaybackState.RECOVERING_IN_PLACE,
                PlaybackState.LIVE_RECONNECTING,
            )
        ) {
            return unchanged(state)
        }
        return transition(
            state.copy(
                snapshot = state.snapshot.copy(
                    state = PlaybackState.STARTING_PRIMARY,
                    isReconnecting = state.snapshot.isReconnecting,
                    statusCode = PlaybackStatusCode.STARTING,
                ),
            ),
        )
    }

    private inline fun progress(
        state: PlaybackMachineState,
        update: (PlaybackProgressEvidence) -> PlaybackProgressEvidence,
    ): PlaybackTransition {
        if (!state.snapshot.state.acceptsProgress()) return unchanged(state)
        return transition(
            state.copy(
                snapshot = state.snapshot.copy(
                    progress = update(state.snapshot.progress),
                    streamAvailability = StreamAvailability.Available,
                ),
            ),
        )
    }

    private fun tracksAvailable(
        state: PlaybackMachineState,
        event: PlaybackEvent.TracksAvailable,
    ): PlaybackTransition {
        if (!state.snapshot.state.acceptsProgress()) return unchanged(state)
        val updated = state.copy(
            snapshot = state.snapshot.copy(
                tracks = TrackSummary(event.audioTrackCount, event.subtitleTrackCount, event.hasVideo),
                progress = state.snapshot.progress.copy(discoveredTracks = true),
                streamAvailability = StreamAvailability.Available,
            ),
        )
        return if (!event.hasVideo && updated.snapshot.progress.renderedAudio) {
            firstAudio(updated)
        } else {
            transition(updated)
        }
    }

    private fun firstAudio(state: PlaybackMachineState): PlaybackTransition {
        if (!state.snapshot.state.acceptsProgress()) return unchanged(state)
        val audioOnlyConfirmed = state.snapshot.progress.discoveredTracks &&
            !state.snapshot.tracks.hasVideoTrack
        if (!audioOnlyConfirmed) {
            return transition(
                state.copy(
                    snapshot = state.snapshot.copy(
                        progress = state.snapshot.progress.copy(renderedAudio = true),
                        streamAvailability = StreamAvailability.Available,
                    ),
                ),
            )
        }
        return transition(
            state.copy(
                snapshot = state.snapshot.copy(
                    state = PlaybackState.PLAYING,
                    isPlaying = !state.paused,
                    isBuffering = false,
                    isReconnecting = false,
                    progress = state.snapshot.progress.copy(renderedAudio = true),
                    failure = null,
                    statusCode = null,
                    streamAvailability = StreamAvailability.Available,
                ),
                incident = null,
            ),
        )
    }

    private fun firstVideoFrame(state: PlaybackMachineState): PlaybackTransition {
        if (!state.snapshot.state.acceptsProgress()) return unchanged(state)
        return transition(
            state.copy(
                snapshot = state.snapshot.copy(
                    state = PlaybackState.PLAYING,
                    isPlaying = !state.paused,
                    isBuffering = false,
                    isReconnecting = false,
                    progress = state.snapshot.progress.copy(renderedVideoFrame = true),
                    failure = null,
                    statusCode = null,
                    previewAvailability = if (state.snapshot.profile == SessionProfile.GUIDE) {
                        PreviewAvailability.Available
                    } else {
                        state.snapshot.previewAvailability
                    },
                    streamAvailability = StreamAvailability.Available,
                ),
                incident = null,
            ),
        )
    }

    private fun bufferingStarted(state: PlaybackMachineState): PlaybackTransition {
        if (state.snapshot.state != PlaybackState.PLAYING) return unchanged(state)
        return transition(
            state.copy(
                snapshot = state.snapshot.copy(
                    state = PlaybackState.DEGRADED,
                    isPlaying = false,
                    isBuffering = true,
                    statusCode = PlaybackStatusCode.BUFFERING,
                ),
            ),
        )
    }

    private fun bufferingEnded(state: PlaybackMachineState): PlaybackTransition {
        if (state.snapshot.state != PlaybackState.DEGRADED || !state.snapshot.isBuffering) {
            return unchanged(state)
        }
        return transition(
            state.copy(
                snapshot = state.snapshot.copy(
                    state = PlaybackState.PLAYING,
                    isPlaying = !state.paused,
                    isBuffering = false,
                    statusCode = null,
                ),
                incident = null,
            ),
        )
    }

    private fun playbackEnded(
        state: PlaybackMachineState,
        reason: PlaybackEndReason,
    ): PlaybackTransition {
        if (!state.snapshot.state.isPlaybackActive()) return unchanged(state)
        val live = state.request?.contentType == ContentType.LIVE
        if (reason == PlaybackEndReason.EOF && live) {
            val (withIncident, incident) = state.ensureIncident()
            if (!state.sessionActive || state.paused) {
                return transition(
                    withIncident.copy(
                        snapshot = withIncident.snapshot.copy(
                            state = PlaybackState.DEGRADED,
                            isPlaying = false,
                            isBuffering = false,
                        ),
                    ),
                )
            }
            if (incident.recoveryIssued) return unchanged(withIncident)
            return transition(
                withIncident.copy(
                    snapshot = withIncident.snapshot.copy(
                        state = PlaybackState.LIVE_RECONNECTING,
                        isPlaying = false,
                        isBuffering = false,
                        isReconnecting = true,
                        statusCode = PlaybackStatusCode.RECONNECTING,
                    ),
                    incident = incident.copy(recoveryIssued = true),
                ),
                PlaybackAction.StartLiveReconnectLoop(state.snapshot.generation),
            )
        }

        if (reason == PlaybackEndReason.EOF && !live) {
            return beginBarrier(
                state.copy(sessionActive = false),
                AfterRelease.STOP,
                ActiveWorkReleaseReason.COMPLETED,
            )
        }

        // STOPPED and SHUTDOWN are intentional engine terminal events and never reopen. ERROR is
        // deliberately ignored here: adapters must normalize it as Failed with a typed failure.
        return when (reason) {
            PlaybackEndReason.STOPPED,
            PlaybackEndReason.SHUTDOWN,
            -> beginBarrier(
                state.copy(sessionActive = false),
                AfterRelease.STOP,
                ActiveWorkReleaseReason.STOP,
            )
            PlaybackEndReason.ERROR -> unchanged(state)
            PlaybackEndReason.EOF -> unchanged(state)
        }
    }

    private fun failed(
        state: PlaybackMachineState,
        failure: PlaybackFailure,
    ): PlaybackTransition {
        if (!state.snapshot.state.isFailureEligible()) return unchanged(state)
        // Adapters can emit the same terminal callback more than once while a recovery/release is
        // already being executed. It is observation, not a second incident decision.
        if (state.snapshot.state in setOf(
                PlaybackState.RECOVERING_IN_PLACE,
                PlaybackState.HANDING_OFF_ONCE,
                PlaybackState.LIVE_RECONNECTING,
            )
        ) {
            return unchanged(state)
        }
        val preview = previewAfterFailure(state, failure)
        val failedState = state.copy(
            snapshot = state.snapshot.copy(
                isPlaying = false,
                isBuffering = false,
                failure = failure,
                previewAvailability = preview,
            ),
        )
        val (withIncident, incident) = failedState.ensureIncident()
        return when (failure.retryability) {
            Retryability.HANDOFF_ELIGIBLE -> {
                val graph = withIncident.snapshot.graph
                if (graph == null || incident.handoffIssued) {
                    terminalFailure(withIncident)
                } else {
                    beginBarrier(
                        withIncident.copy(incident = incident.copy(handoffIssued = true)),
                        AfterRelease.HANDOFF,
                        ActiveWorkReleaseReason.HANDOFF,
                        barrierState = PlaybackState.HANDING_OFF_ONCE,
                    )
                }
            }
            Retryability.RETRYABLE_IN_PLACE,
            Retryability.RETRYABLE_WITH_FRESH_REQUEST,
            -> {
                if (incident.recoveryIssued) {
                    terminalFailure(withIncident)
                } else if (withIncident.snapshot.state in setOf(
                        PlaybackState.RESOLVING,
                        PlaybackState.SELECTING_GRAPH,
                        PlaybackState.ATTACHING_SURFACE,
                    )
                ) {
                    retryStartupPhase(
                        withIncident.copy(incident = incident.copy(recoveryIssued = true)),
                        failure.retryability == Retryability.RETRYABLE_WITH_FRESH_REQUEST,
                    )
                } else if (withIncident.request?.contentType == ContentType.LIVE) {
                    transition(
                        withIncident.copy(
                            snapshot = withIncident.snapshot.copy(
                                state = PlaybackState.LIVE_RECONNECTING,
                                isReconnecting = true,
                                statusCode = PlaybackStatusCode.RECONNECTING,
                            ),
                            incident = incident.copy(recoveryIssued = true),
                        ),
                        PlaybackAction.StartLiveReconnectLoop(withIncident.snapshot.generation),
                    )
                } else {
                    transition(
                        withIncident.copy(
                            snapshot = withIncident.snapshot.copy(state = PlaybackState.RECOVERING_IN_PLACE),
                            incident = incident.copy(recoveryIssued = true),
                        ),
                        PlaybackAction.RecoverInPlace(
                            withIncident.snapshot.generation,
                            failure,
                            freshRequestRequired = failure.retryability == Retryability.RETRYABLE_WITH_FRESH_REQUEST,
                        ),
                    )
                }
            }
            Retryability.FATAL -> terminalFailure(withIncident)
        }
    }

    private fun retryStartupPhase(
        state: PlaybackMachineState,
        freshRequestRequired: Boolean,
    ): PlaybackTransition {
        if (freshRequestRequired || state.snapshot.state == PlaybackState.RESOLVING) {
            val request = state.request ?: return terminalFailure(state)
            return transition(
                state.copy(
                    snapshot = state.snapshot.copy(
                        state = PlaybackState.RESOLVING,
                        statusCode = PlaybackStatusCode.RESOLVING,
                        failure = null,
                    ),
                ),
                PlaybackAction.ResolveRequest(state.snapshot.generation, request),
            )
        }
        if (state.snapshot.state == PlaybackState.SELECTING_GRAPH) {
            val summary = state.snapshot.requestSummary ?: return terminalFailure(state)
            val evidence = state.evidence ?: return terminalFailure(state)
            return transition(
                state.copy(snapshot = state.snapshot.copy(failure = null)),
                PlaybackAction.SelectPrimaryGraph(
                    state.snapshot.generation,
                    summary,
                    evidence,
                    state.snapshot.profile,
                ),
            )
        }
        val graph = state.snapshot.graph ?: return terminalFailure(state)
        val retrying = state.copy(snapshot = state.snapshot.copy(failure = null))
        return if (state.surfaceAvailable) {
            transition(retrying, PlaybackAction.AttachSurface(state.snapshot.generation, graph))
        } else {
            transition(retrying)
        }
    }

    private fun terminalFailure(state: PlaybackMachineState): PlaybackTransition {
        if (state.activeReleaseEpoch != null) {
            return transition(state.copy(afterRelease = AfterRelease.FAIL))
        }
        return if (hasActiveWork(state)) {
            beginBarrier(state, AfterRelease.FAIL, ActiveWorkReleaseReason.FAILURE)
        } else {
            failedAfterBarrier(state)
        }
    }

    private fun failedAfterBarrier(state: PlaybackMachineState): PlaybackTransition = transition(
        state.copy(
            snapshot = state.snapshot.copy(
                state = PlaybackState.FAILED,
                graph = null,
                isPlaying = false,
                isBuffering = false,
                isReconnecting = false,
                statusCode = null,
            ),
            afterRelease = AfterRelease.NONE,
            graphBeforeRelease = null,
            activeReleaseEpoch = null,
            lifecycleResume = LifecycleResume.NONE,
            lifecycleResumeGraph = null,
        ),
    )

    private fun barrierCompleted(
        state: PlaybackMachineState,
        releaseEpoch: Long,
    ): PlaybackTransition {
        if (state.activeReleaseEpoch != releaseEpoch) return unchanged(state)
        val releasedGraph = state.graphBeforeRelease
        val cleared = state.copy(
            snapshot = state.snapshot.copy(graph = null),
            activeReleaseEpoch = null,
            graphBeforeRelease = null,
        )
        return when (state.afterRelease) {
            AfterRelease.STOP,
            AfterRelease.NONE,
            -> transition(
                cleared.copy(
                    snapshot = cleared.snapshot.copy(
                        state = PlaybackState.STOPPED,
                        statusCode = PlaybackStatusCode.STOPPED,
                    ),
                    afterRelease = AfterRelease.NONE,
                    incident = null,
                ),
            )
            AfterRelease.START_NEW_REQUEST -> {
                val request = cleared.request ?: return terminalFailure(cleared)
                transition(
                    cleared.copy(
                        snapshot = cleared.snapshot.copy(
                            state = PlaybackState.RESOLVING,
                            statusCode = PlaybackStatusCode.RESOLVING,
                        ),
                        afterRelease = AfterRelease.NONE,
                    ),
                    PlaybackAction.ResolveRequest(cleared.snapshot.generation, request),
                )
            }
            AfterRelease.REBUILD_CURRENT_GRAPH -> {
                val graph = releasedGraph ?: return terminalFailure(cleared)
                val rebuilding = cleared.copy(
                    snapshot = cleared.snapshot.copy(
                        state = PlaybackState.ATTACHING_SURFACE,
                        graph = graph,
                        statusCode = PlaybackStatusCode.STARTING,
                    ),
                    afterRelease = AfterRelease.NONE,
                )
                if (rebuilding.surfaceAvailable) {
                    transition(
                        rebuilding,
                        PlaybackAction.AttachSurface(rebuilding.snapshot.generation, graph),
                    )
                } else {
                    transition(rebuilding)
                }
            }
            AfterRelease.RESELECT_GRAPH -> selectAgain(cleared)
            AfterRelease.HANDOFF -> {
                val graph = releasedGraph ?: return terminalFailure(cleared)
                val failure = state.snapshot.failure ?: return terminalFailure(cleared)
                transition(
                    cleared.copy(
                        snapshot = cleared.snapshot.copy(state = PlaybackState.SELECTING_GRAPH),
                        afterRelease = AfterRelease.NONE,
                    ),
                    PlaybackAction.SelectHandoffGraph(cleared.snapshot.generation, graph, failure),
                )
            }
            AfterRelease.FAIL -> failedAfterBarrier(cleared)
            AfterRelease.SUSPEND -> {
                val suspended = cleared.copy(
                    snapshot = cleared.snapshot.copy(
                        state = PlaybackState.STOPPED,
                        isPlaying = false,
                        isBuffering = false,
                        isReconnecting = false,
                        statusCode = PlaybackStatusCode.STOPPED,
                    ),
                    afterRelease = AfterRelease.NONE,
                    lifecycleResumeGraph = state.lifecycleResumeGraph ?: releasedGraph,
                )
                if (suspended.lifecycleActive) resumeLifecycle(suspended) else transition(suspended)
            }
        }
    }

    private fun barrierFailed(
        state: PlaybackMachineState,
        releaseEpoch: Long,
        failure: PlaybackFailure,
    ): PlaybackTransition {
        if (state.activeReleaseEpoch != releaseEpoch) return unchanged(state)
        return transition(
            state.copy(
                snapshot = state.snapshot.copy(
                    state = PlaybackState.FAILED,
                    isPlaying = false,
                    isBuffering = false,
                    isReconnecting = false,
                    failure = failure,
                    statusCode = null,
                ),
                afterRelease = AfterRelease.NONE,
                activeReleaseEpoch = null,
                lifecycleResume = LifecycleResume.NONE,
                lifecycleResumeGraph = null,
            ),
        )
    }

    private fun beginBarrier(
        state: PlaybackMachineState,
        afterRelease: AfterRelease,
        reason: ActiveWorkReleaseReason,
        barrierState: PlaybackState = PlaybackState.RELEASING,
    ): PlaybackTransition {
        state.activeReleaseEpoch?.let {
            return transition(state.copy(afterRelease = afterRelease))
        }
        check(state.nextReleaseEpoch < Long.MAX_VALUE) { "Playback release epoch exhausted" }
        val epoch = state.nextReleaseEpoch
        return transition(
            state.copy(
                snapshot = state.snapshot.copy(
                    state = barrierState,
                    isPlaying = false,
                    isBuffering = false,
                    isReconnecting = false,
                    statusCode = if (barrierState == PlaybackState.HANDING_OFF_ONCE) {
                        PlaybackStatusCode.HANDING_OFF
                    } else {
                        state.snapshot.statusCode
                    },
                ),
                afterRelease = afterRelease,
                graphBeforeRelease = state.snapshot.graph,
                activeReleaseEpoch = epoch,
                nextReleaseEpoch = epoch + 1,
            ),
            PlaybackAction.ReleaseActiveWork(
                generation = state.snapshot.generation,
                releaseEpoch = epoch,
                reason = reason,
            ),
        )
    }

    private fun streamAvailabilityChanged(
        state: PlaybackMachineState,
        input: PlaybackReducerInput.StreamAvailabilityChanged,
    ): PlaybackTransition {
        val availability = input.availability
        if (availability is StreamAvailability.TerminallyUnavailable) {
            val authorized = when (availability.evidence) {
                TerminalAvailabilityEvidence.ALL_ELIGIBLE_GRAPHS_EXHAUSTED ->
                    input.authority == TerminalAvailabilityAuthority.SESSION_POLICY
                TerminalAvailabilityEvidence.SOURCE_CONFIRMED,
                TerminalAvailabilityEvidence.PROVIDER_DECLARED,
                -> input.authority in setOf(
                    TerminalAvailabilityAuthority.SOURCE,
                    TerminalAvailabilityAuthority.SESSION_POLICY,
                )
            }
            if (!authorized) return unchanged(state)
            return terminalFailure(
                state.copy(snapshot = state.snapshot.copy(streamAvailability = availability)),
            )
        }
        return transition(state.copy(snapshot = state.snapshot.copy(streamAvailability = availability)))
    }

    private fun selectAgain(state: PlaybackMachineState): PlaybackTransition {
        val summary = state.snapshot.requestSummary ?: return terminalFailure(state)
        val evidence = state.evidence ?: return terminalFailure(state)
        return transition(
            state.copy(
                snapshot = state.snapshot.copy(state = PlaybackState.SELECTING_GRAPH),
                afterRelease = AfterRelease.NONE,
            ),
            PlaybackAction.SelectPrimaryGraph(
                state.snapshot.generation,
                summary,
                evidence,
                state.snapshot.profile,
            ),
        )
    }

    private fun previewAfterFailure(
        state: PlaybackMachineState,
        failure: PlaybackFailure,
    ): PreviewAvailability {
        if (state.snapshot.profile != SessionProfile.GUIDE) return state.snapshot.previewAvailability
        val reason = when (failure.domain) {
            FailureDomain.VIDEO_RENDERER_SURFACE -> PreviewUnavailableReason.GUIDE_RENDER_PATH_UNAVAILABLE
            FailureDomain.DEVICE_RESOURCE -> PreviewUnavailableReason.GUIDE_RESOURCE_RESTRICTION
            else -> return state.snapshot.previewAvailability
        }
        return PreviewAvailability.Unavailable(reason)
    }

    private fun PlaybackMachineState.ensureIncident(): Pair<PlaybackMachineState, PlaybackIncident> {
        incident?.let { return this to it }
        check(nextIncidentSequence < Long.MAX_VALUE) { "Playback incident sequence exhausted" }
        val created = PlaybackIncident(nextIncidentSequence)
        return copy(
            incident = created,
            nextIncidentSequence = nextIncidentSequence + 1,
        ) to created
    }

    private fun hasActiveWork(state: PlaybackMachineState): Boolean =
        state.activeReleaseEpoch != null || state.snapshot.state.isPlaybackActive()

    private fun nextGeneration(current: Long): Long {
        check(current < Long.MAX_VALUE) { "Playback request generation exhausted" }
        return current + 1
    }

    private fun PlaybackState.isPlaybackActive(): Boolean = this in setOf(
        PlaybackState.RESOLVING,
        PlaybackState.SELECTING_GRAPH,
        PlaybackState.ATTACHING_SURFACE,
        PlaybackState.STARTING_PRIMARY,
        PlaybackState.PLAYING,
        PlaybackState.DEGRADED,
        PlaybackState.RECOVERING_IN_PLACE,
        PlaybackState.HANDING_OFF_ONCE,
        PlaybackState.LIVE_RECONNECTING,
    )

    private fun PlaybackState.acceptsProgress(): Boolean = this in setOf(
        PlaybackState.STARTING_PRIMARY,
        PlaybackState.PLAYING,
        PlaybackState.DEGRADED,
        PlaybackState.RECOVERING_IN_PLACE,
        PlaybackState.LIVE_RECONNECTING,
    )

    private fun PlaybackState.isFailureEligible(): Boolean = isPlaybackActive()

    private fun transition(
        state: PlaybackMachineState,
        vararg actions: PlaybackAction,
    ): PlaybackTransition = PlaybackTransition(state, actions.toList())

    private fun unchanged(state: PlaybackMachineState): PlaybackTransition = PlaybackTransition(state)
}
