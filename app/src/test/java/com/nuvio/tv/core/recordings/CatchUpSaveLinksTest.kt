package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.iptv.XtreamCatchUp
import com.nuvio.tv.core.recordings.api.RecordingCatchUp
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.internal.CatchUpSaveLinks as L
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class CatchUpSaveLinksTest {
    private val from = NOW - 2 * HOUR
    private val to = NOW - HOUR

    private fun xt(ext: String?) = channel(catchUp = RecordingCatchUp.Xtream("http://p.example:8080", "user", "pass", 42, 3, ext))

    @Test
    fun `a channel without catch-up has no links`() {
        assertTrue(L.candidates(channel(), from, to, NOW).isEmpty())
        assertEquals(RecordingProblem.NO_CATCH_UP, L.problemFor(emptyList()))
    }

    @Test
    fun `xtream links are the panel's candidates with the ts form first`() {
        val expected = XtreamCatchUp.candidateUrls("http://p.example:8080", "user", "pass", 42, from, to, null, null)
        val got = L.candidates(xt(null), from, to, NOW)
        assertEquals(expected, got)
        assertTrue(got.all(L::isProgressive))
    }

    @Test
    fun `a panel that serves hls puts every progressive link before the playlist ones`() {
        val got = L.candidates(xt("m3u8"), from, to, NOW)
        assertTrue(got.isNotEmpty())
        val firstHls = got.indexOfFirst { !L.isProgressive(it) }
        assertTrue("a progressive link comes first: $got", firstHls > 0)
        assertTrue("no progressive link after the first playlist: $got", got.drop(firstHls).none(L::isProgressive))
    }

    @Test
    fun `each hls link also gets its ts twin`() {
        val got = L.candidates(xt("m3u8"), from, to, NOW)
        val hls = got.filter { it.substringBefore('?').endsWith(".m3u8") && "/timeshift/" in it }
        assertTrue(hls.isNotEmpty())
        for (h in hls) {
            val twin = h.substringBefore('?').removeSuffix(".m3u8") + ".ts"
            assertTrue("ts twin of $h in $got", got.any { it.substringBefore('?') == twin })
        }
    }

    @Test
    fun `the list has no duplicates`() {
        val got = L.candidates(xt("m3u8"), from, to, NOW)
        assertEquals(got.distinct(), got)
    }

    @Test
    fun `blank xtream credentials give no links`() {
        val ch = channel(catchUp = RecordingCatchUp.Xtream("http://p.example:8080", "", "", 42, 3, null))
        assertTrue(L.candidates(ch, from, to, NOW).isEmpty())
    }

    @Test
    fun `an m3u append template is built on the live url and is progressive`() {
        val ch = channel(catchUp = RecordingCatchUp.M3u("append", "?utc={utc}&end={utcend}", 3))
        val got = L.candidates(ch, from, to, NOW)
        assertEquals("${ch.liveUrl}?utc=${from / 1000}&end=${to / 1000}", got.first())
        assertTrue(got.first().let(L::isProgressive))
    }

    @Test
    fun `an m3u type the library cannot build gives no links`() {
        val ch = channel(catchUp = RecordingCatchUp.M3u("mystery", null, 3))
        assertTrue(L.candidates(ch, from, to, NOW).isEmpty())
    }

    @Test
    fun `an m3u flussonic hls link is listed after a ts twin or alone`() {
        val ch = channel(catchUp = RecordingCatchUp.M3u("flussonic", null, 3)).let {
            it.copy(liveUrl = "http://f.example/stream1/index.m3u8")
        }
        val got = L.candidates(ch, from, to, NOW)
        assertTrue(got.isNotEmpty())
        assertTrue(got.any { it.contains(".m3u8") })
        assertEquals(RecordingProblem.UNSUPPORTED_FORMAT, L.problemFor(got.filterNot(L::isProgressive)))
    }

    @Test
    fun `hls only links are an unsupported format and any progressive link is fine`() {
        assertEquals(RecordingProblem.UNSUPPORTED_FORMAT, L.problemFor(listOf("http://a/x.m3u8", "http://a/y.m3u8?token=1")))
        assertNull(L.problemFor(listOf("http://a/x.m3u8", "http://a/x.ts")))
        assertNull(L.problemFor(listOf("http://a/x.mp4")))
    }

    @Test
    fun `progressive means not a playlist`() {
        assertTrue(L.isProgressive("http://a/b/c.ts"))
        assertTrue(L.isProgressive("http://a/b/c.mp4?x=1"))
        assertTrue(L.isProgressive("http://a/b/c"))
        assertTrue(L.isProgressive("http://a/streaming/timeshift.php?stream=1&start=2"))
        assertFalse(L.isProgressive("http://a/b/c.m3u8"))
        assertFalse(L.isProgressive("http://a/b/C.M3U8?token=1"))
        assertFalse(L.isProgressive("http://a/streaming/timeshift.php?stream=1&extension=m3u8"))
        assertTrue("the playlist name in a query value does not count", L.isProgressive("http://a/b/c.ts?next=/x.m3u8"))
    }

    @Test
    fun `resume asks for the rest from 30 seconds before where it got to`() {
        val resumed = L.resumeCandidates(xt(null), from, doneMs = 20 * MIN, toMs = to, nowMs = NOW)
        val expected = L.candidates(xt(null), from + 20 * MIN - 30_000L, to, NOW)
        assertEquals(expected, resumed)
        assertTrue(resumed.isNotEmpty())
    }

    @Test
    fun `resume before any footage starts from the beginning`() {
        assertEquals(L.candidates(xt(null), from, to, NOW), L.resumeCandidates(xt(null), from, 0, to, NOW))
        assertEquals("less than the overlap clamps to the start", L.candidates(xt(null), from, to, NOW), L.resumeCandidates(xt(null), from, 10_000, to, NOW))
    }
}
