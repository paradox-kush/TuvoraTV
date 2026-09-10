package com.nuvio.tv.core.analytics

import com.posthog.PostHog

/**
 * Makes "some channels have no audio" a HogQL query instead of a guess — while keeping observed facts
 * strictly separate from diagnostic hypotheses. This event reports what was OBSERVED (the stream's
 * audio codec, the effective output path + selected decoder, the device sink's capabilities and their
 * provenance, the user's audio settings, and whether the pipeline made progress); it does NOT assert
 * that audio was audible or that a given codec caused silence. Correlating these facts across many
 * devices can reveal a pattern; establishing causation is a separate step.
 *
 * Two semantic guarantees this schema keeps:
 *  - Status is EVIDENCE-BASED ([AudioObservationResult]) — driven by observed pipeline progress and
 *    errors, never by codec-versus-sink capability. Missing passthrough support for a codec the app
 *    decodes to PCM is NOT a failure.
 *  - "unknown" is never conflated with "false": an engine that cannot report progress (mpv), or a sink
 *    whose capabilities are unknown/arbitrary, is recorded as unknown, not as an incompatibility.
 *
 * PRIVACY (same rule as [com.nuvio.tv.core.epg.EpgTelemetry] / [LivePlaybackTelemetry]): closed
 * vocabulary — no URL, host, username, playlist or CHANNEL name, and no raw error message. Codec,
 * encoding and decoder names fold to fixed tokens; everything else is a number, boolean, or enum.
 */
object AudioOutputTelemetry {

    /**
     * Where the sink capabilities came from. [DEFAULT_ROUTE_PROBE] is media3
     * `AudioCapabilities.getCapabilities(routedDevice = null)` — the DEFAULT route, an authoritative
     * answer for "can the default route pass this encoding through" but NOT proof of the actual routed
     * device (we do not observe routing). [ENUMERATED_DEVICES] is a connected output from
     * [android.media.AudioDeviceInfo], likewise not confirmed to be the active route.
     */
    enum class SinkCapsSource { DEFAULT_ROUTE_PROBE, ENUMERATED_DEVICES, UNKNOWN }

    /** Provenance of [audio_output_path]. No public runtime API exposes the AudioSink's configured
     *  output mode in the shipped fork, so it is INFERRED (from an active MediaCodec audio decoder =
     *  PCM decode) or UNKNOWN — never asserted as observed runtime configuration. */
    const val OUTPUT_PATH_SOURCE_RUNTIME = "runtime"
    const val OUTPUT_PATH_SOURCE_INFERRED = "inferred"
    const val OUTPUT_PATH_SOURCE_UNKNOWN = "unknown"

    /** The device audio-sink truth, already reduced to neutral facts, with its provenance. */
    data class SinkCaps(
        val source: SinkCapsSource,
        /**
         * Passthrough encodings the sink accepts, normalized. null = UNKNOWN/arbitrary — e.g. an empty
         * [android.media.AudioDeviceInfo.getEncodings] array, which Android documents as "the device
         * supports arbitrary encodings", NOT "supports none". Absence of a codec here is only evidence
         * of non-support when this is non-null AND [hasUnclassifiedEncodings] is false.
         */
        val supportedEncodings: Set<String>?,
        /** The sink reported encoding ints we do not classify, so the set is partial. */
        val hasUnclassifiedEncodings: Boolean = false,
        /** null = unknown/arbitrary (an empty channel-counts array, per Android docs). */
        val maxChannels: Int?,
        val route: String?,
    )

    /**
     * Builds the closed-vocabulary property map. Pure: every input is a neutral fact so this is
     * unit-tested without Android, the player, or PostHog. [observation] is the evidence-based
     * outcome; the sink/codec fields are separate OBSERVED facts and never determine the status.
     */
    fun buildAudioProfile(
        engine: String?,
        rawAudioCodec: String?,
        audioChannels: Int?,
        audioSampleRate: Int?,
        audioTrackCount: Int,
        selectedDecoder: String?,
        outputPath: String?,
        outputPathSource: String?,
        audioInputFormat: String?,
        recoveryStage: String?,
        sink: SinkCaps,
        forceOpticalPassthrough: Boolean,
        decoderPriority: Int,
        observation: AudioObservationResult?,
    ): Map<String, Any> {
        val codec = normalizeAudioCodec(rawAudioCodec)
        return buildMap {
            engine?.let { put("engine", it) }

            // --- observed stream facts ---
            put("audio_codec", codec)
            put("has_audio_track", audioTrackCount > 0)
            put("audio_track_count", audioTrackCount)
            audioChannels?.takeIf { it > 0 }?.let { put("audio_channels", it) }
            audioSampleRate?.takeIf { it > 0 }?.let { put("audio_sample_rate", it) }
            selectedDecoder?.takeIf { it.isNotBlank() }?.let { put("selected_decoder", it) }
            outputPath?.let { put("audio_output_path", it) } // pcm_decode | passthrough | offload | unknown
            outputPathSource?.let { put("audio_output_path_source", it) } // runtime | inferred | unknown
            audioInputFormat?.takeIf { it.isNotBlank() }?.let { put("audio_input_format", it) }
            recoveryStage?.let { put("audio_recovery_stage", it) }

            // --- observed device sink facts, with provenance ---
            put("sink_caps_source", sink.source.name.lowercase())
            put("sink_supported_encodings", sink.supportedEncodings?.sorted()?.joinToString(",") ?: "unknown")
            put("sink_encodings_partial", sink.hasUnclassifiedEncodings)
            put("sink_max_channels", sink.maxChannels?.takeIf { it > 0 }?.toString() ?: "unknown")
            sink.route?.let { put("output_route", it) }
            // Tri-state: true/false only when the sink caps are KNOWN and complete; omitted (unknown)
            // otherwise, and omitted for softly-decoded codecs whose audibility is not a passthrough fact.
            codecInSinkCaps(codec, sink)?.let { put("codec_in_sink_caps", it) }
            if (sink.maxChannels != null && sink.maxChannels > 0 && audioChannels != null && audioChannels > 0) {
                put("channels_exceed_sink", audioChannels > sink.maxChannels)
            }

            // --- settings that steer the audio path ---
            put("force_optical_passthrough", forceOpticalPassthrough)
            put("decoder_priority", decoderPriority)

            // --- evidence-based outcome (never derived from codec x sink) ---
            observation?.let { obs ->
                put("audio_status", obs.status.name.lowercase())
                put("audio_status_reason", obs.reason)
                put("audio_pipeline_progress", obs.progress.name.lowercase())
                put("eligible_observation_ms", obs.eligibleObservationMs)
                put("buffering_ms", obs.bufferingMs)
                obs.renderedBufferDelta?.let { put("rendered_buffer_delta", it) }
                obs.errorCode?.let { put("audio_error_code", it) }
                put("observation_rearms", obs.rearmCount)
            }
        }
    }

