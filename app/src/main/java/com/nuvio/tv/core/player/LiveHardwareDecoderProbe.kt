package com.nuvio.tv.core.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.nuvio.tv.core.analytics.LiveHardwareDecoderPolicy

/**
 * Resolves whether THIS device should open live playback on libmpv instead of ExoPlayer's hardware
 * decoder, by probing the hardware video decoder `MediaCodec` would select for live's common codecs
 * and feeding [LiveHardwareDecoderPolicy].
 *
 * A device capability, not a per-stream decision: the fleet freeze telemetry (PostHog 494529,
 * 2026-08-25) shows the live `video_stalled` correlates with the SoC's hardware decoder, not with
 * the stream's codec or resolution — so the neutral signal is the name of the primary hardware
 * AVC/HEVC decoder on this box. Probed once and cached (the `MediaCodecList` scan is cheap but the
 * answer never changes for a given device).
 */
object LiveHardwareDecoderProbe {

    @Volatile private var cached: Boolean? = null

    /**
     * True when live should default to libmpv on this device. The decision itself lives in the pure,
     * unit-tested [LiveHardwareDecoderPolicy]; this only supplies the device's real decoder name.
     */
    fun preferLibmpvForLive(): Boolean {
        cached?.let { return it }
        val result = LiveHardwareDecoderPolicy.preferLibmpvForLive(
            deviceModel = Build.MODEL,
            videoDecoderName = primaryHardwareVideoDecoderName(),
        )
        cached = result
        return result
    }

    /** The name of the hardware decoder `MediaCodec` would pick for live's dominant codecs — H.264
     *  first, then HEVC — or null if the scan fails or only software decoders exist. */
    private fun primaryHardwareVideoDecoderName(): String? = try {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        var name: String? = null
        for (mime in arrayOf("video/avc", "video/hevc")) {
            name = codecInfos.firstOrNull { info ->
                !info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    isHardware(info)
            }?.name
            if (name != null) break
        }
        name
    } catch (t: Throwable) {
        null
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        // Pre-Q heuristic: the only non-hardware decoders we need to exclude are the AOSP software
        // ones (OMX.google.* / c2.android.*); everything else is a vendor hardware decoder.
        val n = info.name.lowercase()
        return !n.startsWith("omx.google.") && !n.startsWith("c2.android.")
    }
}
