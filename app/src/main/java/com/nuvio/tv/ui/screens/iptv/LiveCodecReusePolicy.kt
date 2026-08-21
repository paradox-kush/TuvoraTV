package com.nuvio.tv.ui.screens.iptv

/**
 * Should the live-guide preview player force a fresh video codec when the channel's video format
 * changes?
 *
 * ExoPlayer sizes the video MediaCodec for adaptive playback at ~1.5x the FIRST format it sees
 * (`MediaCodecVideoRenderer.getCodecMaxValues`) and then keeps reusing that codec for any later
 * stream whose resolution is <= that max. So once the viewer lands on a 4K channel, the codec is
 * pinned at ~4K and every smaller channel (1080p/720p/576p) is decoded through the oversized 4K
 * codec. On the Onn 4K's Amlogic `c2.amlogic.avc.decoder` that in-place downsize throws
 * `MediaCodec$CodecException: Error 0x80000000` and floods "Discard frames from previous
 * generation" — the channel-switch stutter (and, fullscreen, a black screen). Device-confirmed on
 * an Onn 4K (Android 14) 2026-08-21; a documented Amlogic failure class (ExoPlayer #5545/#3535).
 *
 * The fix ([ResolutionAwareVideoRenderer]) discards the codec on a resolution change so each channel
 * gets a codec sized for ITS stream — a ~50-100ms clean re-init instead of seconds of stutter. This
 * pure predicate is the decision, pulled out so the invariant is pinned by tests (the failure mode
 * is invisible in a screenshot until the resolutions happen to differ).
 */
object LiveCodecReusePolicy {

    /**
     * True when [oldWidth]x[oldHeight] and [newWidth]x[newHeight] are both real resolutions that
     * DIFFER — the case where the codec must be re-created rather than reused.
     *
     * Unknown/unset dimensions (<= 0, i.e. `Format.NO_VALUE`) return false: with no size to compare
     * we defer to ExoPlayer's normal reuse logic rather than forcing a spurious re-init.
     */
    fun resolutionChanged(oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int): Boolean {
        if (oldWidth <= 0 || oldHeight <= 0 || newWidth <= 0 || newHeight <= 0) return false
        return oldWidth != newWidth || oldHeight != newHeight
    }
}
