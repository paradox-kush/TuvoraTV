package com.nuvio.tv.core.telemetry

/** At most one user report per [minIntervalMs]. Thread-safe. */
class ReportRateLimit(private val minIntervalMs: Long = 120_000L) {
    private var lastAtMs: Long? = null
    private var previousAtMs: Long? = null

    /** Returns 0 and records the send when allowed, otherwise the milliseconds still to wait. */
    @Synchronized
    fun tryAcquire(nowMs: Long): Long {
        val last = lastAtMs
        if (last != null && nowMs - last < minIntervalMs) return minIntervalMs - (nowMs - last)
        previousAtMs = last
        lastAtMs = nowMs
        return 0L
    }

    /** A send that failed must not use up the window: give it back. */
    @Synchronized
    fun release() {
        lastAtMs = previousAtMs
    }
}
