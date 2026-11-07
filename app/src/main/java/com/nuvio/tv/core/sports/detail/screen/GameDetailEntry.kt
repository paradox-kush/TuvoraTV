package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.radar.RadarCalendarGames
import com.nuvio.tv.core.radar.RadarFixture
import com.nuvio.tv.core.sports.detail.GameRef
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.SportsMapping

/** Bridges the Sports hub's fixtures to the game-detail core. */
object GameDetailEntry {
    fun refFor(fx: RadarFixture, live: Boolean): GameRef {
        val id = fx.id?.let { "tsdb:$it" } ?: "tsdb:${RadarCalendarGames.gameId(fx)}"
        val state = when {
            fx.postponed.equals("yes", true) -> GameState.POSTPONED
            fx.isFinishedOrOff -> GameState.FINAL
            live -> GameState.LIVE
            else -> GameState.UPCOMING
        }
        val scored = state == GameState.LIVE || state == GameState.FINAL
        return GameRef(
            gameId = id,
            leagueId = fx.leagueId.orEmpty(),
            homeName = fx.home.orEmpty(),
            awayName = fx.away.orEmpty(),
            startsAtMs = fx.startEpochMs ?: 0L,
            sport = fx.sport.orEmpty(),
            leagueName = fx.league.orEmpty(),
            eventName = fx.event,
            round = fx.round,
            venue = fx.venue,
            seedHome = fx.home?.let { Side(it, badgeUrl = fx.homeBadge, score = fx.homeScore?.takeIf { _ -> scored }) },
            seedAway = fx.away?.let { Side(it, badgeUrl = fx.awayBadge, score = fx.awayScore?.takeIf { _ -> scored }) },
            seedState = state,
        )
    }

    /** The detail screen only opens for games the mapping can serve; everything else keeps the match sheet. */
    fun shouldOpenDetail(fx: RadarFixture, mapping: SportsMapping): Boolean =
        !fx.id.isNullOrBlank() && mapping.leagueFor(fx.leagueId) != null

    fun findFixture(all: List<RadarFixture>, gameId: String): RadarFixture? =
        all.firstOrNull { RadarCalendarGames.gameId(it) == gameId }
}
