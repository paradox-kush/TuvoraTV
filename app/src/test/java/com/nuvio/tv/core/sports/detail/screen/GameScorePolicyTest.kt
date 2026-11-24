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

    private fun withBlocks(state: GameState) = detail(state, "2", "1").let { d ->
        d.copy(
            header = GameHeader.Teams(Side("Home", score = "2", record = "10-2"), Side("Away", score = "1", record = "4-8")),
            blocks = listOf(
                GameBlock.Results(SourceId.ESPN, listOf("Pos"), listOf(listOf("1"))),
                GameBlock.Highlights(SourceId.ESPN, "https://x"),
                GameBlock.Standings(SourceId.ESPN, emptyList()),
                GameBlock.StandingsTable(SourceId.ESPN, emptyList()),
                GameBlock.Lineup(SourceId.ESPN, emptyList()),
                GameBlock.Form(SourceId.ESPN, emptyList(), emptyList()),
                GameBlock.Stats(SourceId.ESPN, emptyList()),
            ),
        )
    }

    @Test
    fun `scrub is an allow-list - results and highlights go, standings lineup and form stay`() {
        val types = GameSpoilerFilter.scrub(withBlocks(GameState.FINAL)).blocks.map { it.type }.toSet()
        assertEquals("only the safe ones", setOf(BlockType.STANDINGS, BlockType.STANDINGS_TABLE, BlockType.LINEUP, BlockType.FORM), types)
    }

    @Test
    fun `scrub hides the win-loss record too`() {
        val h = GameSpoilerFilter.scrub(withBlocks(GameState.LIVE)).header as GameHeader.Teams
        assertNull("home record", h.home.record)
        assertNull("away record", h.away.record)
    }

    @Test
    fun `an upcoming game keeps its records and blocks`() {
        val d = withBlocks(GameState.UPCOMING)
        assertEquals(d, GameSpoilerFilter.scrub(d))
    }

    @Test
    fun `reveal chip only for live or final games`() {
        assertEquals("live", GameRevealChip.SHOW_THIS, GameRevealChip.of(true, false, GameState.LIVE))
        assertEquals("final", GameRevealChip.SHOW_THIS, GameRevealChip.of(true, false, GameState.FINAL))
        assertEquals("postponed", GameRevealChip.NONE, GameRevealChip.of(true, false, GameState.POSTPONED))
        assertEquals("upcoming", GameRevealChip.NONE, GameRevealChip.of(true, false, GameState.UPCOMING))
        assertEquals("not hidden", GameRevealChip.NONE, GameRevealChip.of(false, false, GameState.LIVE))
    }

    @Test
    fun `a revealed game offers Hide again`() {
        assertEquals("revealed live", GameRevealChip.HIDE_AGAIN, GameRevealChip.of(true, true, GameState.LIVE))
        assertEquals("revealed final", GameRevealChip.HIDE_AGAIN, GameRevealChip.of(true, true, GameState.FINAL))
        assertEquals("a revealed upcoming game has no chip", GameRevealChip.NONE, GameRevealChip.of(true, true, GameState.UPCOMING))
    }

    @Test
    fun `a hidden started game with nothing left to show says so`() {
        val empty = com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel.from(GameSpoilerFilter.scrub(withBlocks(GameState.FINAL).copy(blocks = emptyList())), 0L)
        assertTrue("redaction left nothing", empty.hiddenOverviewEmpty(true))
        assertEquals("not when scores are shown", false, empty.hiddenOverviewEmpty(false))
        val rich = com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel.from(
            withBlocks(GameState.FINAL).copy(blocks = listOf(GameBlock.Stats(SourceId.ESPN, listOf(com.nuvio.tv.core.sports.detail.StatRow("Shots", "1", "2"))))), 0L,
        )
        assertEquals("a card still has content", false, rich.hiddenOverviewEmpty(true))
    }

    @Test
    fun `a delay change resets the moment baseline`() {
        assertTrue("delay 15 to 30", GameRenderBaseline.shouldReset("S", "S", 15_000L, 30_000L))
        assertTrue("mode flip", GameRenderBaseline.shouldReset("H", "S", 0L, 0L))
        assertEquals("steady", false, GameRenderBaseline.shouldReset("S", "S", 30_000L, 30_000L))
        assertEquals("first render has no baseline to reset", false, GameRenderBaseline.shouldReset("S", "S", null, 30_000L))
    }
}
