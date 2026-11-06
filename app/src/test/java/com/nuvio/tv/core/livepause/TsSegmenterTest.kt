package com.nuvio.tv.core.livepause

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class TsSegmenterTest {
    private val store = FakeLiveSegmentStore()
    private var now = 0L
    private val emitted = mutableListOf<LivePiece>()

    private fun segmenter(keepAll: Boolean = false) =
        TsSegmenter(store, monotonicMs = { now }, keepAll = keepAll, onPiece = { emitted += it })

    private fun TsSegmenter.feed(bytes: ByteArray) = write(bytes, 0, bytes.size)

    private fun durations() = emitted.map { it.durationMs }

    // ---- sync

    @Test
    fun `junk before the first packet and arbitrary split writes cut the same pieces as a clean feed`() {
        val bytes = Ts.concat(ByteArray(77), Ts.tables(), Ts.stream(0, 14_000))

        val clean = segmenter().also { it.feed(bytes) }
        val cleanDurations = durations()

        // Same bytes, delivered in awkward 100-byte chunks.
        val split = FakeLiveSegmentStore()
        val splitEmitted = mutableListOf<LivePiece>()
        val s2 = TsSegmenter(split, monotonicMs = { now }, onPiece = { splitEmitted += it })
        var i = 0
        while (i < bytes.size) { val n = minOf(100, bytes.size - i); s2.write(bytes, i, n); i += n }

        assertEquals("first ~2 s then ~6 s", listOf(2_000L, 6_000L, 6_000L), cleanDurations)
        assertEquals("split writes cut identically", cleanDurations, splitEmitted.map { it.durationMs })
        assertTrue("clean feed produced pieces", clean.state().pieces.isNotEmpty())
    }

    @Test
    fun `a PCR that wraps past 33 bits mid-stream still cuts by stream time`() {
        // 2^33 ticks of 90 kHz = 95_443_717 ms; start 3 s before the wrap so a piece straddles it.
        val wrapMs = (1L shl 33) / 90
        segmenter().feed(Ts.concat(Ts.tables(), Ts.stream(wrapMs - 3_000, wrapMs + 17_000)))
        assertEquals("durations across the PCR wrap", listOf(2_000L, 6_000L, 6_000L, 6_000L), durations())
    }

    @Test
    fun `nothing is written before the program tables have been seen`() {
        segmenter().feed(Ts.stream(0, 5_000))
        assertTrue("no piece opened", store.opened.isEmpty())
    }

    // ---- cutting

    @Test
    fun `the first piece is about two seconds and the rest about the target`() {
        segmenter().feed(Ts.concat(Ts.tables(), Ts.stream(0, 20_000)))
        assertEquals("durations", listOf(2_000L, 6_000L, 6_000L, 6_000L), durations())
        assertEquals("sequences count up from 0", listOf(0L, 1L, 2L, 3L), emitted.map { it.sequence })
        assertEquals("names", listOf("seg0.ts", "seg1.ts", "seg2.ts", "seg3.ts"), emitted.map { it.name })
    }

    @Test
    fun `every piece starts with the PAT then the PMT`() {
        segmenter().feed(Ts.concat(Ts.tables(), Ts.stream(0, 20_000)))
        assertTrue("several pieces", emitted.size >= 3)
        for (piece in emitted) {
            val bytes = store.bytesOf(piece.name)
            assertEquals("${piece.name} is whole packets", 0, bytes.size % 188)
            assertEquals("${piece.name} starts with the PAT", 0, Ts.pidOf(Ts.packetAt(bytes, 0)))
            assertEquals("${piece.name} then the PMT", Ts.PMT_PID, Ts.pidOf(Ts.packetAt(bytes, 1)))
        }
    }

    @Test
    fun `the tables are not duplicated in the first piece`() {
        segmenter().feed(Ts.concat(Ts.tables(), Ts.stream(0, 3_000)))
        val first = store.bytesOf("seg0.ts")
        assertEquals("third packet is video, not a second PMT", Ts.VIDEO_PID, Ts.pidOf(Ts.packetAt(first, 2)))
    }

    @Test
    fun `a piece is cut cleanly on a random-access packet and starts with it`() {
        // Random access only every 3 s; PCR every 0.5 s.
        segmenter().feed(Ts.concat(Ts.tables(), Ts.stream(0, 10_000, rai = { it % 3_000 == 0L })))
        assertEquals("cuts only where a picture starts", listOf(3_000L, 6_000L), durations().take(2))
        val second = store.bytesOf("seg1.ts")
        val firstVideo = Ts.packetAt(second, 2)
        assertTrue("the new piece begins on the random-access packet", (firstVideo[5].toInt() and 0x40) != 0)
    }

    @Test
    fun `without random access a piece is force cut at twice the target on a PCR packet start`() {
        segmenter().feed(Ts.concat(Ts.tables(), Ts.stream(0, 16_000, rai = { false }, pusi = { true })))
        assertEquals("first forced at 2x2 s, next at 2x6 s", listOf(4_000L, 12_000L), durations())
    }

    @Test
    fun `with nothing to cut on a piece is still cut at three times the target`() {
        segmenter().feed(Ts.concat(Ts.tables(), Ts.stream(0, 6_000, rai = { false }, pusi = { false })))
        assertEquals("3x the first target", listOf(6_000L), durations())
    }

    // ---- clocks

    @Test
    fun `lengths come from the PCR, never the wall clock`() {
        val s = segmenter()
        s.feed(Ts.tables())
        var t = 0L
        while (t <= 8_000) {
            now += 3_600_000L // the clock lies by an hour per packet
            s.feed(Ts.video(t, rai = true))
            t += 500
        }
        assertEquals("PCR durations", listOf(2_000L, 6_000L), durations())
    }

    @Test
    fun `without a PCR the injected monotonic clock times the piece`() {
        val s = segmenter()
        now = 0
        s.feed(Ts.tables())
        s.feed(Ts.video(null, rai = true)) // opens at clock 0
        now = 1_000
        s.feed(Ts.video(null, rai = true)) // 1 s: below the 2 s first target
        now = 2_500
        s.feed(Ts.video(null, rai = true)) // 2.5 s: clean cut
        assertEquals("clock duration", listOf(2_500L), durations())
    }

    // ---- reconnect / finish

    @Test
    fun `after a reconnect the next piece is flagged as a discontinuity and its playlist entry says so`() {
        val s = segmenter()
        s.feed(Ts.concat(Ts.tables(), Ts.stream(0, 3_000)))
        s.reconnected()
        s.feed(Ts.stream(0, 3_000)) // the provider's clock restarted
        s.finish(ended = false)

        assertEquals("piece 0, the one the reconnect closed, and the one after the gap", 3, emitted.size)
        assertFalse("first piece is not a discontinuity", emitted[0].discontinuity)
        assertFalse("the piece the reconnect closed is not either", emitted[1].discontinuity)
        val after = emitted.last()
        assertTrue("piece after the gap", after.discontinuity)
        val playlist = store.lastPlaylist
        val marker = playlist.indexOf("#EXT-X-DISCONTINUITY")
        assertTrue("marker present", marker >= 0)
        assertTrue("marker precedes the flagged piece", marker < playlist.indexOf(after.name))
        assertEquals("only one marker", 1, Regex("#EXT-X-DISCONTINUITY").findAll(playlist).count())
    }

    @Test
    fun `a reconnect before any piece exists does not flag a discontinuity`() {
        val s = segmenter()
        s.reconnected()
        s.feed(Ts.concat(Ts.tables(), Ts.stream(0, 3_000)))
        s.finish()
        assertFalse("nothing before it", emitted.first().discontinuity)
    }

    @Test
    fun `finish closes the open piece and ends the playlist`() {
        val s = segmenter()
        s.feed(Ts.concat(Ts.tables(), Ts.stream(0, 1_000)))
        assertTrue("no piece closed yet", emitted.isEmpty())
        s.finish()
        assertEquals("the open piece was closed and emitted", 1, emitted.size)
        assertTrue("writer closed", store.closed.contains("seg0.ts"))
        assertTrue("ENDLIST", store.lastPlaylist.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun `an unfinished playlist has no ENDLIST`() {
        segmenter().feed(Ts.concat(Ts.tables(), Ts.stream(0, 3_000)))
        assertFalse(store.lastPlaylist.contains("#EXT-X-ENDLIST"))
    }

    // ---- playlist

    @Test
    fun `a rewind buffer is not an EVENT playlist but keepAll is`() {
        segmenter(keepAll = false).feed(Ts.concat(Ts.tables(), Ts.stream(0, 3_000)))
        assertFalse("rewind", store.lastPlaylist.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))

        val recording = FakeLiveSegmentStore()
        val b = Ts.concat(Ts.tables(), Ts.stream(0, 3_000))
        TsSegmenter(recording, monotonicMs = { now }, keepAll = true).write(b, 0, b.size)
        assertTrue("recording", recording.lastPlaylist.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
    }

    @Test
    fun `target duration is steady at twenty seconds`() {
        segmenter().feed(Ts.concat(Ts.tables(), Ts.stream(0, 20_000)))
        val targets = store.playlists.map { Regex("TARGETDURATION:(\\d+)").find(it)!!.groupValues[1] }.toSet()
        assertEquals("one value across every rewrite", setOf("20"), targets)
    }

    // ---- retention

    @Test
    fun `dropOldest deletes the files, advances the media sequence and counts the trimmed time`() {
        val s = segmenter()
        s.feed(Ts.concat(Ts.tables(), Ts.stream(0, 26_000)))
        assertEquals("setup: five pieces closed", 5, emitted.size)

        s.dropOldest(2)

        assertEquals("oldest two deleted", listOf("seg0.ts", "seg1.ts"), store.deleted)
        val st = s.state()
        assertEquals("remaining pieces", listOf("seg2.ts", "seg3.ts", "seg4.ts"), st.pieces.map { it.name })
        assertEquals("trimmed = 2 s + 6 s", 8_000L, st.trimmedMs)
        assertEquals("total = what is left", 18_000L, st.totalMs)
        assertTrue("playlist rewritten with the new media sequence", store.lastPlaylist.contains("#EXT-X-MEDIA-SEQUENCE:2"))
        assertFalse("deleted pieces are gone from the playlist", store.lastPlaylist.contains("seg0.ts"))
    }

    @Test
    fun `the playlist is rewritten before the files go away`() {
        val order = mutableListOf<String>()
        val ordered = object : LiveSegmentStore by store {
            override fun delete(name: String) { order += "delete $name"; store.delete(name) }
            override fun writePlaylistAtomically(text: String) { order += "playlist"; store.writePlaylistAtomically(text) }
        }
        val s = TsSegmenter(ordered, { now })
        val b = Ts.concat(Ts.tables(), Ts.stream(0, 14_000))
        s.write(b, 0, b.size)
        order.clear()
        s.dropOldest(1)
        assertEquals("playlist first", listOf("playlist", "delete seg0.ts"), order)
    }

    @Test
    fun `dropOldest with zero or more than exist is safe`() {
        val s = segmenter()
        s.feed(Ts.concat(Ts.tables(), Ts.stream(0, 14_000)))
        s.dropOldest(0)
        assertTrue("nothing deleted", store.deleted.isEmpty())
        s.dropOldest(99)
        assertEquals("only the existing pieces deleted", emitted.map { it.name }, store.deleted)
    }

    @Test
    fun `state reports total and trimmed time`() {
        val s = segmenter()
        s.feed(Ts.concat(Ts.tables(), Ts.stream(0, 14_000)))
        assertEquals("total", 14_000L, s.state().totalMs)
        assertEquals("nothing trimmed", 0L, s.state().trimmedMs)
        assertEquals("pieces", emitted, s.state().pieces)
    }

    // ---- failure

    @Test
    fun `a store write failure propagates to the caller`() {
        store.failWritesOn = "seg0.ts"
        val s = segmenter()
        try {
            s.feed(Ts.concat(Ts.tables(), Ts.stream(0, 3_000)))
            fail("the IOException must reach the owner")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertTrue("no piece was reported as saved", emitted.isEmpty())
    }
}
