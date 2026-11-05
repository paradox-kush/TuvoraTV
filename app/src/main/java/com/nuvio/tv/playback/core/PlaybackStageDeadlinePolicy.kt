package com.nuvio.tv.playback.core

/**
 * B178: the phase watchdogs only cover the engine's own startup (surface, bytes, tracks, decoder,
 * first frame). Before the engine exists there was no deadline at all: a provider resolution that
 * never returns (a dead Stalker portal), or a graph selection that never answers, left the guide
 * preview on "starting" with a spinner and no Failed/Retry. These per-stage deadlines are the
 * backstop; they are pure and gated by the same switch as the other watchdogs.
 */
object PlaybackStageDeadlinePolicy {
    const val RESOLVING_MS = 30_000L
    const val SELECTING_GRAPH_MS = 20_000L
    /** STARTING_PRIMARY before any engine attempt began (the engine start call itself never returned). */
    const val PRE_ENGINE_START_MS = 30_000L

    /** Null when the state has no stage deadline (it is covered by a watchdog, or it is steady). */
    fun deadlineMs(state: PlaybackState, engineAttemptStarted: Boolean): Long? = when (state) {
        PlaybackState.RESOLVING -> RESOLVING_MS
        PlaybackState.SELECTING_GRAPH -> SELECTING_GRAPH_MS
        PlaybackState.STARTING_PRIMARY -> if (engineAttemptStarted) null else PRE_ENGINE_START_MS
        else -> null
    }

    fun failure(state: PlaybackState): PlaybackFailure = PlaybackFailure(
        code = FailureCode.NETWORK_TIMEOUT,
        domain = FailureDomain.NETWORK,
        phase = when (state) {
            PlaybackState.RESOLVING -> FailurePhase.REQUEST_RESOLUTION
            PlaybackState.SELECTING_GRAPH -> FailurePhase.GRAPH_SELECTION
            else -> FailurePhase.ENGINE_START
        },
        // Terminal for this attempt: the viewer's Retry (or a zap) is the next move, not a loop.
        retryability = Retryability.FATAL,
    )
}
