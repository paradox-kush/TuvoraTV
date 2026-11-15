package com.nuvio.tv.core.sports.detail.screen

/**
 * The one motion vocabulary of the game screen (values from androidx material3 `MotionTokens.kt`, checked 2026-11-14).
 * Every animation is built from these; nothing loops. Android TV needs no reduce-motion code: Compose scales every
 * `animate*AsState` / `Animatable` by the system animator duration scale. Easings: standard = cubic-bezier(0.2, 0, 0, 1);
 * enter = emphasizedDecelerate (0.05, 0.7, 0.1, 1).
 */
object MotionSpec {
    /** DurationShort4: chips, pops, focus. */
    const val QUICK_MS = 200
    /** DurationLong2: positions and bar widths. */
    const val MOVE_MS = 500
    /** DurationLong4: number count-up, chart segment draw. */
    const val COUNT_MS = 600
    /** DurationExtraLong1: one-shot emphasis (goal, touchdown). */
    const val PULSE_MS = 700
}
