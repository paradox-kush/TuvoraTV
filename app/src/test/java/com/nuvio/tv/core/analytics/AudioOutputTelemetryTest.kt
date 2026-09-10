package com.nuvio.tv.core.analytics

import com.nuvio.tv.core.analytics.AudioOutputTelemetry.SinkCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure schema/normalization for the audio-output diagnostic (no Android/player/PostHog). */
class AudioOutputTelemetryTest {

    // --- codec normalization: the narrower token must win over the broader substring ---

    @Test
    fun `e-ac-3 normalizes before ac-3 across mpv and exo spellings`() {
        listOf("eac3", "E-AC-3", "ec-3", "audio/eac3", "DD+", "ddp").forEach {
            assertEquals("eac3 spelling '$it'", "eac3", AudioOutputTelemetry.normalizeAudioCodec(it))
        }
        // Plain AC-3 must NOT be swallowed by the eac3 branch.
        listOf("ac3", "AC-3", "audio/ac3", "Dolby Digital").forEach {
            assertEquals("ac3 spelling '$it'", "ac3", AudioOutputTelemetry.normalizeAudioCodec(it))
        }
    }

    @Test
    fun `ac-4 is its own passthrough codec, not folded into ac-3`() {
        assertEquals("ac4", AudioOutputTelemetry.normalizeAudioCodec("AC-4"))
        assertEquals("ac4", AudioOutputTelemetry.normalizeAudioCodec("ac4"))
        // and it is treated as passthrough-class for the sink-capability derivation
        assertEquals(false, AudioOutputTelemetry.codecInSinkCaps("ac4", setOf("pcm", "ac3", "eac3")))
    }

    @Test
    fun `dts-hd normalizes before dts and mp2 is distinct from mp3`() {
        assertEquals("dts_hd", AudioOutputTelemetry.normalizeAudioCodec("DTS-HD MA"))
        assertEquals("dts", AudioOutputTelemetry.normalizeAudioCodec("dts"))
        assertEquals("mp2", AudioOutputTelemetry.normalizeAudioCodec("audio/mpeg-L2"))
        assertEquals("mp2", AudioOutputTelemetry.normalizeAudioCodec("MP2"))
        assertEquals("mp3", AudioOutputTelemetry.normalizeAudioCodec("mp3"))
    }

    @Test
    fun `common decoded codecs and unknowns fold correctly`() {
        assertEquals("aac", AudioOutputTelemetry.normalizeAudioCodec("audio/mp4a-latm"))
        assertEquals("opus", AudioOutputTelemetry.normalizeAudioCodec("Opus"))
        assertEquals("pcm", AudioOutputTelemetry.normalizeAudioCodec("pcm_s16le"))
        assertEquals("unknown", AudioOutputTelemetry.normalizeAudioCodec(null))
        assertEquals("unknown", AudioOutputTelemetry.normalizeAudioCodec("   "))
        assertEquals("other", AudioOutputTelemetry.normalizeAudioCodec("some-exotic-codec"))
    }

    // --- codec vs sink-capability derivation ---

    @Test
    fun `codec_in_sink_caps is only computed for passthrough-class codecs`() {
        assertEquals(true, AudioOutputTelemetry.codecInSinkCaps("eac3", setOf("pcm", "ac3", "eac3")))
        assertEquals(false, AudioOutputTelemetry.codecInSinkCaps("dts", setOf("pcm", "ac3", "eac3")))
        // A softly-decoded codec's audibility does not depend on sink passthrough -> null (omitted).
        assertNull(AudioOutputTelemetry.codecInSinkCaps("aac", setOf("pcm")))
    }

    // --- the smoking gun: passthrough codec the sink can't take + no rendered audio ---

    @Test
    fun `an unsupported passthrough codec that rendered no audio is flagged likely silent`() {
        val props = AudioOutputTelemetry.buildAudioProfile(
            engine = "exo",
            rawAudioCodec = "audio/eac3",
            audioChannels = 6,
            audioSampleRate = 48_000,
            audioTrackCount = 1,
            sink = SinkCaps(supportedEncodings = setOf("pcm"), maxChannels = 2, route = "speaker"),
            forceOpticalPassthrough = true,
            decoderPriority = 1,
            audioRendered = false,
            recoveryStage = AudioOutputTelemetry.RECOVERY_DISABLED,
            audioErrorCode = "audio_track_init",
        )
        assertEquals("eac3", props["audio_codec"])
        assertEquals(false, props["codec_in_sink_caps"])
        assertEquals(true, props["channels_exceed_sink"])
        assertEquals(false, props["audio_rendered"])
        assertEquals(true, props["likely_silent"])
        assertEquals("pcm", props["sink_supported_encodings"])
        assertEquals("speaker", props["output_route"])
    }

    @Test
    fun `a decoded codec that rendered audio is not flagged silent and omits sink-passthrough fields`() {
        val props = AudioOutputTelemetry.buildAudioProfile(
            engine = "mpv",
            rawAudioCodec = "aac",
            audioChannels = 2,
            audioSampleRate = 48_000,
            audioTrackCount = 2,
            sink = SinkCaps(supportedEncodings = setOf("pcm", "ac3", "eac3"), maxChannels = 8, route = "hdmi"),
            forceOpticalPassthrough = false,
            decoderPriority = 0,
            audioRendered = true,
            recoveryStage = AudioOutputTelemetry.RECOVERY_NONE,
            audioErrorCode = null,
        )
        assertEquals("aac", props["audio_codec"])
        assertNull("aac audibility is decoder-side, not sink passthrough", props["codec_in_sink_caps"])
        assertEquals(false, props["channels_exceed_sink"])
        assertEquals(false, props["likely_silent"])
        assertFalse(props.containsKey("audio_error_code"))
    }

    @Test
    fun `likely_silent is driven by the disabled ladder stage even when render state is unknown`() {
        val props = AudioOutputTelemetry.buildAudioProfile(
            engine = "exo", rawAudioCodec = "dts", audioChannels = null, audioSampleRate = null,
            audioTrackCount = 1,
            sink = SinkCaps(supportedEncodings = setOf("pcm", "ac3"), maxChannels = null, route = null),
            forceOpticalPassthrough = false, decoderPriority = 1,
            audioRendered = null, recoveryStage = AudioOutputTelemetry.RECOVERY_DISABLED, audioErrorCode = null,
        )
        assertEquals(true, props["likely_silent"])
        assertFalse("null channels omitted", props.containsKey("audio_channels"))
        assertFalse("null render state omitted", props.containsKey("audio_rendered"))
    }
}
