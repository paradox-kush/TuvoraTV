package com.nuvio.tv.core.analytics

import com.nuvio.tv.core.analytics.AudioOutputTelemetry.SinkCaps
import com.nuvio.tv.core.analytics.AudioOutputTelemetry.SinkCapsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure schema/normalization/selection for the audio-output diagnostic (no Android/player/PostHog). */
class AudioOutputTelemetryTest {

    private fun caps(
        source: SinkCapsSource,
        encodings: Set<String>?,
        partial: Boolean = false,
        maxChannels: Int? = null,
        route: String? = null,
    ) = SinkCaps(source, encodings, partial, maxChannels, route)

    private fun observation(
        status: AudioStatus,
        progress: AudioPipelineProgress,
        delta: Long? = null,
        error: String? = null,
    ) = AudioObservationResult(
        status = status, progress = progress, eligibleObservationMs = 4_000, bufferingMs = 0,
        renderedBufferDelta = delta, errorCode = error, reason = "r", rearmCount = 0,
    )

    // --- codec normalization: the narrower token must win over the broader substring ---

    @Test
    fun `e-ac-3 normalizes before ac-3 and ac-4 is its own codec`() {
        listOf("eac3", "E-AC-3", "audio/eac3", "DD+").forEach {
            assertEquals("eac3", AudioOutputTelemetry.normalizeAudioCodec(it))
        }
        assertEquals("ac3", AudioOutputTelemetry.normalizeAudioCodec("AC-3"))
        assertEquals("ac4", AudioOutputTelemetry.normalizeAudioCodec("AC-4"))
        assertEquals("dts_hd", AudioOutputTelemetry.normalizeAudioCodec("DTS-HD MA"))
        assertEquals("mp2", AudioOutputTelemetry.normalizeAudioCodec("audio/mpeg-L2"))
        assertEquals("unknown", AudioOutputTelemetry.normalizeAudioCodec(null))
    }

    // --- tri-state codec-vs-sink: unknown/partial must never become a false incompatibility ---

    @Test
    fun `codec_in_sink_caps is true false or unknown but never a false incompatibility`() {
        val known = caps(SinkCapsSource.ACTIVE_ROUTE, setOf("pcm", "ac3", "eac3"))
        assertEquals(true, AudioOutputTelemetry.codecInSinkCaps("eac3", known))
        assertEquals(false, AudioOutputTelemetry.codecInSinkCaps("dts", known))
        // decoded codec -> passthrough support is irrelevant -> unknown
        assertNull(AudioOutputTelemetry.codecInSinkCaps("aac", known))
        // arbitrary/unknown sink caps (null set) -> unknown, NOT false
        assertNull(AudioOutputTelemetry.codecInSinkCaps("eac3", caps(SinkCapsSource.ENUMERATED_DEVICES, null)))
        // partial caps (an unclassified encoding present) -> unknown, NOT false
        assertNull(AudioOutputTelemetry.codecInSinkCaps("eac3", caps(SinkCapsSource.ACTIVE_ROUTE, setOf("pcm"), partial = true)))
    }

    // --- the key correction: missing passthrough support must NOT flag working PCM decode as failure ---

    @Test
    fun `E-AC-3 decoded to PCM on a PCM-only output is progress_observed, not a failure`() {
        val props = AudioOutputTelemetry.buildAudioProfile(
            engine = "exo",
            rawAudioCodec = "audio/eac3",
            audioChannels = 2,
            audioSampleRate = 48_000,
            audioTrackCount = 1,
            selectedDecoder = "c2.android.eac3.decoder",
            outputPath = AudioOutputTelemetry.OUTPUT_PATH_PCM_DECODE,
            audioInputFormat = "eac3",
            recoveryStage = AudioOutputTelemetry.RECOVERY_NONE,
            sink = caps(SinkCapsSource.ACTIVE_ROUTE, setOf("pcm"), maxChannels = 2, route = "speaker"),
            forceOpticalPassthrough = false,
            decoderPriority = 0,
            observation = observation(AudioStatus.PROGRESS_OBSERVED, AudioPipelineProgress.OBSERVED, delta = 800),
        )
        assertEquals("progress_observed", props["audio_status"])
        assertEquals("observed", props["audio_pipeline_progress"])
        // The sink can't PASS THROUGH E-AC-3 (it decoded to PCM) — recorded as a fact, not a verdict.
        assertEquals(false, props["codec_in_sink_caps"])
        assertEquals("pcm_decode", props["audio_output_path"])
        // Crucially: no silence verdict is invented from codec-vs-sink.
        assertFalse("no likely_silent field exists any more", props.containsKey("likely_silent"))
    }

