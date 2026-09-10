package com.nuvio.tv.core.analytics

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure encoding-int -> codec-vocabulary map (the device-walking read() is thin I/O glue). */
class AudioSinkCapabilitiesTest {

    @Test
    fun `passthrough encodings map to the same vocabulary the codec normalizer uses`() {
        assertEquals("ac3", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_AC3))
        assertEquals("eac3", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_E_AC3))
        // Dolby Digital Plus with Atmos (JOC) is still E-AC-3 for passthrough support purposes.
        assertEquals("eac3", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_E_AC3_JOC))
        assertEquals("dts", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_DTS))
        assertEquals("truehd", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_DOLBY_TRUEHD))
    }

    @Test
    fun `pcm variants collapse to pcm and unknown encodings drop out`() {
        assertEquals("pcm", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_PCM_16BIT))
        assertEquals("pcm", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_PCM_FLOAT))
        assertNull(AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_INVALID))
        assertNull(AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_DEFAULT))
    }

    @Test
    fun `the sink vocabulary lines up with codec_in_sink_caps so a join is valid`() {
        // A channel decoded as E-AC-3 and a sink advertising E-AC-3 must compare equal — same tokens.
        val streamCodec = AudioOutputTelemetry.normalizeAudioCodec("audio/eac3")
        val sinkEncoding = AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_E_AC3)
        assertEquals(streamCodec, sinkEncoding)
        assertEquals(true, AudioOutputTelemetry.codecInSinkCaps(streamCodec, setOf(sinkEncoding!!)))
    }
}
