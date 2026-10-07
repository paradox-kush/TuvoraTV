// Portions of this file are derived from Plezy (https://github.com/edde746/plezy), GPL-3.0,
// lib/services/jellyfin_client/parts/playback.dart (getPlaybackInfo's DeviceProfile) at commit
// 05ef93c55b0a6e8a35683762c2803e1756aaed1c, ported to Kotlin. Tuvora is GPL-3.0 as well.
package com.nuvio.tv.core.mediaserver.client.mediabrowser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `DeviceProfile` Tuvora sends with PlaybackInfo: what its players (ExoPlayer with the FFmpeg decoder
 * extension, mpv) can direct-play and what to fall back to. Direct play the common containers/codecs so the
 * server never transcodes what a player decodes natively; one HLS/ts transcode target (HEVC listed ahead of
 * H.264 so a server allowed to encode HEVC does). Text subtitles are offered `Embed` (read out of the
 * container) and, unless the caller wants them burned in, `External`.
 */
internal object MediaBrowserDeviceProfile {
    private const val DIRECT_PLAY_CONTAINERS = "mp4,mkv,m4v,webm,mov,ts"
    // No mpeg2video: owner decision 2026-10-06. J2 saw black video with no fallback when an MPEG-2 file was direct-played,
    // so such files are left out of the claim and the server transcodes them (the HLS profile below).
    private const val DIRECT_PLAY_VIDEO = "hevc,h264,h265,vp8,vp9,av1,mpeg4"
    private const val DIRECT_PLAY_AUDIO = "aac,mp3,mp2,ac3,eac3,flac,opus,vorbis,dts"
    private const val TRANSCODE_VIDEO = "hevc,h264"
    private const val TRANSCODE_AUDIO = "aac,mp3,ac3,eac3,flac,opus"
    private val embeddedSubtitleFormats = listOf("srt", "ass", "ssa", "vtt", "pgssub", "dvdsub", "dvbsub")
    private val externalSubtitleFormats = listOf("srt", "ass", "ssa", "vtt")

    fun build(maxStreamingBitrate: Long?, burnSubtitles: Boolean): JsonObject = buildJsonObject {
        put("Name", "Tuvora")
        maxStreamingBitrate?.let { put("MaxStreamingBitrate", it) }
        put("CodecProfiles", JsonArray(emptyList()))
        put("TranscodingProfiles", buildJsonArray {
            add(buildJsonObject {
                put("Type", "Video"); put("Container", "ts"); put("Protocol", "hls")
                put("VideoCodec", TRANSCODE_VIDEO); put("AudioCodec", TRANSCODE_AUDIO)
            })
        })
        put("DirectPlayProfiles", buildJsonArray {
            add(buildJsonObject {
                put("Type", "Video"); put("Container", DIRECT_PLAY_CONTAINERS)
                put("VideoCodec", DIRECT_PLAY_VIDEO); put("AudioCodec", DIRECT_PLAY_AUDIO)
            })
        })
        put("SubtitleProfiles", buildJsonArray {
            embeddedSubtitleFormats.forEach { add(buildJsonObject { put("Format", it); put("Method", "Embed") }) }
            if (!burnSubtitles) externalSubtitleFormats.forEach { add(buildJsonObject { put("Format", it); put("Method", "External") }) }
        })
    }

    internal fun jsonString(value: String) = JsonPrimitive(value)
}
