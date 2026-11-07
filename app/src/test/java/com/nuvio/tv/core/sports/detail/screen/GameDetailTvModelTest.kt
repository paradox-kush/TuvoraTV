package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.Availability
import com.nuvio.tv.core.sports.detail.BlockType
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameHeader
import com.nuvio.tv.core.sports.detail.GameRef
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.LiveInfo
import com.nuvio.tv.core.sports.detail.SideKey
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.SourceId
import com.nuvio.tv.core.sports.detail.StatRow
import com.nuvio.tv.core.sports.detail.TimelineItem
import com.nuvio.tv.core.sports.detail.TimelineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDetailTvModelTest {
    private val ref = GameRef("tsdb:1", "4387", "Hawks", "Celtics", 0L)
    private fun plays(n: Int) = GameBlock.Timeline(SourceId.ESPN, (1..n).map { TimelineItem("$it'", null, SideKey.HOME, "play $it", TimelineKind.SCORE) })
    private val base = GameDetail.skeleton(ref).copy(
        state = GameState.LIVE, availability = Availability.FULL,
        header = GameHeader.Teams(Side("Hawks", score = "54"), Side("Celtics", score = "50")),
        live = LiveInfo(clock = "Q2 5:12", detail = null),
        blocks = listOf(
            GameBlock.Situation(SourceId.ESPN, "2nd & 7 at OPP 34"),
            GameBlock.WinProbability(SourceId.ESPN, 63.4),
            GameBlock.Linescore(SourceId.ESPN, listOf("Q1", "Q2"), listOf(listOf("30", "24"), listOf("28", "22"))),
            plays(8),
            GameBlock.Stats(SourceId.TSDB, listOf(StatRow("Shots", "1", "2"))),
            GameBlock.Lineup(SourceId.TSDB, emptyList()),
            GameBlock.Standings(SourceId.TSDB, emptyList()),
            GameBlock.Results(SourceId.TSDB, emptyList(), emptyList()),
        ),
    )

    @Test fun `header shows both sides scores and live clock`() {
        val m = GameDetailTvModel.from(base, nowMs = 0)
        assertEquals("home", "Hawks", m.sides[0].name)
        assertEquals("score", "54", m.sides[0].score)
        assertEquals("live line", "Q2 5:12", m.liveLine)
        assertTrue("is live", m.isLive)
    }
    @Test fun `tv keeps situation win probability linescore and latest plays`() {
        val m = GameDetailTvModel.from(base, 0)
        assertEquals("situation", "2nd & 7 at OPP 34", m.situation)
        assertEquals("win prob rounds", 63, m.homeWinPercent)
        assertEquals("linescore columns", listOf("Q1", "Q2"), m.linescore!!.columns)
        assertEquals("latest plays are newest first and capped", listOf("play 8", "play 7", "play 6", "play 5", "play 4"), m.latestPlays.map { it.text })
    }
    @Test fun `dense tables never reach the tv model`() {
        val m = GameDetailTvModel.from(base, 0)
        assertEquals("no extra blocks", setOf(BlockType.SITUATION, BlockType.WIN_PROBABILITY, BlockType.LINESCORE, BlockType.TIMELINE), m.shownBlocks)
    }
    @Test fun `absent blocks are null not empty`() {
        val m = GameDetailTvModel.from(base.copy(blocks = emptyList()), 0)
        assertNull("situation", m.situation)
        assertNull("win", m.homeWinPercent)
        assertNull("linescore", m.linescore)
        assertTrue("plays", m.latestPlays.isEmpty())
    }
    @Test fun `non live games have no live line`() {
        val m = GameDetailTvModel.from(base.copy(state = GameState.FINAL, live = LiveInfo(detail = "Final")), 0)
        assertEquals("final label", "Final", m.liveLine)
        assertEquals("not live", false, m.isLive)
    }
    @Test fun `staleness label appears only after 20 seconds`() {
        val withAsOf = base.copy(asOf = mapOf(SourceId.ESPN to 1_000_000L))
        assertNull("fresh", GameDetailTvModel.from(withAsOf, 1_015_000).updatedLabel)
        assertEquals("2 min", "Updated 2 min ago", GameDetailTvModel.from(withAsOf, 1_000_000 + 125_000).updatedLabel)
        assertEquals("seconds", "Updated 45 s ago", GameDetailTvModel.from(withAsOf, 1_045_000).updatedLabel)
    }
    @Test fun `event style headers become a single title`() {
        val m = GameDetailTvModel.from(base.copy(header = GameHeader.Event("Brazilian GP", "Race", "Interlagos")), 0)
        assertEquals("title", "Brazilian GP", m.title)
        assertTrue("no sides", m.sides.isEmpty())
    }
    @Test fun `win probability is clamped`() {
        val m = GameDetailTvModel.from(base.copy(blocks = listOf(GameBlock.WinProbability(SourceId.ESPN, 140.0))), 0)
        assertEquals("clamped", 100, m.homeWinPercent)
    }

    @Test fun `period and clock read as 2nd dot 0 colon 00`() =
        assertEquals("line", "2nd · 0:00", GameDetailTvModel.liveLine(LiveInfo(clock = "0:00", periodLabel = "2nd")))
    @Test fun `a pause shows its name`() {
        assertEquals("intermission", "Intermission", GameDetailTvModel.liveLine(LiveInfo(clock = "0:00", periodLabel = "2nd", detail = "Intermission")))
        assertEquals("halftime", "Halftime", GameDetailTvModel.liveLine(LiveInfo(detail = "Halftime")))
        assertEquals("end of period", "End of 2nd", GameDetailTvModel.liveLine(LiveInfo(detail = "End of 2nd")))
    }
    @Test fun `bare clock or detail still render`() {
        assertEquals("clock", "78'", GameDetailTvModel.liveLine(LiveInfo(clock = "78'")))
        assertEquals("detail", "Final", GameDetailTvModel.liveLine(LiveInfo(detail = "Final")))
        assertNull("nothing", GameDetailTvModel.liveLine(null))
    }
    @Test fun `team names display as abbreviations when known`() {
        val d = base.copy(header = GameHeader.Teams(Side("Washington Capitals", short = "WSH", score = "3"), Side("Florida Panthers", score = "2")))
        val m = GameDetailTvModel.from(d, 0)
        assertEquals("short", "WSH", m.sides[0].tvName)
        assertEquals("full when no short", "Florida Panthers", m.sides[1].tvName)
    }
    @Test fun `plays carry the team tag of the side that made them`() {
        val d = base.copy(
            header = GameHeader.Teams(Side("Hawks", short = "ATL"), Side("Celtics", short = "BOS")),
            blocks = listOf(GameBlock.Timeline(SourceId.ESPN, listOf(
                TimelineItem("1'", null, SideKey.AWAY, "goal", TimelineKind.GOAL), TimelineItem("2'", null, null, "note", null),
            ))),
        )
        val p = GameDetailTvModel.from(d, 0).latestPlays
        assertEquals("untagged note first (newest)", null, p[0].tag)
        assertEquals("away tag", "BOS", p[1].tag)
    }
}
