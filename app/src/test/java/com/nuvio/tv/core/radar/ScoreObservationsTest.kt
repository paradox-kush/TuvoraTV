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
}
