package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.iptv.LoadStatus

/** What the live player UI renders for pause / rewind. One per player instance (multiview-ready). */
sealed interface LivePauseState {
    /** At live, not paused. */
    data object Off : LivePauseState

    /** Starting the buffer. Entered only through `BoundedLoad.begin(LoadSurface.LIVE_PAUSE)` (ArchitectureTest R7). */
    data class Preparing(val status: LoadStatus.Loading) : LivePauseState

    data class Shifted(
        val paused: Boolean,
        val behindLiveMs: Long,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val positionMs: Long,
        val bufferFull: Boolean,
    ) : LivePauseState

    /** Shown with Retry + Live. */
    data class Failed(val reason: LivePauseFailure) : LivePauseState
}

enum class LivePauseFailure { NO_SPACE, PROVIDER_REFUSED, STALLED, SEQUENCE_RESET, UNSUPPORTED_STREAM, TIMED_OUT }
