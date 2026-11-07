package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.radar.RadarFixture
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.BundledSportsMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDetailEntryTest {
    private val fx = RadarFixture(
        id = "2494097", leagueId = "4328", league = "EPL", sport = "Soccer", home = "Leeds", away = "Spurs",
        homeBadge = "h.png", awayBadge = "a.png", ts = "2026-11-07T12:30:00", venue = "Elland Road", round = "10",
        homeScore = "1", awayScore = "0", status = "2H",
    )

    @Test fun `a tsdb fixture becomes a GameRef with seeds`() {
        val r = GameDetailEntry.refFor(fx, live = true)
        assertEquals("id", "tsdb:2494097", r.gameId)
        assertEquals("tsdb event", "2494097", r.tsdbEventId)
        assertEquals("league", "4328", r.leagueId)
        assertEquals("names", "Leeds" to "Spurs", r.homeName to r.awayName)
        assertEquals("seed score", "1", r.seedHome?.score)
        assertEquals("seed badge", "a.png", r.seedAway?.badgeUrl)
        assertEquals("seed state", GameState.LIVE, r.seedState)
        assertEquals("start", java.time.Instant.parse("2026-11-07T12:30:00Z").toEpochMilli(), r.startsAtMs)
    }
    @Test fun `finished and postponed seed states`() {
        assertEquals("final", GameState.FINAL, GameDetailEntry.refFor(fx.copy(status = "FT"), live = false).seedState)
        assertEquals("postponed", GameState.POSTPONED, GameDetailEntry.refFor(fx.copy(postponed = "yes"), live = false).seedState)
        assertEquals("upcoming", GameState.UPCOMING, GameDetailEntry.refFor(fx.copy(status = "NS"), live = false).seedState)
    }
    @Test fun `event style fixtures carry the event name`() {
        val r = GameDetailEntry.refFor(fx.copy(home = null, away = null, event = "Brazilian GP"), live = false)
        assertEquals("event", "Brazilian GP", r.eventName)
        assertEquals("blank away", "", r.awayName)
    }
    @Test fun `only leagues the mapping knows open the detail screen`() {
        val m = BundledSportsMapping.load()
        assertTrue("epl", GameDetailEntry.shouldOpenDetail(fx, m))
        assertFalse("unknown league goes straight to the match sheet", GameDetailEntry.shouldOpenDetail(fx.copy(leagueId = "999999"), m))
        assertFalse("no event id", GameDetailEntry.shouldOpenDetail(fx.copy(id = null), m))
        assertNull("fixture lookup is by calendar game id", GameDetailEntry.findFixture(emptyList(), "x"))
    }
    @Test fun `fixture is found by the calendar game id`() {
        assertEquals("found", fx, GameDetailEntry.findFixture(listOf(fx.copy(id = "1"), fx), "2494097"))
    }
}
