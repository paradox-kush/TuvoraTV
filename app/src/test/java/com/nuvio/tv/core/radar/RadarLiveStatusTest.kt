package com.nuvio.tv.core.radar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished or too-old fixture must never read LIVE, however stale the livescore feed's live set
 * is. Regression for the reported "Sunday NFL game still shows LIVE on Monday" (robustness
 * inventory T1/BK1/#4): [RadarUiState.isLive] previously returned `feedConfirmed` (id in the
 * stale-served live set) with no finished-status gate and no max-window cap, so a covered-sport
 * game could read LIVE forever.
 */
class RadarLiveStatusTest {

    private val ts = "2026-08-24T18:00:00" // a Sunday 18:00 UTC kick-off
    private val nfl = RadarFixture(
        id = "nfl1",
        leagueId = "4391",
        sport = "American Football",
        home = "Chiefs",
        away = "Bills",
        ts = ts,
    )
    private val start = nfl.startEpochMs!!

    /** Feed-covered NFL, id still in the (stale) live set, but the fixture is FINISHED. */
    private fun state(fixture: RadarFixture) = RadarUiState(
        fixturesByLeague = mapOf("4391" to listOf(fixture)),
        liveEventIds = setOf("nfl1"),
        livescoreSports = setOf("american football"),
    )

    @Test
    fun `a finished NFL game is not live even when its id is still in the live set`() {
        val finished = nfl.copy(status = "Final")
        assertFalse(
            "a fixture whose strStatus is finished must never read LIVE (the Sunday-NFL-on-Monday bug)",
            state(finished).isLive(finished, start + 20 * 60 * 60 * 1000L),
        )
    }

    @Test
    fun `a day-old NFL game with no status is capped by the max-live window`() {
        // status unknown (null) + id still stale in the live set: the hard window cap is the backstop.
        assertFalse(
            "a stale live-set entry must not read LIVE a day later even without a finished status",
            state(nfl).isLive(nfl, start + 20 * 60 * 60 * 1000L),
        )
    }

    @Test
    fun `a postponed fixture is not live`() {
        val off = nfl.copy(postponed = "yes")
        assertFalse(
            "a postponed fixture is never live",
            state(off).isLive(off, start + 60 * 60 * 1000L),
        )
    }

    @Test
    fun `a genuinely in-progress NFL game is still live`() {
        // One hour after kick-off, no finished status, id in the live set: must stay LIVE.
        assertTrue(
            "the fix must not clip a genuinely live game inside its window",
            state(nfl).isLive(nfl, start + 60 * 60 * 1000L),
        )
    }
}
