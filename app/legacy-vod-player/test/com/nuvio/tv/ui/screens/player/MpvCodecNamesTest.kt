package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvCodecNamesTest {

    @Test
    fun `maps mpv video codecs to the ExoPlayer display names`() {
        assertEquals("H.264", MpvCodecNames.display("h264"))
        assertEquals("HEVC", MpvCodecNames.display("hevc"))
        assertEquals("AV1", MpvCodecNames.display("av1"))
        assertEquals("MPEG-2", MpvCodecNames.display("mpeg2video"))
    }

    @Test
    fun `maps mpv audio codecs to the ExoPlayer display names`() {
        assertEquals("E-AC-3", MpvCodecNames.display("eac3"))
        assertEquals("AC-3", MpvCodecNames.display("ac3"))
        assertEquals("TrueHD", MpvCodecNames.display("truehd"))
        assertEquals("AAC", MpvCodecNames.display("aac"))
    }

    @Test
    fun `is idempotent so an already-formatted name survives a second pass`() {
        // buildStreamInfoData can re-format a value applyMpvVideoSnapshot already mapped.
        listOf("HEVC", "H.264", "AV1", "E-AC-3", "TrueHD", "AAC", "DTS-HD").forEach { name ->
            assertEquals(name, MpvCodecNames.display(name))
        }
    }

    @Test
    fun `upper-cases unknown codecs instead of dropping them`() {
        assertEquals("PRORES", MpvCodecNames.display("prores"))
    }

    @Test
    fun `treats missing and blank codecs as absent`() {
        assertNull(MpvCodecNames.display(null))
        assertNull(MpvCodecNames.display(""))
        assertNull(MpvCodecNames.display("   "))
    }
}
