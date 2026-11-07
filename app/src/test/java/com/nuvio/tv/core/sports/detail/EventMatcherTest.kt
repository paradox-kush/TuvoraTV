package com.nuvio.tv.core.sports.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventMatcherTest {
    private val m = SportsFixtures.mapping
    private val t0 = 1_800_000_000_000L
    private fun c(id: String, h: String, a: String, dt: Long = 0) = EspnCandidate(id, h, a, t0 + dt)
    private fun q(h: String, a: String, dt: Long = 0, id: String = "tsdb:1") = MatchQuery(id, h, a, t0 + dt)
    private val hour = 3_600_000L

    @Test fun `normalise drops accents suffixes and applies aliases`() {
        assertEquals("accent+fc", "atletico madrid", EventMatcher.normalize("Atlético Madrid FC", m.teamAliases))
        assertEquals("alias", "manchester united", EventMatcher.normalize("Man Utd", m.teamAliases))
        assertEquals("alias psg", "paris saint-germain", EventMatcher.normalize("PSG", m.teamAliases))
    }
    @Test fun `exact teams within the window match`() =
        assertEquals("id", "e1", EventMatcher.match(q("Leeds United", "Tottenham Hotspur"), listOf(c("e1", "Leeds United", "Tottenham Hotspur")), m))
    @Test fun `alias bridges provider naming`() =
        assertEquals("id", "e2", EventMatcher.match(q("Man Utd", "Spurs"), listOf(c("e2", "Manchester United", "Tottenham Hotspur")), m))
    @Test fun `short name tokens are contained in the full name`() =
        assertEquals("id", "e3", EventMatcher.match(q("Atlanta Hawks", "Boston Celtics"), listOf(c("e3", "Atlanta Hawks", "Boston Celtics")), m))
    @Test fun `outside the date window does not match`() =
        assertNull("7h away", EventMatcher.match(q("A City", "B Town"), listOf(c("x", "A City", "B Town", 7 * hour)), m))
    @Test fun `both teams must match`() =
        assertNull("one team", EventMatcher.match(q("Leeds United", "Everton"), listOf(c("x", "Leeds United", "Tottenham Hotspur")), m))
    @Test fun `closest start wins among duplicates`() =
        assertEquals("doubleheader", "late", EventMatcher.match(q("A City", "B Town", 5 * hour), listOf(c("early", "A City", "B Town", 0), c("late", "A City", "B Town", 5 * hour)), m))
    @Test fun `manual override wins even with no candidate agreement`() {
        val withOverride = m.copy(eventOverrides = mapOf("1" to "forced"))
        assertEquals("override by raw id", "forced", EventMatcher.match(q("Q", "R", id = "tsdb:1"), listOf(c("other", "Z", "Y")), withOverride))
        assertEquals("override even with none", "forced", EventMatcher.match(q("Q", "R", id = "tsdb:1"), emptyList(), withOverride))
    }
    @Test fun `event style games match on date and name overlap`() {
        val cand = listOf(c("gp", "", "").copy(name = "Brazilian Grand Prix"))
        assertEquals("gp", EventMatcher.match(MatchQuery("tsdb:9", "", "", t0, eventName = "Formula 1 Brazilian Grand Prix 2026"), cand, m))
        assertNull("different race", EventMatcher.match(MatchQuery("tsdb:9", "", "", t0, eventName = "Monaco Grand Prix"), cand, m))
    }
    @Test fun `candidates are read from a real scoreboard`() {
        val cands = EventMatcher.candidatesFromScoreboard(SportsFixtures.json("espn/epl_scoreboard.json"), SportsFixtures.mapping)
        assertEquals("four events", 4, cands.size)
        assertEquals("id and teams present", true, cands.all { it.id.isNotBlank() && it.homeName.isNotBlank() && it.awayName.isNotBlank() })
    }
}
