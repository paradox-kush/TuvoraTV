package com.nuvio.tv.playback.media3

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

private val HARDWARE_FIRST_VIDEO_TYPES = setOf(
    MimeTypes.VIDEO_H264,
    MimeTypes.VIDEO_H265,
    MimeTypes.VIDEO_VP9,
    MimeTypes.VIDEO_AV1,
    MimeTypes.VIDEO_DOLBY_VISION,
)

private val SOFTWARE_DECODER_PREFIXES = listOf("omx.google.", "c2.android.", "c2.google.", "omx.ffmpeg.")

internal fun hasLiveSoftwareDecoderName(name: String): Boolean {
    val lower = name.lowercase()
    return SOFTWARE_DECODER_PREFIXES.any { lower.startsWith(it) }
}

/** Above this frame size (either dimension) a software video decode is not attempted for live. */
private const val MAX_SOFTWARE_LIVE_WIDTH = 1920
private const val MAX_SOFTWARE_LIVE_HEIGHT = 1088

/** True for a known live video size beyond 1080p (unknown size, -1, is never "above"). */
internal fun isLiveVideoAbove1080p(width: Int, height: Int): Boolean =
    width > MAX_SOFTWARE_LIVE_WIDTH || height > MAX_SOFTWARE_LIVE_HEIGHT

/**
 * Android's software decoders are left out only when a hardware decoder supports this exact format
 * (profile, level, size), so a stream beyond the hardware still plays in software - EXCEPT live video
 * above 1080p: on a 2 GB Onn a software UHD decode (media.swcodec ~97% CPU) starved the render thread
 * and the app was killed by an input-dispatch ANR. There the list is EMPTY: the format is unsupported,
 * which Media3 reports as a deterministic VIDEO_DECODER_UNAVAILABLE (one hand-off, then terminal).
 */
internal fun <T> hardwareFirstLiveVideoDecoders(
    mimeType: String?,
    decoders: List<T>,
    isSoftware: (T) -> Boolean,
    supportsFormat: (T) -> Boolean,
    width: Int = -1,
    height: Int = -1,
    live: Boolean = true,
): List<T> {
    if (mimeType !in HARDWARE_FIRST_VIDEO_TYPES) return decoders
    val hardware = decoders.filterNot(isSoftware)
    if (hardware.any(supportsFormat)) return hardware
    if (live && isLiveVideoAbove1080p(width, height)) return emptyList()
    return decoders
}

/** The platform video renderer with [hardwareFirstLiveVideoDecoders] applied to its decoder list. */
@UnstableApi
internal class HardwareFirstLiveVideoRenderer(
    private val rendererContext: Context,
    builder: MediaCodecVideoRenderer.Builder,
    private val liveContent: Boolean,
) : MediaCodecVideoRenderer(builder) {
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val all = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        val picked = hardwareFirstLiveVideoDecoders(
            format.sampleMimeType,
            all,
            { it.softwareOnly || hasLiveSoftwareDecoderName(it.name) },
            { runCatching { it.isFormatSupported(rendererContext, format) }.getOrDefault(false) },
            width = format.width,
            height = format.height,
            live = liveContent,
        )
        if (picked.isEmpty() && all.isNotEmpty()) {
            // Reason line for the diag trail: closed text, no URL, host or channel.
            android.util.Log.w("CleanPlaybackDiag", "live_uhd_software_decode_blocked reason=no_hardware_decoder_above_1080p")
        }
        return picked
    }
}
