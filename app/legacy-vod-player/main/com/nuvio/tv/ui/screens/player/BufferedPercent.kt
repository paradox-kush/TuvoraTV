package com.nuvio.tv.ui.screens.player

/**
 * Buffered percentage that never throws.
 *
 * media3's `Player.getBufferedPercentage()` computes `bufferedPosition * 100 / duration` and passes
 * the result through `Ints.checkedCast`, which throws `IllegalArgumentException("Out of range")`
 * when the ratio overflows Int. That happens on live `.ts` whose extractor-derived duration is a
 * tiny/garbage PTS-wrap value (media3 #2750; fixed upstream in 1.9.0 by switching to a saturating
 * cast). The outer `constrainValue(..., 0, 100)` in `BasePlayer` never runs because `percentInt`
 * throws first. We only read buffered% for diagnostics/telemetry, so compute it defensively here
 * instead of calling the throwing getter on the player.
 */
object BufferedPercent {
    /**
     * 0..100, clamped and overflow-proof. 100 when duration is 0 (media3 parity); 0 when duration is
     * unknown/negative (`C.TIME_UNSET` == -1, or any garbage negative live duration).
     */
    fun of(bufferedPositionMs: Long, durationMs: Long): Int = when {
        durationMs == 0L -> 100
        durationMs < 0L -> 0
        // Double math + saturating toInt() + coerceIn: cannot throw for any Long inputs.
        else -> ((bufferedPositionMs.toDouble() / durationMs.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }
}
