package com.nuvio.tv.playback.android

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.nuvio.tv.core.multiview.CodecFacts
import com.nuvio.tv.core.multiview.DecoderFacts
import com.nuvio.tv.core.multiview.DecoderFactsPort
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multiview decoder facts from MediaCodecList, per MIME type: the non-secure hardware AVC and HEVC decoders. Never
 * the app-wide "conservative concurrent decoder budget" (that takes the smallest value over every decoder, which on a
 * budget box is a VC-1 decoder's 1). Read once per process, off the main thread.
 */
@Singleton
internal class AndroidDecoderFactsSource @Inject constructor() : DecoderFactsPort {
    @Volatile
    private var cached: DecoderFacts? = null

    override suspend fun facts(): DecoderFacts = cached ?: withContext(Dispatchers.Default) {
        DecoderFacts(avc = read("video/avc"), hevc = read("video/hevc")).also { cached = it }
    }

    private fun read(mime: String): CodecFacts? = runCatching {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                !info.name.endsWith(".secure") && !isSoftware(info)
        }
        // The decoder the device would pick for a plain stream: the first hardware one.
        val info = decoders.firstOrNull() ?: return@runCatching null
        val caps = info.getCapabilitiesForType(mime)
        val video = caps.videoCapabilities
        // The measured rate is the block's total throughput; the performance points are the vendor's claim.
        val measured = video?.getAchievableFrameRatesFor(1920, 1080)?.upper?.toFloat()
            ?: video?.let(::performancePointFps)
        CodecFacts(declaredInstances = caps.maxSupportedInstances.takeIf { it > 0 }, throughputFps1080 = measured)
    }.getOrNull()

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    private fun performancePointFps(video: MediaCodecInfo.VideoCapabilities): Float? {
        if (Build.VERSION.SDK_INT < 29) return null
        val points = video.supportedPerformancePoints ?: return null
        return listOf(120, 60, 50, 30, 25).firstOrNull { fps ->
            points.any { it.covers(MediaCodecInfo.VideoCapabilities.PerformancePoint(1920, 1080, fps)) }
        }?.toFloat()
    }
}
