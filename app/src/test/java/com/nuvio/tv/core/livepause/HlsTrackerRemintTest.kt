package com.nuvio.tv.core.livepause

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTrackerRemintTest {
    private fun playlist(from: Int, to: Int, dir: String) = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$from\n")
        for (i in from..to) append("#EXTINF:2.000,\n$dir/seg$i.ts?t=abc\n")
    }

    @Test
    fun `a re-minted playlist whose token sits in the path continues the stream instead of resetting it`() {
        val tracker = HlsLiveSegmentTracker()
        tracker.onPlaylist(playlist(10, 12, "/hls/tokenA"), "http://h/index.m3u8")

        // The reconnect minted a fresh link: same segments, a new token directory.
        val update = tracker.onPlaylist(playlist(11, 13, "/hls/tokenB"), "http://h/index.m3u8")

        assertTrue("only the new segment is fetched: $update", update is HlsLiveSegmentTracker.Update.NewSegments)
        assertEquals(listOf(13L), (update as HlsLiveSegmentTracker.Update.NewSegments).segments.map { it.sequence })
        assertEquals("a token change is not a restart", 0, tracker.resetCount)
    }

    @Test
    fun `four re-mints do not exhaust the reset budget`() {
        val tracker = HlsLiveSegmentTracker()
        tracker.onPlaylist(playlist(10, 12, "/t0"), "http://h/index.m3u8")
        for (n in 1..4) tracker.onPlaylist(playlist(10 + n, 12 + n, "/t$n"), "http://h/index.m3u8")
        assertEquals(0, tracker.resetCount)
    }

    @Test
    fun `a real restart that reuses the numbers with different files is still a reset`() {
        val tracker = HlsLiveSegmentTracker()
        tracker.onPlaylist(playlist(10, 12, "/a"), "http://h/index.m3u8")
        val restarted = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:10\n" +
            (10..12).joinToString("") { "#EXTINF:2.000,\n/a/new$it.ts\n" }
        val update = tracker.onPlaylist(restarted, "http://h/index.m3u8")
        assertTrue(update is HlsLiveSegmentTracker.Update.SequenceReset)
    }
}
