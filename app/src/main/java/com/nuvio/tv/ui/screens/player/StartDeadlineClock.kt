package com.nuvio.tv.ui.screens.player

/**
 * The viewer's real wait for [VodStartDeadlinePolicy]: it counts only time the load was active.
 * Paused or backgrounded time, and gaps where nobody ticked (a re-resolve, a throttled loop), are
 * not counted. It restarts on a new source identity, a failure that was shown, or an explicit
 * reset (Retry, "Start from beginning"). An engine failover keeps the same key, so it keeps the clock.
 */
internal class StartDeadlineClock {
    private var key: String? = null
    private var lastTickMs: Long = -1L
    var activeMs: Long = 0L
        private set

    /** Call at the start of every attempt. [force] = a failure was shown, or the viewer restarted. */
    fun onAttempt(key: String?, nowMs: Long, force: Boolean = false) {
        if (force || this.key != key || lastTickMs < 0L) reset(nowMs)
        this.key = key
    }

    fun reset(nowMs: Long) {
        lastTickMs = nowMs
        activeMs = 0L
    }

    /** Forget everything (the load ended); the next attempt starts a new clock. */
    fun clear() {
        key = null
        lastTickMs = -1L
        activeMs = 0L
    }

    fun tick(nowMs: Long, suspended: Boolean): Long {
        if (lastTickMs < 0L) lastTickMs = nowMs
        val delta = (nowMs - lastTickMs).coerceIn(0L, MAX_COUNTED_GAP_MS)
        lastTickMs = nowMs
        if (!suspended) activeMs += delta
        return activeMs
    }

    companion object {
        /** A gap longer than this between ticks is not the viewer waiting (throttled or parked loop). */
        const val MAX_COUNTED_GAP_MS = 5_000L
    }
}
