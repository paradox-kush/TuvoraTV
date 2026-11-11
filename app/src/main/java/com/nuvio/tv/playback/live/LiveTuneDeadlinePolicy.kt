package com.nuvio.tv.playback.live

import com.nuvio.tv.playback.core.PlaybackNetworkRequest
import com.nuvio.tv.playback.core.PlaybackPolicy

/**
 * Backstop deadline for a guide `host.tune()` / `host.zap()` call (B247). Those calls run while the
 * guide owner holds its ownership lock, so one that never returns freezes every later operation
 * (collapse/expand, detach-style commands). The wait must therefore end on its own.
 *
 * The deadline is deliberately LONGER than the playback state machine's own start timeouts, so the
 * watchdogs always get to report a precise failure first and this only fires when the call itself
 * ignores them. Numbers come from [PlaybackPolicy.WatchdogConfiguration] and
 * [PlaybackNetworkRequest]: surface attach 3 s + first media byte (60 s read timeout) + live
 * bytes-to-tracks 1.5 s + two 4K decoder phases 2 x 6 s + first frame 1 s = 77.5 s worst case;
 * 90 s leaves 12.5 s of margin.
 */
object LiveTuneDeadlinePolicy {
    const val DEADLINE_MS = 90_000L

    /** What the owner does when the deadline expires. Never "keep loading". */
    enum class Expiry {
        /** Release the wedged host and end in the Failed state, where Retry is offered. */
        RELEASE_HOST_AND_FAIL,
    }

    val expiry: Expiry = Expiry.RELEASE_HOST_AND_FAIL

    /** Sum of the playback state machine's sequential start budgets (worst case, 4K live). */
    fun worstCaseStartBudgetMs(
        watchdog: PlaybackPolicy.WatchdogConfiguration = PlaybackPolicy.WatchdogConfiguration(),
        network: PlaybackNetworkRequest = PlaybackNetworkRequest(),
    ): Long =
        watchdog.surfaceAttachmentProgressMs +
            minOf(network.readTimeoutMs.toLong(), network.callTimeoutMs?.toLong() ?: Long.MAX_VALUE) +
            watchdog.liveBytesToTracksMs +
            2 * watchdog.highResolutionDecoderMs +
            watchdog.liveReadyToFirstFrameMs
}
