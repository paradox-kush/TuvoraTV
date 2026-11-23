package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarUiStateScoreDueTest {
    private fun live(h: String) = RadarLiveScore(eventId = "e1", status = "1H", homeScore = h, awayScore = "0")

    private fun state(prefs: RadarPrefs): RadarUiState {
        val obs = ScoreObservations()
        obs.note(emptyList(), listOf(live("1")), 10_000L)
        return RadarUiState(prefs = prefs, scores = obs)
    }

    @Test
    fun `the due time is exposed so the visible screen can drive the one-shot`() {
        assertEquals("due", 40_000L, state(RadarPrefs(scoreDelayS = 30)).nextScoreDueAtMs(20_000L))
    }

    @Test
    fun `no delay or hidden scores means nothing is due`() {
        assertEquals("no delay", null, state(RadarPrefs()).nextScoreDueAtMs(20_000L))
        assertEquals("hidden", null, state(RadarPrefs(scoresShown = false, scoreDelayS = 30)).nextScoreDueAtMs(20_000L))
    }
}
