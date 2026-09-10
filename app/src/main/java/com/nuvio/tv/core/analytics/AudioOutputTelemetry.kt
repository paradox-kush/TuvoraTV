package com.nuvio.tv.core.analytics

import com.posthog.PostHog

/**
 * Makes "some channels have no audio" a HogQL query instead of a guess.
 *
 * The field reports (Xiaomi Mi TV especially) are almost always ONE class: a live channel whose audio
 * codec the device can neither decode nor pass through, so the safe-audio -> PCM -> audio-disabled
 * ladder ends at silence while the video keeps playing. Nothing measured which codec, on which sink,
 * under which setting, actually went silent — so the single most diagnostic correlation was invisible.
 *
 * The event pairs the STREAM's audio facts (codec / channels / sample rate) with the DEVICE SINK's
 * real capabilities (which encodings it accepts for passthrough + its max channel count + the output
 * route) and the user's audio SETTINGS, then records the OUTCOME: did audio frames actually render.
 * ExoPlayer surfaces the sink truth via `AudioCapabilities.getCapabilities` (supportedEncodings +
 * maxChannelCount — the exact thing that decides whether E-AC-3/DTS passthrough is even possible), so
 * `codec_in_sink_caps=false` + `audio_rendered=false` for a passthrough codec is the smoking gun.
 *
 * PRIVACY (same rule as [com.nuvio.tv.core.epg.EpgTelemetry] / [LivePlaybackTelemetry]): closed
 * vocabulary only — no URL, host, username, playlist or CHANNEL name, and no raw error message. Codec
 * and encoding names fold to a fixed set; everything else is a number, a boolean, or an enum token.
 */
object AudioOutputTelemetry {

    /** The device audio-sink truth, already reduced to neutral facts by the caller. */
    data class SinkCaps(
        /** Passthrough encodings the sink accepts, normalized (e.g. {"pcm","ac3","eac3"}). */
        val supportedEncodings: Set<String>,
        val maxChannels: Int?,
        /** Where audio is routed: hdmi | hdmi_arc | spdif | speaker | bluetooth | usb | other. */
        val route: String?,
    )

    /**
     * Builds the closed-vocabulary property map for an `audio_output_profile` event. Pure: every input
     * is already a neutral fact, so this is unit-tested without Android, the player, or PostHog.
     *
     * @param audioRendered whether the engine actually rendered audio frames a few seconds in
     *   (null = unknown). false is the direct "silent" signal.
     * @param recoveryStage the audio fallback ladder's stage: none | safe_audio | pcm | disabled.
     * @param audioErrorCode closed-vocab audio failure token, or null.
     */
    fun buildAudioProfile(
        engine: String?,
        rawAudioCodec: String?,
        audioChannels: Int?,
        audioSampleRate: Int?,
        audioTrackCount: Int,
        sink: SinkCaps,
        forceOpticalPassthrough: Boolean,
        decoderPriority: Int,
        audioRendered: Boolean?,
        recoveryStage: String?,
        audioErrorCode: String?,
    ): Map<String, Any> {
        val codec = normalizeAudioCodec(rawAudioCodec)
        return buildMap {
            engine?.let { put("engine", it) }
            put("audio_codec", codec)
            put("has_audio_track", audioTrackCount > 0)
            put("audio_track_count", audioTrackCount)
            audioChannels?.takeIf { it > 0 }?.let { put("audio_channels", it) }
            audioSampleRate?.takeIf { it > 0 }?.let { put("audio_sample_rate", it) }

            // The sink truth — the correlate that decides whether a passthrough codec can be heard.
            put("sink_supported_encodings", sink.supportedEncodings.sorted().joinToString(","))
            sink.maxChannels?.takeIf { it > 0 }?.let { put("sink_max_channels", it) }
            sink.route?.let { put("output_route", it) }
            // Only meaningful for passthrough-class codecs; null (omitted) for softly-decoded ones.
            codecInSinkCaps(codec, sink.supportedEncodings)?.let { put("codec_in_sink_caps", it) }
            audioChannels?.takeIf { it > 0 }?.let { ch ->
                sink.maxChannels?.takeIf { it > 0 }?.let { put("channels_exceed_sink", ch > it) }
            }

            // Settings that steer the audio path (also globally logged, repeated here for self-contained rows).
            put("force_optical_passthrough", forceOpticalPassthrough)
            put("decoder_priority", decoderPriority)

            // Outcome.
            audioRendered?.let { put("audio_rendered", it) }
            recoveryStage?.let { put("audio_recovery_stage", it) }
            audioErrorCode?.let { put("audio_error_code", it) }
            put("likely_silent", recoveryStage == RECOVERY_DISABLED || audioRendered == false)
        }
    }

