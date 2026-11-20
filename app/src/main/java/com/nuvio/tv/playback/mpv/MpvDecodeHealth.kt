package com.nuvio.tv.playback.mpv

import com.nuvio.tv.playback.core.DecodeSignal
import com.nuvio.tv.playback.core.StreamHdrCode

/**
 * Decode-health facts from libmpv, reduced to closed signals. The log text never leaves this object:
 * only the module (`ad`, `vd`, `ao`) and the level are read, and those modules print no URLs.
 */
internal object MpvDecodeHealth {
    /** mpv client.h: MPV_LOG_LEVEL_WARN. Lower numbers are more severe. */
    private const val LOG_LEVEL_WARN = 30

    /** An `ad` / `ao` warning or worse is an audio decoder error; a `vd` one is a video decoder error. */
    fun logSignal(prefix: String, level: Int, count: Int): DecodeSignal? {
        if (level > LOG_LEVEL_WARN) return null
        return when (prefix) {
            "ad", "ao" -> DecodeSignal.AudioDecoderError(count)
            "vd" -> DecodeSignal.VideoDecoderError(count)
            else -> null
        }
    }

    /** True for the modules whose lines are classified; the backend counts only those. */
    fun isDecodeModule(prefix: String): Boolean = prefix == "ad" || prefix == "ao" || prefix == "vd"

    /** `video-params/gamma`: `pq` is HDR10, `hlg` is HLG, any other known transfer is SDR. */
    fun hdr(gamma: String?): StreamHdrCode? = when (gamma?.lowercase()) {
        null, "", "auto" -> null
        "pq" -> StreamHdrCode.HDR10
        "hlg" -> StreamHdrCode.HLG
        else -> StreamHdrCode.SDR
    }
}
