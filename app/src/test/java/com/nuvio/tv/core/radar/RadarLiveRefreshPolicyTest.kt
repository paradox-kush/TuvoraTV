package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sports live tick must refresh ONLY leagues/teams with a live-or-imminent fixture — the whole
 * point of the egress fix. The old poll refetched every followed league in full every 2 minutes
 * (one 62-league follower re-pulling ~850 KB/poll, 24/7, was the top Supabase-egress source); the
 * policy narrows each tick to what could actually be live, and returns an empty target set (→ no
 * network) when the slate is idle. These assert the decision the old force-refresh never made.
 */
class RadarLiveRefreshPolicyTest {

    // Kick-off far enough in the future that "now" can be placed anywhere around it deterministically.
    private val kickoff = "2026-08-25T18:00:00" // 18:00 UTC
    private fun fixture(id: String, leagueId: String, sport: String = "Soccer", ts: String? = kickoff) =
        RadarFixture(id = id, leagueId = leagueId, sport = sport, home = "H", away = "A", ts = ts)

    private val start = fixture("x", "x").startEpochMs!!

    @Test
    fun `a league with an in-progress fixture is a target`() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328"))),
            fixturesByTeam = emptyMap(),
            nowMs = start + 30 * 60 * 1000L, // 30 min into a 2.5h soccer window
        )
        assertEquals("a currently-live league must be refreshed", setOf("4328"), targets.leagueIds)
    }

    @Test
    fun `a league whose only fixture is finished is not a target`() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328").copy(status = "Final"))),
            fixturesByTeam = emptyMap(),
            nowMs = start + 30 * 60 * 1000L,
        )
        assertTrue("a finished-only league must not be refreshed", targets.isEmpty)
    }

    @Test
    fun `a fixture inside the pre-kickoff lead makes its league a target`() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328"))),
            fixturesByTeam = emptyMap(),
            nowMs = start - 10 * 60 * 1000L, // 10 min before kick-off, inside the 30-min lead
        )
        assertEquals("an imminent kick-off must join the live tick early", setOf("4328"), targets.leagueIds)
    }

    @Test
    fun `a fixture well before the lead window is not a target`() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328"))),
            fixturesByTeam = emptyMap(),
            nowMs = start - 2 * 60 * 60 * 1000L, // 2h before kick-off
        )
        assertTrue("a league whose next game is hours away must not be refreshed", targets.isEmpty)
    }

    @Test
    fun `a fixture past its max live window is not a target`() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf("4328" to listOf(fixture("g1", "4328"))), // no finished status
            fixturesByTeam = emptyMap(),
            nowMs = start + 20 * 60 * 60 * 1000L, // a day later
        )
        assertTrue("a stale, long-past fixture must not keep its league on the live tick", targets.isEmpty)
    }

    @Test
    fun `an idle slate yields no targets so the tick makes no network call`() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328", "4335"),
            candidateTeamIds = setOf("133604"),
            fixturesByLeague = mapOf(
                "4328" to listOf(fixture("g1", "4328", ts = "2026-08-25T23:00:00")), // hours away
                "4335" to listOf(fixture("g2", "4335").copy(status = "Match Finished")),
            ),
            fixturesByTeam = mapOf("133604" to listOf(fixture("g3", "4335").copy(status = "FT"))),
            nowMs = start, // 18:00
        )
        assertTrue("nothing live/imminent must produce an empty target set (no fetch)", targets.isEmpty)
    }

    @Test
    fun `a followed team with a live fixture is a target`() {
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = emptySet(),
            candidateTeamIds = setOf("133604"),
            fixturesByLeague = emptyMap(),
            fixturesByTeam = mapOf("133604" to listOf(fixture("g1", "4328"))),
            nowMs = start + 30 * 60 * 1000L,
        )
        assertEquals("a live followed club must be refreshed", setOf("133604"), targets.teamIds)
    }

    @Test
    fun `a live fixture cached for a non-candidate league is ignored`() {
        // Only 4328 is followed/featured; 9999 is merely browsed and cached — a live game there
        // must not drag it onto the tick (the candidate set is authoritative).
        val targets = RadarLiveRefreshPolicy.targets(
            candidateLeagueIds = setOf("4328"),
            candidateTeamIds = emptySet(),
            fixturesByLeague = mapOf(
                "4328" to listOf(fixture("g1", "4328", ts = "2026-08-26T00:00:00")), // not live
                "9999" to listOf(fixture("g2", "9999")), // live, but not a candidate
            ),
            fixturesByTeam = emptyMap(),
            nowMs = start + 30 * 60 * 1000L,
        )
        assertTrue("only candidate leagues may become targets", targets.isEmpty)
    }
}
