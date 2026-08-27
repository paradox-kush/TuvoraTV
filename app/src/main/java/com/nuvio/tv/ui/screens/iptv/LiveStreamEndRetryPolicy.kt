package com.nuvio.tv.ui.screens.iptv

/** Backoff for a live stream that reached EOF. Attempts are unbounded; cadence is not. */
internal object LiveStreamEndRetryPolicy {
    private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 20_000L)

    /** [attempt] is one-based for user-facing/logging consistency. */
    fun delayMs(attempt: Int): Long = BACKOFF_MS.getOrElse((attempt - 1).coerceAtLeast(0)) {
        BACKOFF_MS.last()
    }
}
