package com.nuvio.tv.ui.screens.iptv

/**
 * When the live guide's "now" clock should tick next.
 *
 * The screen reads the wall clock on every resume (so a guide that comes back from another app or
 * the screensaver is never stale), then ticks on wall-clock minute boundaries. Aligning to the
 * boundary keeps the now-line and "ends in N min" labels in step with the system clock instead of
 * drifting by however long the screen took to compose.
 */
internal object GuideClockPolicy {
    const val TICK_MS = 60_000L

    /** Milliseconds from [nowMs] to the next minute boundary; a full minute when exactly on one. */
    fun delayToNextTickMs(nowMs: Long): Long {
        val intoMinute = Math.floorMod(nowMs, TICK_MS)
        return if (intoMinute == 0L) TICK_MS else TICK_MS - intoMinute
    }
}
