package com.nuvio.tv.playback.mpv

import com.nuvio.tv.playback.core.DecodeSignal
import com.nuvio.tv.playback.core.StreamHdrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvDecodeHealthTest {
    @Test
    fun `ad and ao warnings are audio decoder errors and vd is a video decoder error`() {
        assertEquals(DecodeSignal.AudioDecoderError(2), MpvDecodeHealth.logSignal("ad", WARN, 2))
        assertEquals(DecodeSignal.AudioDecoderError(1), MpvDecodeHealth.logSignal("ao", ERROR, 1))
        assertEquals(DecodeSignal.VideoDecoderError(1), MpvDecodeHealth.logSignal("vd", FATAL, 1))
    }

    @Test
    fun `info and verbose lines and other modules are not decoder errors`() {
        assertNull("info is not a warning", MpvDecodeHealth.logSignal("ad", INFO, 1))
        assertNull(MpvDecodeHealth.logSignal("vo", WARN, 1))
        assertNull(MpvDecodeHealth.logSignal("ffmpeg", WARN, 1))
        assertNull(MpvDecodeHealth.logSignal("cplayer", ERROR, 1))
    }

    @Test
    fun `only the three decode modules are counted`() {
        assertEquals(listOf(true, true, true), listOf("ad", "vd", "ao").map(MpvDecodeHealth::isDecodeModule))
        assertEquals(listOf(false, false), listOf("ffmpeg", "stream").map(MpvDecodeHealth::isDecodeModule))
    }

    @Test
    fun `gamma maps to an hdr class and unknown stays unknown`() {
        assertEquals(StreamHdrCode.HDR10, MpvDecodeHealth.hdr("pq"))
        assertEquals(StreamHdrCode.HLG, MpvDecodeHealth.hdr("HLG"))
        assertEquals(StreamHdrCode.SDR, MpvDecodeHealth.hdr("bt.1886"))
        assertNull(MpvDecodeHealth.hdr(""))
        assertNull(MpvDecodeHealth.hdr("auto"))
        assertNull(MpvDecodeHealth.hdr(null))
    }

    private companion object {
        // mpv client.h: FATAL 10, ERROR 20, WARN 30, INFO 40.
        const val FATAL = 10
        const val ERROR = 20
        const val WARN = 30
        const val INFO = 40
    }
}
