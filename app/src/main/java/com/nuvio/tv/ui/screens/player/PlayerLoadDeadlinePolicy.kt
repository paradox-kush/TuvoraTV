package com.nuvio.tv.ui.screens.player

/**
 * Every VOD start ends: B214 (libmpv loads the file but never presents a frame) and B217 (offline
 * start) both sat on "Starting stream..." with no message and no Retry. The engines' own watchdogs
 * are per attempt and best effort; this is the screen's backstop across auto-retries and engine
 * failovers. The caller measures elapsed time from the first attempt (not reset by auto-retries).
 */
internal object PlayerLoadDeadlinePolicy {
    /** Offline: no point waiting for a provider that cannot be reached. */
    const val OFFLINE_DEADLINE_MS = 15_000L

    /** Online, no first frame: covers mpv's 30 s watchdog plus its retries. */
    const val HARD_DEADLINE_MS = 75_000L

    const val POLL_INTERVAL_MS = 1_000L

    enum class Action { Wait, FailOffline, FailTimeout, RetryNetworkBack }

    fun decide(
        elapsedMs: Long,
        online: Boolean,
        firstFrameRendered: Boolean,
        isLive: Boolean,
        isTorrent: Boolean,
        suspended: Boolean,
        hasError: Boolean,
        failedOffline: Boolean,
    ): Action {
        // Failed offline is re-asked by an event (network back), never a timer.
        if (failedOffline) return if (online) Action.RetryNetworkBack else Action.Wait
        if (firstFrameRendered || isLive || isTorrent || suspended || hasError) return Action.Wait
        return when {
            !online && elapsedMs >= OFFLINE_DEADLINE_MS -> Action.FailOffline
            elapsedMs >= HARD_DEADLINE_MS -> Action.FailTimeout
            else -> Action.Wait
        }
    }
}
