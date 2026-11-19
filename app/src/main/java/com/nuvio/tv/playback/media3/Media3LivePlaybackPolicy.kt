package com.nuvio.tv.playback.media3

import com.nuvio.tv.playback.core.BufferingPreference

/**
 * Media3 LIVE load control, TiviMate-style (stock Media3 fed continuously; research/
 * tivimate-player-analysis-2026-10-14.md and exoplayer-tivimate-parity-spec-2026-10-14.md).
 * Pure, no Media3 types, so it tests without a device.
 *
 * Continuous loading: min == max == 60 s, so a progressive (raw TS) ProgressiveMediaPeriod never
 * parks the live HTTP socket on a short time window; the 2 s start and 4 s after-rebuffer gates
 * absorb jitter. The BYTE cap stays the real limit (setPrioritizeTimeOverSizeThresholds(false) in
 * the backend): [clampToByteBudget] shrinks the window to what the device's memory budget holds, so
 * low-RAM boxes never exceed their live budget. A buffer length the user chose is never overridden.
 */
internal object Media3LivePlaybackPolicy {
    const val CONTINUOUS_BUFFER_MS = 60_000
    const val START_MS = 2_000
    const val REBUFFER_MS = 4_000
    const val MIN_CONTINUOUS_BUFFER_MS = 8_000
    /** Worst realistic live bitrate used to turn the byte budget into seconds. */
    const val ASSUMED_LIVE_BITRATE_BPS = 16_000_000L

    /** Null = leave the plan as the resolver chose (only the AUTO live buffer is replaced). */
    fun bufferPlan(buffering: BufferingPreference): Media3BufferPlan? =
        if (buffering == BufferingPreference.LOW_LATENCY_LIVE) {
            Media3BufferPlan(CONTINUOUS_BUFFER_MS, CONTINUOUS_BUFFER_MS, START_MS, REBUFFER_MS)
        } else {
            null
        }

    /** Keeps a continuous plan continuous while never asking for more than the byte budget holds. */
    fun clampToByteBudget(plan: Media3BufferPlan, targetBytes: Int): Media3BufferPlan {
        if (plan.minimumMs != plan.maximumMs) return plan
        val affordableMs = (targetBytes.toLong() * 8L * 1_000L / ASSUMED_LIVE_BITRATE_BPS)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val windowMs = minOf(plan.maximumMs, affordableMs).coerceAtLeast(MIN_CONTINUOUS_BUFFER_MS)
        return plan.copy(minimumMs = windowMs, maximumMs = windowMs)
    }
}
