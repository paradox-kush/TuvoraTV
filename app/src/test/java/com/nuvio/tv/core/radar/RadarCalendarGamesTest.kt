package com.nuvio.tv.core.radar

import com.nuvio.tv.core.calendar.CalendarGameState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadarCalendarGamesTest {
    private val now = Instant.parse("2026-11-04T15:00:00Z").toEpochMilli()

    private fun fixture(id: String, ts: String, status: String? = null, postponed: String? = null, league: String = "4328", score: Pair<String, String>? = null) =
        RadarFixture(
            id = id, leagueId = league, sport = "Soccer", home = "Arsenal", away = "Chelsea", ts = ts,
            status = status, postponed = postponed, homeScore = score?.first, awayScore = score?.second, homeBadge = "h.png"
        )

    private val state = RadarUiState(
        follows = listOf(RadarFollow(leagueId = "4328", name = "Premier League", badge = "pl.png", custom = true)),
        teamFollows = listOf(RadarTeamFollow(teamId = "133604", name = "Arsenal")),
        fixturesByLeague = mapOf(
            "4328" to listOf(
                fixture("1", "2026-11-04T14:00:00", score = "1" to "0"),
                fixture("2", "2026-11-01T15:00:00", status = "Match Finished", score = "2" to "2"),
                fixture("3", "2026-11-08T16:30:00"),
                fixture("4", "2026-11-09T16:30:00", postponed = "yes")
            ),
            "9999" to listOf(fixture("x", "2026-11-05T10:00:00", league = "9999"))
        ),
        fixturesByTeam = mapOf("133604" to listOf(fixture("3", "2026-11-08T16:30:00"), fixture("5", "2026-11-10T19:00:00", league = "4480"))),
        liveEventIds = setOf("1"),
        liveScores = mapOf("1" to RadarLiveScore(eventId = "1", progress = "67'", homeScore = "2", awayScore = "0")),
        livescoreSports = setOf("soccer")
    )

    @Test fun followedLeaguesAndTeamsBecomeOneGameEachSoonestFirst() {
        val games = RadarCalendarGames.games(state, now)
        assertEquals("followed only, de-duplicated", listOf("2", "1", "3", "4", "5"), games.map { it.id })
    }

    @Test fun stateScoreAndMinuteComeFromTheHeldFeed() {
        val byId = RadarCalendarGames.games(state, now).associateBy { it.id }
        assertEquals("live", CalendarGameState.LIVE, byId.getValue("1").state)
        assertEquals("live score row wins", "2" to "0", byId.getValue("1").homeScore to byId.getValue("1").awayScore)
        assertEquals("minute", "67'", byId.getValue("1").progress)
        assertEquals("final", CalendarGameState.FINAL, byId.getValue("2").state)
        assertEquals("final score", "2" to "2", byId.getValue("2").homeScore to byId.getValue("2").awayScore)
        assertEquals("scheduled", CalendarGameState.SCHEDULED, byId.getValue("3").state)
        assertNull("no score before kick-off", byId.getValue("3").homeScore)
        assertEquals("postponed", CalendarGameState.POSTPONED, byId.getValue("4").state)
        assertEquals("league name from the follow", "Premier League", byId.getValue("3").league)
        assertEquals("league badge from the follow", "pl.png", byId.getValue("3").leagueBadge)
        assertEquals("kick-off is UTC", Instant.parse("2026-11-08T16:30:00Z").toEpochMilli(), byId.getValue("3").kickoffMs)
    }

    @Test fun aFixtureWithoutAKickoffIsLeftOut() {
        assertNull("no ts", RadarCalendarGames.item(RadarFixture(id = "n", leagueId = "4328"), state, now))
        assertEquals("id fallback", "4328/Grand Prix/null", RadarCalendarGames.gameId(RadarFixture(leagueId = "4328", event = "Grand Prix")))
    }
}