    /** Emits the event; never throws into the caller. */
    fun audioProfile(
        engine: String?,
        rawAudioCodec: String?,
        audioChannels: Int?,
        audioSampleRate: Int?,
        audioTrackCount: Int,
        sink: SinkCaps,
        forceOpticalPassthrough: Boolean,
        decoderPriority: Int,
        audioRendered: Boolean?,
        recoveryStage: String?,
        audioErrorCode: String?,
    ) {
        runCatching {
            PostHog.capture(
                event = "audio_output_profile",
                properties = buildAudioProfile(
                    engine = engine,
                    rawAudioCodec = rawAudioCodec,
                    audioChannels = audioChannels,
                    audioSampleRate = audioSampleRate,
                    audioTrackCount = audioTrackCount,
                    sink = sink,
                    forceOpticalPassthrough = forceOpticalPassthrough,
                    decoderPriority = decoderPriority,
                    audioRendered = audioRendered,
                    recoveryStage = recoveryStage,
                    audioErrorCode = audioErrorCode,
                ),
            )
        }
    }

    const val RECOVERY_NONE = "none"
    const val RECOVERY_SAFE_AUDIO = "safe_audio"
    const val RECOVERY_PCM = "pcm"
    const val RECOVERY_DISABLED = "disabled"

    /** Passthrough-class codecs whose audibility genuinely depends on sink support. */
    private val PASSTHROUGH_CODECS = setOf("ac3", "eac3", "ac4", "dts", "dts_hd", "truehd")

    /** null when [codec] is softly decoded (sink support irrelevant); else whether the sink accepts it. */
    internal fun codecInSinkCaps(codec: String, supportedEncodings: Set<String>): Boolean? =
        if (codec in PASSTHROUGH_CODECS) codec in supportedEncodings else null

    /**
     * Folds a raw codec or MIME string (mpv's `audio-codec-name`, or ExoPlayer's sampleMimeType /
     * codecs) to a fixed vocabulary. Order matters: E-AC-3 must be tested before AC-3, and DTS-HD
     * before DTS, because the broader token is a substring of the narrower one.
     */
    internal fun normalizeAudioCodec(raw: String?): String {
        val s = raw?.trim()?.lowercase() ?: return "unknown"
        if (s.isEmpty()) return "unknown"
        return when {
            s.contains("eac3") || s.contains("e-ac-3") || s.contains("ec-3") ||
                s.contains("ec3") || s.contains("dd+") || s.contains("ddp") -> "eac3"
            s.contains("truehd") || s.contains("true-hd") || s.contains("mlp") -> "truehd"
            s.contains("ac4") || s.contains("ac-4") -> "ac4"
            s.contains("ac3") || s.contains("ac-3") || s.contains("dolby digital") -> "ac3"
            s.contains("dts-hd") || s.contains("dts_hd") || s.contains("dtshd") -> "dts_hd"
            s.contains("dts") -> "dts"
            s.contains("mp4a") || s.contains("aac") -> "aac"
            // MPEG audio: layer 3 vs layer 2 (many IPTV live streams use MP2).
            s.contains("mp3") || s.contains("mpeg-1-layer-3") || s.contains("layer iii") -> "mp3"
            s.contains("mp2") || s.contains("mp1") || s.contains("layer ii") ||
                s.contains("mpeg-l2") || s.contains("mpeg audio") || s == "mpga" -> "mp2"
            s.contains("opus") -> "opus"
            s.contains("vorbis") -> "vorbis"
            s.contains("flac") -> "flac"
            s.contains("alac") -> "alac"
            s.contains("pcm") || s.contains("raw") || s.contains("lpcm") -> "pcm"
            else -> "other"
        }
    }
}
