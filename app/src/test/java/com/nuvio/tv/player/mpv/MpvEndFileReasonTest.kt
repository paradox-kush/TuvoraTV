package com.nuvio.tv.player.mpv

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvEndFileReasonTest {

    @Test
    fun `maps every documented mpv end-file reason`() {
        assertEquals(MpvEndFileReason.EOF, MpvEndFileReason.fromWireValue("eof"))
        assertEquals(MpvEndFileReason.ERROR, MpvEndFileReason.fromWireValue("error"))
        assertEquals(MpvEndFileReason.STOP, MpvEndFileReason.fromWireValue("stop"))
        assertEquals(MpvEndFileReason.QUIT, MpvEndFileReason.fromWireValue("quit"))
        assertEquals(MpvEndFileReason.REDIRECT, MpvEndFileReason.fromWireValue("redirect"))
    }

    @Test
    fun `unknown and absent reasons stay non-retryable`() {
        assertEquals(MpvEndFileReason.UNKNOWN, MpvEndFileReason.fromWireValue("future"))
        assertEquals(MpvEndFileReason.UNKNOWN, MpvEndFileReason.fromWireValue(null))
    }
}
