package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreObservationsTest {
    private fun fx(id: String, status: String? = null, h: String? = null, a: String? = null) =
        RadarFixture(id = id, leagueId = "L", sport = "Soccer", home = "H", away = "A", status = status, homeScore = h, awayScore = a)
    private fun live(id: String, h: String, a: String) = RadarLiveScore(eventId = id, status = "1H", homeScore = h, awayScore = a)
    private val d30 = ScoreSpoilerSettings(shown = true, delaySeconds = 30)

    @Test
    fun `a live game is delayed from the first live sight`() {
        val o = ScoreObservations()
        o.note(listOf(fx("e1")), listOf(live("e1", "0", "0")), 0L)
        o.note(emptyList(), listOf(live("e1", "1", "0")), 20_000L)
        assertEquals("pending first", ScoreView.ScorePending, o.scoreView(d30, "e1", live("e1", "1", "0"), 10_000L))
        assertEquals("old value", ScoreView.ScoreShown(live("e1", "0", "0")), o.scoreView(d30, "e1", live("e1", "1", "0"), 40_000L))
    }

    @Test
    fun `a game final at first sight is shown at once`() {
        val o = ScoreObservations()
        o.note(listOf(fx("e2", status = "Match Finished", h = "2", a = "1")), emptyList(), 0L)
        assertEquals(ScoreView.ScoreShown(null), o.scoreView(d30, "e2", null, 1L))
    }

    @Test
    fun `the final score of a game seen live is delayed too`() {
        val o = ScoreObservations()
        o.note(listOf(fx("e3")), listOf(live("e3", "1", "0")), 0L)
        o.note(listOf(fx("e3", status = "Match Finished", h = "2", a = "0")), emptyList(), 100_000L)
        val early = o.scoreView(d30, "e3", null, 110_000L) as ScoreView.ScoreShown
        assertEquals("still the older score", "1", early.value?.homeScore)
        val late = o.scoreView(d30, "e3", null, 131_000L) as ScoreView.ScoreShown
        assertEquals("final once due", "2", late.value?.homeScore)
    }

    @Test
    fun `settings off means nothing changes`() {
        val o = ScoreObservations()
        o.note(listOf(fx("e4")), listOf(live("e4", "3", "3")), 0L)
        assertEquals(ScoreView.ScoreShown(live("e4", "3", "3")), o.scoreView(ScoreSpoilerSettings(), "e4", live("e4", "3", "3"), 1L))
        assertTrue(o.nextChangeAtMs(1L, 0L) == null)
    }

    private val d60 = ScoreSpoilerSettings(shown = true, delaySeconds = 60)

    @Test
    fun `a game with no live row that lands final with a score is delayed too`() {
        val o = ScoreObservations()
        o.note(listOf(fx("e5")), emptyList(), 0L)
        val fin = fx("e5", status = "Match Finished", h = "2", a = "1")
        o.note(listOf(fin), emptyList(), 100_000L)
        assertEquals("pending when the fetch lands", ScoreView.ScorePending, o.scoreView(d60, fin, null, 100_000L))
        assertEquals("pending at 59 s", ScoreView.ScorePending, o.scoreView(d60, fin, null, 159_000L))
        val late = o.scoreView(d60, fin, null, 160_000L) as ScoreView.ScoreShown
        assertEquals("shown 60 s later", "2", late.value?.homeScore)
    }

    @Test
    fun `an in-progress score from a sport without a live feed is delayed`() {
        val o = ScoreObservations()
        val a = fx("e7", h = "1", a = "0")
        val b = fx("e7", h = "2", a = "0")
        o.note(listOf(a), emptyList(), 0L)
        o.note(listOf(b), emptyList(), 70_000L)
        val v = o.scoreView(d60, b, null, 80_000L) as ScoreView.ScoreShown
        assertEquals("the older score", "1", v.value?.homeScore)
    }

    @Test
    fun `a fixture score does not shadow the live row of the same response`() {
        val o = ScoreObservations()
        o.note(listOf(fx("e8", h = "1", a = "0")), listOf(live("e8", "1", "0")), 0L)
        val v = o.scoreView(d30, fx("e8", h = "1", a = "0"), live("e8", "1", "0"), 31_000L) as ScoreView.ScoreShown
        assertEquals("live row snapshot", "1H", v.value?.status)
    }

    @Test
    fun `a game that was live in the disk cache shows the cached value once the delay passed`() {
        val o = ScoreObservations()
        val cached = fx("e6", status = "1H", h = "1", a = "0")
        o.noteCached(listOf(cached), listOf(live("e6", "1", "0")), 1_000L)
        assertEquals("pending right after load", ScoreView.ScorePending, o.scoreView(d30, cached, live("e6", "1", "0"), 20_000L))
        val v = o.scoreView(d30, cached, live("e6", "1", "0"), 31_000L) as ScoreView.ScoreShown
        assertEquals("cached value after the delay, no fetch needed", "1", v.value?.homeScore)
    }

    @Test
    fun `a cached final stays shown at once`() {
        val o = ScoreObservations()
        val cached = fx("e10", status = "Match Finished", h = "2", a = "2")
        o.noteCached(listOf(cached), emptyList(), 1_000L)
        val v = o.scoreView(d30, cached, null, 1_001L) as ScoreView.ScoreShown
        assertEquals("old news shown at once", "2", v.value?.homeScore)
    }

    @Test
    fun `an upcoming game with no score still shows no score`() {
        val o = ScoreObservations()
        val up = fx("e9")
        o.note(listOf(up), emptyList(), 0L)
        assertEquals(ScoreView.ScoreShown(null), o.scoreView(d60, up, null, 1L))
    }
}
