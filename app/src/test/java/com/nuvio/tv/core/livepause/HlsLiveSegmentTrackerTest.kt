package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.livepause.HlsLiveSegmentTracker.Update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsLiveSegmentTrackerTest {
    private val base = "https://h.example/live/chan/index.m3u8?tok=1"

    private fun playlist(
        first: Long,
        count: Int,
        prefix: String = "s",
        extra: String = "",
        dur: String = "6.000",
        discontinuityAt: Int = -1,
        endlist: Boolean = false,
    ): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        append(extra)
        for (i in 0 until count) {
            if (i == discontinuityAt) append("#EXT-X-DISCONTINUITY\n")
            append("#EXTINF:$dur,\n$prefix${first + i}.ts\n")
        }
        if (endlist) append("#EXT-X-ENDLIST\n")
    }

    private fun news(u: Update) = (u as Update.NewSegments).segments
    private fun seqs(l: List<HlsLiveSegmentTracker.MediaSegment>) = l.map { it.sequence }

    @Test
    fun `the first playlist yields every segment`() {
        val t = HlsLiveSegmentTracker()
        val segs = news(t.onPlaylist(playlist(100, 4), base))
        assertEquals("sequences", listOf(100L, 101L, 102L, 103L), seqs(segs))
        assertEquals("resolved uri", "https://h.example/live/chan/s100.ts", segs[0].uri)
        assertEquals("duration", 6_000L, segs[0].durationMs)
        assertFalse("not a discontinuity", segs[0].discontinuity)
        assertEquals("target", 6_000L, t.targetDurationMs)
    }

    @Test
    fun `the same playlist again is unchanged`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(playlist(100, 4), base)
        assertEquals("unchanged", Update.Unchanged, t.onPlaylist(playlist(100, 4), base))
    }

    @Test
    fun `a sliding window yields only the new segments`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(playlist(100, 4), base)
        val update = t.onPlaylist(playlist(102, 4), base)
        assertEquals("only 104 and 105", listOf(104L, 105L), seqs(news(update)))
        assertEquals("still no resets", 0, t.resetCount)
    }

    @Test
    fun `a gap is accepted and the first segment after it is flagged`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(playlist(100, 4), base)
        val segs = news(t.onPlaylist(playlist(110, 4), base))
        assertEquals(listOf(110L, 111L, 112L, 113L), seqs(segs))
        assertTrue("missed segments are a discontinuity", segs[0].discontinuity)
        assertFalse(segs[1].discontinuity)
    }

    @Test
    fun `numbering that goes far backwards is a sequence reset with a discontinuity first`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(playlist(500, 4), base)
        val update = t.onPlaylist(playlist(7, 4, prefix = "n"), base)
        assertTrue("reset", update is Update.SequenceReset)
        val segs = (update as Update.SequenceReset).segments
        assertEquals(listOf(7L, 8L, 9L, 10L), seqs(segs))
        assertTrue("first carries the discontinuity", segs[0].discontinuity)
        assertFalse(segs[1].discontinuity)
        assertEquals("counted", 1, t.resetCount)
        // and the new numbering is now the baseline: it slides normally afterwards
        assertEquals(listOf(11L), seqs(news(t.onPlaylist(playlist(8, 4, prefix = "n"), base))))
        assertEquals("not counted again", 1, t.resetCount)
    }

    @Test
    fun `a restart inside the overlap is detected by changed uris`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(playlist(100, 4), base)
        // The provider restarted at 100 with different segments: same numbers, different files.
        val update = t.onPlaylist(playlist(100, 4, prefix = "restart"), base)
        assertTrue("reset on uri mismatch, got $update", update is Update.SequenceReset)
        assertEquals(1, t.resetCount)
    }

    @Test
    fun `a rotating token in the query is not a reset`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(playlist(100, 4).replace(".ts", ".ts?t=aaa"), base)
        val update = t.onPlaylist(playlist(100, 4).replace(".ts", ".ts?t=bbb"), base)
        assertEquals("same files", Update.Unchanged, update)
        assertEquals(0, t.resetCount)
    }

    @Test
    fun `resets are counted across the capture`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(playlist(500, 4), base)
        for (p in listOf("a", "b", "c", "d")) t.onPlaylist(playlist(1, 4, prefix = p), base)
        assertEquals("owner fails above 3", 4, t.resetCount)
    }

    @Test
    fun `AES-128 is unsupported but METHOD NONE is fine`() {
        val aes = "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
        val t = HlsLiveSegmentTracker()
        val u = t.onPlaylist(playlist(1, 3, extra = aes), base)
        assertTrue("unsupported, got $u", u is Update.Unsupported)

        val none = HlsLiveSegmentTracker()
        assertEquals("none ok", 3, news(none.onPlaylist(playlist(1, 3, extra = "#EXT-X-KEY:METHOD=NONE\n"), base)).size)
    }

    @Test
    fun `byte ranges and master playlists and garbage are unsupported`() {
        assertTrue(HlsLiveSegmentTracker().onPlaylist(playlist(1, 2, extra = "#EXT-X-BYTERANGE:1000@0\n"), base) is Update.Unsupported)
        assertTrue(HlsLiveSegmentTracker().onPlaylist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", base) is Update.Unsupported)
        assertTrue(HlsLiveSegmentTracker().onPlaylist("<html>nope</html>", base) is Update.Unsupported)
    }

    @Test
    fun `unsupported leaves the tracker untouched`() {
        val t = HlsLiveSegmentTracker()
        t.onPlaylist(playlist(100, 2), base)
        t.onPlaylist(playlist(102, 2, extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n"), base)
        assertEquals("still resumes from 101", listOf(102L, 103L), seqs(news(t.onPlaylist(playlist(102, 2), base))))
    }

    @Test
    fun `EXT-X-MAP gives the init segment resolved against the playlist`() {
        val t = HlsLiveSegmentTracker()
        assertNull("none before", t.initSegmentUri)
        t.onPlaylist(playlist(1, 2, extra = "#EXT-X-MAP:URI=\"init/i.mp4\"\n"), base)
        assertEquals("https://h.example/live/chan/init/i.mp4", t.initSegmentUri)
    }

    @Test
    fun `playlist discontinuity tags are carried on the next segment`() {
        val t = HlsLiveSegmentTracker()
        val segs = news(t.onPlaylist(playlist(1, 4, discontinuityAt = 2), base))
        assertEquals(listOf(false, false, true, false), segs.map { it.discontinuity })
    }

    @Test
    fun `endlist is exposed and fractional durations round to ms`() {
        val t = HlsLiveSegmentTracker()
        val segs = news(t.onPlaylist(playlist(1, 2, dur = "5.0059", endlist = true), base))
        assertTrue(t.ended)
        assertEquals(5_006L, segs[0].durationMs)
    }

    // ---- master

    @Test
    fun `parses a master playlist`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=900000,BANDWIDTH=1200000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low/index.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=50000,URI="iframe.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080

            /abs/hd.m3u8?x=1
        """.trimIndent()
        val v = parseHlsMaster(master, base)
        assertEquals("two variants (the I-frame one is not playable)", 2, v.size)
        assertEquals(LivePausePolicy.HlsVariant("https://h.example/live/chan/low/index.m3u8", 1_200_000, 360), v[0])
        assertEquals(LivePausePolicy.HlsVariant("https://h.example/abs/hd.m3u8?x=1", 5_000_000, 1080), v[1])
    }

    @Test
    fun `a media playlist is not a master`() {
        assertTrue(parseHlsMaster(playlist(1, 3), base).isEmpty())
    }

    // ---- uri resolution

    @Test
    fun `resolves relative absolute root-relative and parent references`() {
        assertEquals("relative", "https://h.example/live/chan/seg1.ts", resolveHlsUri(base, "seg1.ts"))
        assertEquals("absolute", "http://o.example/a.ts", resolveHlsUri(base, "http://o.example/a.ts"))
        assertEquals("root-relative", "https://h.example/x/y.ts", resolveHlsUri(base, "/x/y.ts"))
        assertEquals("parent", "https://h.example/live/seg.ts", resolveHlsUri(base, "../seg.ts"))
        assertEquals("scheme-relative", "https://cdn.example/a.ts", resolveHlsUri(base, "//cdn.example/a.ts"))
        assertEquals("keeps the query", "https://h.example/live/chan/seg.ts?x=1&y=2", resolveHlsUri(base, "seg.ts?x=1&y=2"))
        assertEquals("dot segments", "https://h.example/live/chan/b.ts", resolveHlsUri(base, "./a/../b.ts"))
        assertEquals("above the root stops at the root", "https://h.example/x.ts", resolveHlsUri(base, "../../../../x.ts"))
        assertEquals("query-only", "https://h.example/live/chan/index.m3u8?other=1", resolveHlsUri(base, "?other=1"))
        assertEquals("port and credentials are kept", "http://u:p@h.example:8080/a/b.ts", resolveHlsUri("http://u:p@h.example:8080/a/m.m3u8", "b.ts"))
    }
}
