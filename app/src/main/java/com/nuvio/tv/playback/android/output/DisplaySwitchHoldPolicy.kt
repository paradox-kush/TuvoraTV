package com.nuvio.tv.playback.android.output

/**
 * How long playback stays held around a display-mode switch. Both numbers are bounds, never waits that can
 * run on: the hold ends after [maxHoldMs] whatever the display does (the BoundedLoad rule).
 *
 * Sources: Android's frame-rate guide (request the mode, wait for DisplayListener.onDisplayChanged, then ~2 s
 * more for non-seamless switches), Plezy's FrameRateManager (DISPLAY_SETTLE_MS = 2000 plus a watchdog).
 */
data class DisplaySwitchHoldPolicy(
    /** Extra time after the display reports the new mode: the TV's HDMI re-sync outlasts the callback. */
    val settleMs: Long = 2_000L,
    /** Hard ceiling for request + confirmation + settle together. */
    val maxHoldMs: Long = 6_000L,
) {
    init {
        require(settleMs >= 0 && maxHoldMs >= settleMs)
    }

    /** What is left to wait after the switch confirmation took [elapsedMs]: settle, but never past the ceiling. */
    fun settleAfterConfirmationMs(elapsedMs: Long): Long =
        settleMs.coerceAtMost((maxHoldMs - elapsedMs).coerceAtLeast(0L))
}