    @Test
    fun `an audio pipeline error is reported as output_error with its code`() {
        val props = AudioOutputTelemetry.buildAudioProfile(
            engine = "exo", rawAudioCodec = "dts", audioChannels = 6, audioSampleRate = 48_000,
            audioTrackCount = 1, selectedDecoder = null, outputPath = AudioOutputTelemetry.OUTPUT_PATH_PASSTHROUGH,
            audioInputFormat = "dts", recoveryStage = AudioOutputTelemetry.RECOVERY_DISABLED,
            sink = caps(SinkCapsSource.ACTIVE_ROUTE, setOf("pcm", "ac3"), maxChannels = 2, route = "speaker"),
            forceOpticalPassthrough = true, decoderPriority = 1,
            observation = observation(AudioStatus.OUTPUT_ERROR, AudioPipelineProgress.NOT_OBSERVED, delta = 0, error = "audio_track_init"),
        )
        assertEquals("output_error", props["audio_status"])
        assertEquals("audio_track_init", props["audio_error_code"])
        assertEquals(true, props["channels_exceed_sink"]) // 6ch stream, 2ch sink
        assertEquals("disabled", props["audio_recovery_stage"])
    }

    @Test
    fun `mpv observation stays unknown and is not a failure`() {
        val props = AudioOutputTelemetry.buildAudioProfile(
            engine = "mpv", rawAudioCodec = "ac3", audioChannels = 2, audioSampleRate = 48_000,
            audioTrackCount = 1, selectedDecoder = null, outputPath = AudioOutputTelemetry.OUTPUT_PATH_UNKNOWN,
            audioInputFormat = null, recoveryStage = null,
            sink = caps(SinkCapsSource.ACTIVE_ROUTE, setOf("pcm"), maxChannels = 2),
            forceOpticalPassthrough = false, decoderPriority = 0,
            observation = observation(AudioStatus.UNKNOWN, AudioPipelineProgress.UNKNOWN),
        )
        assertEquals("unknown", props["audio_status"])
        assertEquals("unknown", props["audio_pipeline_progress"])
    }

    // --- provenance + active-route selection: a connected-but-inactive output must not mislead ---

    @Test
    fun `active route beats a connected but inactive device`() {
        val activeSpeakerPcmOnly = caps(SinkCapsSource.ACTIVE_ROUTE, setOf("pcm"), route = "speaker")
        val enumeratedHdmiSupportsEac3 = caps(SinkCapsSource.ENUMERATED_DEVICES, setOf("pcm", "ac3", "eac3"), route = "hdmi")
        val chosen = AudioOutputTelemetry.selectSinkCaps(activeSpeakerPcmOnly, enumeratedHdmiSupportsEac3)
        assertEquals(SinkCapsSource.ACTIVE_ROUTE, chosen.source)
        // Using the active route, E-AC-3 is NOT passthrough-supported — the inactive HDMI must not lie.
        assertEquals(false, AudioOutputTelemetry.codecInSinkCaps("eac3", chosen))
    }

    @Test
    fun `sink provenance is recorded on the event`() {
        val props = AudioOutputTelemetry.buildAudioProfile(
            engine = "exo", rawAudioCodec = "aac", audioChannels = 2, audioSampleRate = 48_000,
            audioTrackCount = 1, selectedDecoder = "c2.android.aac.decoder",
            outputPath = AudioOutputTelemetry.OUTPUT_PATH_PCM_DECODE, audioInputFormat = "aac", recoveryStage = null,
            sink = caps(SinkCapsSource.ENUMERATED_DEVICES, null, route = "hdmi"), // arbitrary caps
            forceOpticalPassthrough = false, decoderPriority = 0,
            observation = observation(AudioStatus.PROGRESS_OBSERVED, AudioPipelineProgress.OBSERVED, delta = 500),
        )
        assertEquals("enumerated_devices", props["sink_caps_source"])
        assertEquals("unknown", props["sink_supported_encodings"]) // null set -> "unknown", not empty
    }
}
