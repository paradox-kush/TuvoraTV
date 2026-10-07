package com.nuvio.tv.core.sync

object RealtimeRetryPolicy {
    const val STABLE_CONNECTION_MS = 60_000L
    fun delayMs(attempt: Int, jitter: Double): Long {
        val ceiling = (1_000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(60_000L)
        return (ceiling / 2 + (ceiling / 2 * jitter.coerceIn(0.0, 1.0)).toLong()).coerceAtLeast(500L)
    }
}
