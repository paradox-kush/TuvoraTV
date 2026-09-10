package com.nuvio.tv.core.analytics

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure encoding classification (the device-walking readEnumerated() is thin I/O glue). */
class AudioSinkCapabilitiesTest {

    @Test
    fun `known encoding ints fold to the codec vocabulary`() {
        assertEquals("ac3", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_AC3))
        assertEquals("eac3", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_E_AC3))
        assertEquals("eac3", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_E_AC3_JOC))
        assertEquals("dts", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_DTS))
        assertEquals("pcm", AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_PCM_16BIT))
        assertNull(AudioSinkCapabilities.encodingName(AudioFormat.ENCODING_INVALID))
    }

    // --- Android-documented empty-array semantics: empty = ARBITRARY (unknown), never "none" ---

    @Test
    fun `an empty encodings array means arbitrary support, recorded as unknown not empty`() {
        val (encodings, partial) = AudioSinkCapabilities.classifyEncodings(intArrayOf())
        assertNull("empty array = supports arbitrary encodings per docs -> unknown", encodings)
        assertEquals(false, partial)
    }

    @Test
    fun `a null encodings array is also unknown`() {
        val (encodings, _) = AudioSinkCapabilities.classifyEncodings(null)
        assertNull(encodings)
    }

    @Test
    fun `an unclassified future encoding marks the set partial rather than dropping silently`() {
        val futureEncoding = 999_999
        val (encodings, partial) = AudioSinkCapabilities.classifyEncodings(
            intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, futureEncoding),
        )
        assertEquals(setOf("pcm", "ac3"), encodings)
        assertTrue("an unknown encoding must not be read as proof of non-support", partial)
    }

    @Test
    fun `an empty channel-counts array is unknown, not zero`() {
        assertNull(AudioSinkCapabilities.classifyMaxChannels(intArrayOf()))
        assertNull(AudioSinkCapabilities.classifyMaxChannels(null))
        assertEquals(8, AudioSinkCapabilities.classifyMaxChannels(intArrayOf(2, 6, 8)))
    }

    @Test
    fun `the classified vocabulary lines up with codec_in_sink_caps so a join is valid`() {
        val (encodings, _) = AudioSinkCapabilities.classifyEncodings(
            intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_E_AC3),
        )
        val caps = AudioOutputTelemetry.SinkCaps(
            AudioOutputTelemetry.SinkCapsSource.ENUMERATED_DEVICES, encodings, false, null, "hdmi",
        )
        assertEquals(true, AudioOutputTelemetry.codecInSinkCaps("eac3", caps))
        assertEquals(false, AudioOutputTelemetry.codecInSinkCaps("dts", caps))
    }
}
