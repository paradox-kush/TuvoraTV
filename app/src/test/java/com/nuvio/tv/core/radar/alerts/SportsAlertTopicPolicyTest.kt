package com.nuvio.tv.core.radar.alerts

import com.nuvio.tv.core.radar.RadarFollow
import com.nuvio.tv.core.radar.RadarTeamFollow
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsAlertTopicPolicyTest {
    private val on = SportsAlertSettings()
    private fun league(id: String, sport: String) = RadarFollow(leagueId = id, sport = sport)
    private fun team(id: String, sport: String) = RadarTeamFollow(teamId = id, name = id, sport = sport)
    private fun topics(
        s: SportsAlertSettings = on,
        leagues: List<RadarFollow> = emptyList(),
        teams: List<RadarTeamFollow> = emptyList(),
        signedIn: Boolean = true,
    ) = SportsAlertTopicPolicy.topics(s, leagues, teams, signedIn)

    @Test
    fun `off, no follows and signed out are all empty`() {
        val follows = listOf(league("L1", "Soccer"))
        assertEquals("alerts off", emptySet<String>(), topics(on.copy(enabled = false), follows))
        assertEquals("no follows", emptySet<String>(), topics())
        assertEquals("signed out", emptySet<String>(), topics(leagues = follows, signedIn = false))
    }

    @Test
    fun `one topic per followed sport and only the five live-feed sports`() {
        val t = topics(
            leagues = listOf(league("L1", "Soccer"), league("L2", "Soccer"), league("L3", "American Football"), league("L4", "Cricket")),
            teams = listOf(team("T1", "Ice Hockey"), team("T2", "Basketball"), team("T3", "Baseball"), team("T4", "Tennis")),
        )
        assertEquals(
            "five sports, slugged, no cricket or tennis",
            setOf("soccer", "american_football", "ice_hockey", "basketball", "baseball").map { "sports-alerts:v1:$it" }.toSet(),
            t,
        )
    }

    @Test
    fun `a muted league removes its sport only when nothing else needs it`() {
        val leagues = listOf(league("L1", "Soccer"), league("L2", "Basketball"))
        assertEquals(
            "basketball stays, soccer goes",
            setOf("sports-alerts:v1:basketball"),
            topics(on.copy(mutedLeagues = setOf("L1")), leagues),
        )
        val withTeam = topics(on.copy(mutedLeagues = setOf("L1")), leagues, teams = listOf(team("T1", "Soccer")))
        assertEquals(
            "a followed soccer team keeps the soccer topic",
            setOf("sports-alerts:v1:basketball", "sports-alerts:v1:soccer"),
            withTeam,
        )
        val mutedBoth = topics(on.copy(mutedLeagues = setOf("L1"), mutedTeams = setOf("T1")), listOf(league("L1", "Soccer")), listOf(team("T1", "Soccer")))
        assertEquals("both muted", emptySet<String>(), mutedBoth)
    }

    @Test
    fun `a follow with no sport has no topic`() {
        assertEquals("blank sport", emptySet<String>(), topics(leagues = listOf(league("L1", ""))))
    }
}
