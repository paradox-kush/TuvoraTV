package com.nuvio.tv.core.analytics

/**
 * Turns mpv's `estimated-vf-fps` (a rate) into the monotonic "frames are still reaching the
 * screen" counter that [LivePlaybackFreezePolicy] consumes as `videoProgressTicks` — the same
 * contract ExoPlayer satisfies natively with `renderedOutputBufferCount`.
 *
 * WHY THIS EXISTS (review pass 3, F1/F2 — device-proven on the Onn 4K): mpv exposes no cumulative
 * presented-frames property, only the fps estimate. The counter must therefore be advanced at
 * READ time — once per freeze sample while frames flow — and NOT from mpv's property-change
 * callbacks. `estimated-vf-fps` stops emitting once it settles to a steady value, so a
 * callback-driven count plateaus during healthy steady-state playback exactly as it does during
 * a real freeze: it cannot tell the two apart. That shipped as a false `VIDEO_STALLED` on healthy
 * libmpv — a spurious live reconnect, and a 509 risk on `max_connections=1` providers — on the
 * two platforms whose counters were callback-driven (Android TV, Android mobile). iOS and desktop
 * already advance at read time (`getDouble("estimated-vf-fps") > 0 → ticks++`); this pulls the
 * decision into one pure, tested rule so all four platforms agree.
 *
 * Pure and clock-free so it tests without mpv, the player, or Android (house policy pattern,
 * alongside [LivePlaybackFreezePolicy]).
 */
internal object MpvVideoOutputSignal {

    /**
     * fps at or above which the video output counts as live. Real video runs at ~15fps and up; a
     * frozen VO's estimate decays toward 0. 1.0 sits far below any real content and above the
     * decayed-but-nonzero readings a stalled filter chain can briefly hold, so a genuine freeze
     * crosses it (→ counter holds → detected) while healthy playback never dips below it.
     */
    const val MIN_LIVE_FPS = 1.0

    /**
     * Advance the tick by one if frames are flowing, else hold it. Call once per freeze sample,
     * feeding [estimatedVfFps] from the property shadow (the last mirrored value — never a live
     * mpv read on the main thread, which would ANR). A held tick is what [LivePlaybackFreezePolicy]
     * reads as "the picture stopped".
     */
    fun advance(currentTicks: Long, estimatedVfFps: Double): Long =
        if (estimatedVfFps >= MIN_LIVE_FPS) currentTicks + 1 else currentTicks
}
