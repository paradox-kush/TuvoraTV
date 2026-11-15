package com.nuvio.tv.ui.screens.iptv

/**
 * B5 of the IPTV redesign (2026-11-15, 05-fullscreen): every channel list row shows what is on now with a progress
 * line, so the list reads as a guide by itself. One rule for every list: a line only for a programme airing at
 * [nowMs], clamped to 0..1. Pure.
 */
internal object GuideNowProgress {
    fun fraction(startMs: Long?, endMs: Long?, nowMs: Long): Float? {
        if (startMs == null || endMs == null || endMs <= startMs) return null
        if (nowMs < startMs || nowMs >= endMs) return null
        return ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }
}
