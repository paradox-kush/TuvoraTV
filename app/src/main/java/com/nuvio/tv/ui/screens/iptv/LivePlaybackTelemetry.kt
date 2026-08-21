package com.nuvio.tv.ui.screens.iptv

import androidx.media3.common.Player
import com.posthog.PostHog

/**
 * What the live-guide preview player reports from the field.
 *
 * Two signals the channel-switch stutter/black investigation (2026-08-21) had to reconstruct from a
 * dev-device adb capture because nothing measured them:
 *  - a preview DECODING error — the 4K-codec-reused-for-a-smaller-channel failure that surfaces as
 *    `MediaCodec$CodecException 0x80000000` on the Onn's Amlogic decoder;
 *  - a preview STALL + freeze-watchdog re-tune — a provider dropping the feed (STATE_IDLE/ENDED).
 *
 * With these, "a user says it stutters" becomes a HogQL query, and the renderer fix can be proven in
 * the field by watching [playbackError] collapse for the video-decoding codes after the next release.
 * An app can only read its OWN logcat, so the low-level `c2.amlogic`/`CCodec` lines are never
 * available from users — but ExoPlayer hands the app the error code + failing Format directly, which
 * is the signal that matters.
 *
 * PRIVACY (same rule as [com.nuvio.tv.core.epg.EpgTelemetry]): no URL, host, username, playlist or
 * CHANNEL name. Errors report their code + exception CLASS, never the message (media error messages
 * routinely quote the request URL, which carries the host and credentials). Resolution numbers,
 * closed-vocabulary codes only.
 */
object LivePlaybackTelemetry {

    /**
     * A preview playback error. The codec-reuse failure surfaces here as
     * `error_code = ERROR_CODE_DECODING_FAILED` + `cause_class = MediaCodecDecoderException` (or
     * `CodecException`) at the mis-sized resolution. [width]/[height] are the failing renderer
     * format's dimensions (<= 0 = unknown, omitted).
     */
    fun playbackError(errorCode: String, causeClass: String?, width: Int, height: Int) {
        runCatching {
            PostHog.capture(
                event = "live_playback_error",
                properties = buildMap {
                    put("error_code", errorCode)          // e.g. ERROR_CODE_DECODING_FAILED — closed vocab
                    causeClass?.let { put("cause_class", it) }  // class name only, never the message
                    if (width > 0) put("width", width)
                    if (height > 0) put("height", height)
                    put("surface", "live_guide")
                },
            )
        }
    }

    /**
     * The preview stalled (STATE_IDLE/ENDED with no error) and the freeze watchdog acted.
     * [surfaced] = true when the watchdog gave up and showed the viewer an error; false when it
     * re-tuned. [attempt] is the recovery attempt count.
     */
    fun previewStall(playbackState: Int, attempt: Int, surfaced: Boolean) {
        runCatching {
            PostHog.capture(
                event = "live_preview_stall",
                properties = mapOf(
                    // NOT "state": that key comes back null in PostHog (reserved / stripped by the
                    // host privacy guard) — verified on-device 2026-08-21 while the sibling keys landed.
                    "playback_state" to when (playbackState) {
                        Player.STATE_IDLE -> "idle"
                        Player.STATE_ENDED -> "ended"
                        else -> "other"
                    },
                    "attempt" to attempt,
                    "surfaced" to surfaced,
                    "surface" to "live_guide",
                ),
            )
        }
    }
}