    /** Emits the event; never throws into the caller. Consent + drop rules are enforced by PostHog. */
    fun audioProfile(
        engine: String?,
        rawAudioCodec: String?,
        audioChannels: Int?,
        audioSampleRate: Int?,
        audioTrackCount: Int,
        selectedDecoder: String?,
        outputPath: String?,
        outputPathSource: String?,
        audioInputFormat: String?,
        recoveryStage: String?,
        sink: SinkCaps,
        forceOpticalPassthrough: Boolean,
        decoderPriority: Int,
        observation: AudioObservationResult?,
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
                    selectedDecoder = selectedDecoder,
                    outputPath = outputPath,
                    outputPathSource = outputPathSource,
                    audioInputFormat = audioInputFormat,
                    recoveryStage = recoveryStage,
                    sink = sink,
                    forceOpticalPassthrough = forceOpticalPassthrough,
                    decoderPriority = decoderPriority,
                    observation = observation,
                ),
            )
        }
    }

    const val RECOVERY_NONE = "none"
    const val RECOVERY_SAFE_AUDIO = "safe_audio"
    const val RECOVERY_PCM = "pcm"
    const val RECOVERY_DISABLED = "disabled"

    const val OUTPUT_PATH_PCM_DECODE = "pcm_decode"
    const val OUTPUT_PATH_PASSTHROUGH = "passthrough"
    const val OUTPUT_PATH_OFFLOAD = "offload"
    const val OUTPUT_PATH_UNKNOWN = "unknown"

    /** Passthrough-class codecs whose audibility genuinely depends on sink passthrough support. */
    private val PASSTHROUGH_CODECS = setOf("ac3", "eac3", "ac4", "dts", "dts_hd", "truehd")

    /**
     * Tri-state. null (unknown) when: the codec is softly decoded (sink passthrough is irrelevant), OR
     * the sink capabilities are unknown/arbitrary (null set), OR the sink reported encodings we could
     * not classify (partial set) — so we never flag a false incompatibility. true/false only when the
     * codec is passthrough-class AND the sink caps are known and complete.
     */
    /**
     * Chooses the more trustworthy capability answer. The DEFAULT-ROUTE PROBE wins: it reflects what
     * the platform's default output route can pass through, so a connected-but-inactive enumerated
     * device (e.g. an HDMI receiver the TV is not currently routing to) cannot make a codec look
     * supported. Neither source is a VERIFIED active route — actual routing is not observed — so the
     * provenance travels with the result. Falls back to enumerated, then to an UNKNOWN sentinel.
     */
    fun selectSinkCaps(defaultRouteProbe: SinkCaps?, enumerated: SinkCaps?): SinkCaps = when {
        defaultRouteProbe != null && defaultRouteProbe.source == SinkCapsSource.DEFAULT_ROUTE_PROBE -> defaultRouteProbe
        enumerated != null && enumerated.source != SinkCapsSource.UNKNOWN -> enumerated
        else -> SinkCaps(SinkCapsSource.UNKNOWN, supportedEncodings = null, maxChannels = null, route = null)
    }

    internal fun codecInSinkCaps(codec: String, sink: SinkCaps): Boolean? {
        if (codec !in PASSTHROUGH_CODECS) return null
        val encodings = sink.supportedEncodings ?: return null
        if (sink.hasUnclassifiedEncodings) return null
        return codec in encodings
    }

    /**
     * Folds a raw codec or MIME string (mpv's `audio-codec-name`, or ExoPlayer's sampleMimeType /
     * codecs) to a fixed vocabulary. Order matters: E-AC-3 before AC-3, DTS-HD before DTS, MP3 before
     * MP2, because the broader token is a substring of the narrower one.
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
