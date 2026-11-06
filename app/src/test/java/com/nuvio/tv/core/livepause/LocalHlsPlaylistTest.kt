package com.nuvio.tv.core.livepause

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHlsPlaylistTest {
    private fun piece(seq: Long, ms: Long, discontinuity: Boolean = false) =
        LivePiece(seq, "seg$seq.ts", ms, 0, discontinuity)

    @Test
    fun `renders a rewind buffer playlist`() {
        val text = LocalHlsPlaylist.render(
            pieces = listOf(piece(4, 6_000), piece(5, 2_500)),
            targetDurationSec = 20,
            mediaSequence = 4,
            ended = false,
            eventType = false,
            initSegment = null,
        )
        assertEquals(
            "exact text",
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:20\n#EXT-X-MEDIA-SEQUENCE:4\n" +
                "#EXTINF:6.000,\nseg4.ts\n#EXTINF:2.500,\nseg5.ts\n",
            text,
        )
    }

    @Test
    fun `three decimals are hand formatted including zero padding`() {
        val text = LocalHlsPlaylist.render(listOf(piece(0, 6_007), piece(1, 12_050), piece(2, 999)), 20, 0, false, false, null)
        assertTrue(text, text.contains("#EXTINF:6.007,\n"))
        assertTrue(text, text.contains("#EXTINF:12.050,\n"))
        assertTrue(text, text.contains("#EXTINF:0.999,\n"))
    }

    @Test
    fun `discontinuity precedes only the flagged piece`() {
        val text = LocalHlsPlaylist.render(listOf(piece(0, 1_000), piece(1, 1_000, discontinuity = true), piece(2, 1_000)), 20, 0, false, false, null)
        assertEquals("one marker", 1, Regex("#EXT-X-DISCONTINUITY").findAll(text).count())
        assertTrue("before seg1", text.contains("#EXT-X-DISCONTINUITY\n#EXTINF:1.000,\nseg1.ts"))
    }

    @Test
    fun `ended adds ENDLIST and event type adds the playlist type`() {
        val ended = LocalHlsPlaylist.render(listOf(piece(0, 1_000)), 20, 0, ended = true, eventType = true, initSegment = null)
        assertTrue("ENDLIST last", ended.endsWith("#EXT-X-ENDLIST\n"))
        assertTrue("EVENT", ended.contains("#EXT-X-PLAYLIST-TYPE:EVENT\n"))
        val live = LocalHlsPlaylist.render(listOf(piece(0, 1_000)), 20, 0, ended = false, eventType = false, initSegment = null)
        assertFalse("no ENDLIST", live.contains("#EXT-X-ENDLIST"))
        assertFalse("no EVENT", live.contains("PLAYLIST-TYPE"))
    }

    @Test
    fun `an init segment adds the map and raises the version`() {
        val text = LocalHlsPlaylist.render(listOf(piece(0, 1_000)), 20, 0, false, false, "init.mp4")
        assertTrue(text, text.contains("#EXT-X-MAP:URI=\"init.mp4\"\n"))
        assertTrue(text, text.contains("#EXT-X-VERSION:6\n"))
        assertTrue("map before the first segment", text.indexOf("#EXT-X-MAP") < text.indexOf("seg0.ts"))
    }
}
