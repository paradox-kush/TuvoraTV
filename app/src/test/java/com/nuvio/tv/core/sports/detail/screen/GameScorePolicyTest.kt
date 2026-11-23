package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.radar.DelayedValueBuffer
import com.nuvio.tv.core.radar.ScoreSpoilerSettings
import com.nuvio.tv.core.sports.detail.Availability
import com.nuvio.tv.core.sports.detail.BlockType
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameHeader
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.LiveInfo
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameScorePolicyTest {
    private fun detail(state: GameState, h: String, a: String) = GameDetail(
        gameId = "g1", sport = "Soccer", leagueId = "L", leagueName = "League", profile = null, round = null, venue = null,
        startsAtMs = 0L, state = state,
        header = GameHeader.Teams(Side("Home", score = h), Side("Away", score = a)),
        live = LiveInfo(clock = "40'", periodLabel = "1H"),
        blocks = listOf(
            GameBlock.Timeline(SourceId.ESPN, emptyList()),
            GameBlock.WinProbability(SourceId.MARKET, 60.0),
            GameBlock.SoccerStrip(SourceId.ESPN, homeScorers = listOf("X 12'")),
            GameBlock.Stats(SourceId.ESPN, emptyList()),
            GameBlock.Lineup(SourceId.ESPN, emptyList()),
            GameBlock.Form(SourceId.ESPN, emptyList(), emptyList()),
        ),
        sources = emptyMap(), sourceStatus = emptyMap(), availability = Availability.FULL, asOf = emptyMap(),
    )

    @Test
    fun `scrub removes scores and spoiler blocks but keeps status lineups and form`() {
        val s = GameSpoilerFilter.scrub(detail(GameState.LIVE, "2", "1"))
        val t = s.header as GameHeader.Teams
        assertNull("home score", t.home.score)
        assertNull("away score", t.away.score)
        val types = s.blocks.map { it.type }.toSet()
        assertEquals("kept", setOf(BlockType.LINEUP, BlockType.FORM), types)
        assertEquals("clock stays", "40'", s.live?.clock)
        assertEquals("state stays", GameState.LIVE, s.state)
        assertEquals("availability stays", Availability.FULL, s.availability)
    }

    @Test
    fun `an upcoming game is not scrubbed`() {
        val d = detail(GameState.UPCOMING, "", "")
        assertEquals(d, GameSpoilerFilter.scrub(d))
    }

    @Test
    fun `hidden renders the scrubbed model`() {
        val r = GameScorePolicy.render(ScoreSpoilerSettings(shown = false), "g1", DelayedValueBuffer(), detail(GameState.FINAL, "2", "1"), false, 0L)
        assertTrue(r is GameRender.Hidden)
        assertNull(((r!!.detail.header) as GameHeader.Teams).home.score)
    }

    @Test
    fun `delay renders the buffered older model and pending before it is due`() {
        val buf = DelayedValueBuffer<String, GameDetail>()
        val d0 = detail(GameState.LIVE, "0", "0"); val d1 = detail(GameState.LIVE, "1", "0")
        buf.record("g1", d0, 0L); buf.record("g1", d1, 20_000L)
        val s = ScoreSpoilerSettings(delaySeconds = 30)
        assertEquals("pending", GameRender.Pending::class, GameScorePolicy.render(s, "g1", buf, d1, false, 10_000L)!!::class)
        val shown = GameScorePolicy.render(s, "g1", buf, d1, false, 40_000L) as GameRender.Shown
        assertEquals("older", d0, shown.detail)
        assertNotNull(GameScorePolicy.render(s, "g1", buf, d1, true, 1L))
        assertEquals("final at first sight not delayed", d1, (GameScorePolicy.render(s, "g1", buf, d1, true, 1L) as GameRender.Shown).detail)
    }
}
