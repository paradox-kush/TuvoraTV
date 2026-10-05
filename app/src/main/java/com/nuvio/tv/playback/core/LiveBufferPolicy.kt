package com.nuvio.tv.playback.core

/**
 * F13 — adjustable / automatic buffer length for live IPTV (GitHub tuvora#10). Hand-port of
 * NuvioMobile's commonMain `LiveBufferPolicy` (same choices and numbers).
 *
 * One setting in seconds: [AUTO] keeps the tuned live default ([BufferingPreference.LOW_LATENCY_LIVE]);
 * any other value becomes a CUSTOM buffer for live only, applied identically to the guide preview and
 * fullscreen so a guide->fullscreen promote still applies in place (no rebuild).
 *  - Media3: min = target, max = 2x target, start = a short fixed threshold (zapping stays fast),
 *    after-rebuffer = target capped at 10 s.
 *  - libmpv: readahead from the max buffer (MpvAdapterPlan), plus `cache-pause-wait` = the
 *    after-rebuffer cushion (mpv's default is 1 s — the knob a stuttering provider needs).
 */
object LiveBufferPolicy {
    const val AUTO = 0
    val CHOICES_SECONDS: List<Int> = listOf(AUTO, 5, 10, 20, 30, 60)
    private const val START_MS = 1_500
    private const val MAX_REBUFFER_MS = 10_000

    fun normalize(seconds: Int?): Int {
        val value = seconds ?: return AUTO
        if (value <= 0) return AUTO
        return CHOICES_SECONDS.filter { it != AUTO && it <= value }.maxOrNull() ?: CHOICES_SECONDS.first { it != AUTO }
    }

    /** Null = AUTO (keep LOW_LATENCY_LIVE). */
    fun customBuffer(seconds: Int?): CustomBufferPreference? {
        val s = normalize(seconds)
        if (s == AUTO) return null
        val targetMs = s * 1_000
        return CustomBufferPreference(
            minimumBufferMs = targetMs,
            maximumBufferMs = targetMs * 2,
            playbackStartBufferMs = START_MS,
            rebufferStartBufferMs = minOf(targetMs, MAX_REBUFFER_MS),
        )
    }
}
