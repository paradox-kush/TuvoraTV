package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealedGamesTest {
    private fun fx(id: String) = RadarFixture(
        id = id, leagueId = "L", sport = "Soccer", home = "H", away = "A", status = "Match Finished", homeScore = "2", awayScore = "1",
    )
    private val hidden = RadarPrefs(scoresShown = false)

    private fun state(revealed: Set<String>) = RadarUiState(prefs = hidden, revealed = revealed)

    @Test
    fun `revealing one game leaves the others hidden`() {
        val s = state(setOf("g1"))
        assertTrue("revealed game shows", s.scoreView(fx("g1"), 1L) is ScoreView.ScoreShown)
        assertEquals("other stays hidden", ScoreView.ScoreHidden, s.scoreView(fx("g2"), 1L))
    }

    @Test
    fun `hide again re-hides the game`() {
        val ids = RevealedGames({ 1 })
        ids.reveal("g1")
        assertEquals("shown", true, state(ids.current()).scoreView(fx("g1"), 1L) is ScoreView.ScoreShown)
        ids.hideAgain("g1")
        assertEquals("hidden again", ScoreView.ScoreHidden, state(ids.current()).scoreView(fx("g1"), 1L))
    }

    @Test
    fun `a profile switch forgets every reveal and a fresh set starts empty`() {
        var profile = 1
        val ids = RevealedGames({ profile })
        ids.reveal("g1")
        assertEquals("same profile remembers", setOf("g1"), ids.current())
        profile = 2
        assertEquals("other profile starts clean", emptySet<String>(), ids.current())
        profile = 1
        assertEquals("and does not get them back", emptySet<String>(), ids.current())
        assertEquals("fresh set", emptySet<String>(), RevealedGames({ 1 }).current())
    }

    @Test
    fun `revealing never changes the global setting`() {
        val s = state(setOf("g1"))
        assertEquals("global still hidden", false, s.spoiler.shown)
        assertEquals("settings for the revealed game are shown", true, s.spoilerFor("g1").shown)
        assertEquals("settings for another game stay hidden", false, s.spoilerFor("g2").shown)
    }

    @Test
    fun `a revealed game still obeys the delay`() {
        val s = RadarUiState(prefs = RadarPrefs(scoresShown = false, scoreDelayS = 30), revealed = setOf("g1"))
        assertEquals("delay kept", 30, s.spoilerFor("g1").delaySeconds)
    }
}
