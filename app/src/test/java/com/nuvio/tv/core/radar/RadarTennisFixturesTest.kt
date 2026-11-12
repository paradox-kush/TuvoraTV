package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B142 — "no tennis events despite following ATP and WTA". Real prod payload shape
 * (`radar_fixture_cache` league:4464, 2026-11-10): tennis rows have no home/away, no status, a
 * date-only `ts` (strTime 00:00:00 = midnight UTC, not a real start) and `intRound` = a tour
 * WEEK number ("57"), not a knockout round. Treating that midnight as a kick-off dropped a match
 * day's fixtures from Upcoming at 04:00 UTC (before an Americas viewer's morning) and then filed
 * them under Recent with a "Round 57" label.
 */
class RadarTennisFixturesTest {
    private val hour = 60 * 60 * 1000L
    private val match = RadarFixture(
        id = "t1", leagueId = "4464", sport = "Tennis", league = "ATP World Tour",
        event = "BNP Paribas Masters Alcaraz vs Shelton", round = "57",
        ts = "2026-11-08T00:00:00",
    )
    private val day = match.startEpochMs!!
    private fun state() = RadarUiState(fixturesByLeague = mapOf("4464" to listOf(match)))

    @Test
    fun `a tennis match day stays upcoming for the whole day in the Americas`() {
        val nowMs = day + 18 * hour // 18:00 UTC = 2 pm New York
        assertEquals(listOf("t1"), state().upcoming(listOf("4464"), nowMs).map { it.id })
    }

    @Test
    fun `a tennis match day is not filed under recent while that day is still running`() {
        val nowMs = day + 18 * hour
        assertTrue(state().recent("4464", nowMs).isEmpty())
    }

    @Test
    fun `the day after tennis moves to recent and leaves upcoming`() {
        val nowMs = day + 40 * hour
        assertTrue(state().upcoming(listOf("4464"), nowMs).isEmpty())
        assertEquals(listOf("t1"), state().recent("4464", nowMs).map { it.id })
    }

    @Test
    fun `a soccer fixture at midnight UTC keeps the four hour window`() {
        val soccer = match.copy(id = "s1", sport = "Soccer", home = "A", away = "B")
        val st = RadarUiState(fixturesByLeague = mapOf("4464" to listOf(soccer)))
        assertTrue(st.upcoming(listOf("4464"), day + 18 * hour).isEmpty())
    }

    @Test
    fun `tennis round numbers are tour weeks and show no round label`() {
        assertNull(match.roundLabel)
        assertEquals("Quarter-final", match.copy(sport = "Soccer", round = "125").roundLabel)
        assertEquals("Round 3", match.copy(sport = "Soccer", round = "3").roundLabel)
    }
}
