package com.nuvio.tv.ui.screens.iptv

/**
 * Sim pass 2026-11-16 (UX): entering full screen live gave no channel name or number until the first CH+. Entering
 * full screen now names the channel PLAYING (not the row the guide was aimed at, which can differ after Play/Pause)
 * for [ENTRY_MS]; a zap keeps naming where it landed for [LiveZapModePolicy.BANNER_MS]. Pure.
 */
internal object GuideBannerPolicy {
    enum class Reason { ENTRY, ZAP }

    /** 05-fullscreen: a short channel banner on entry. */
    const val ENTRY_MS = 3_000L

    fun durationMs(reason: Reason): Long = when (reason) {
        Reason.ENTRY -> ENTRY_MS
        Reason.ZAP -> LiveZapModePolicy.BANNER_MS
    }

    /** The channel the banner names: on entry the one playing, on a zap the one aimed at (surf) or landed on. */
    fun channelId(reason: Reason, aimed: String?, playing: String?): String? = when (reason) {
        Reason.ENTRY -> playing ?: aimed
        Reason.ZAP -> aimed ?: playing
    }
}
