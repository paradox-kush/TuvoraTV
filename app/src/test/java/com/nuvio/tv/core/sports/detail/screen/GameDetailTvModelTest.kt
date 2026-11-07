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
import com.nuvio.tv.core.sports.detail.LeaderItem
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
        assertEquals("latest plays are newest first", listOf("play 8", "play 7", "play 6", "play 5", "play 4", "play 3", "play 2", "play 1"), m.latestPlays.map { it.text })
    }
    @Test fun `lineups standings and results tables never reach the tv model`() {
        val m = GameDetailTvModel.from(base, 0)
        assertEquals("shown", setOf(BlockType.SITUATION, BlockType.WIN_PROBABILITY, BlockType.LINESCORE, BlockType.STATS, BlockType.TIMELINE), m.shownBlocks)
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
        assertEquals("pill", "FINAL", m.pill)
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

    // ---- redesign: scoreboard pill, by-period table, compact stats, performers, upcoming ----

    private val teams = GameHeader.Teams(Side("Nashville Predators", short = "NSH", badgeUrl = "n.png", score = "2"), Side("St. Louis Blues", short = "STL", badgeUrl = "s.png", score = "1"))

    @Test fun `live pill ties the state to the period and clock`() {
        val m = GameDetailTvModel.from(base.copy(header = teams, live = LiveInfo(clock = "10:12", periodLabel = "3rd")), 0)
        assertEquals("pill", "LIVE · 3rd 10:12", m.pill)
    }
    @Test fun `upcoming has no pill and no live line`() {
        val m = GameDetailTvModel.from(base.copy(state = GameState.UPCOMING, live = null), 0)
        assertNull("pill", m.pill)
    }
    @Test fun `by period table has badges short names totals and the current period`() {
        val ls = GameBlock.Linescore(SourceId.ESPN, listOf("P1", "P2", "P3"), listOf(listOf("1", "1", ""), listOf("0", "1", "")))
        val m = GameDetailTvModel.from(base.copy(header = teams, blocks = listOf(ls)), 0)
        val t = m.linescore!!
        assertEquals("labels", listOf("NSH", "STL"), t.rows.map { it.label })
        assertEquals("badges", listOf("n.png", "s.png"), t.rows.map { it.badgeUrl })
        assertEquals("total column", "T", t.totalLabel)
        assertEquals("totals from the header score", listOf("2", "1"), t.rows.map { it.total })
        assertEquals("current period is the last one with data", 1, t.currentIndex)
    }
    @Test fun `totals fall back to the sum when the header has no score`() {
        val ls = GameBlock.Linescore(SourceId.ESPN, listOf("Q1", "Q2"), listOf(listOf("30", "24"), listOf("28", "22")))
        val m = GameDetailTvModel.from(base.copy(header = GameHeader.Teams(Side("A"), Side("B")), blocks = listOf(ls)), 0)
        assertEquals("sums", listOf("54", "50"), m.linescore!!.rows.map { it.total })
    }
    @Test fun `final games emphasise the winner and have no current period`() {
        val ls = GameBlock.Linescore(SourceId.ESPN, listOf("P1", "P2", "P3"), listOf(listOf("1", "1", "1"), listOf("0", "1", "0")))
        val m = GameDetailTvModel.from(base.copy(state = GameState.FINAL, header = GameHeader.Teams(Side("A", score = "3"), Side("B", score = "1")), blocks = listOf(ls)), 0)
        assertNull("no current period", m.linescore!!.currentIndex)
        assertEquals("winner leads", listOf(true, false), m.linescore!!.rows.map { it.leading })
    }
    @Test fun `baseball keeps its own R H E columns and adds no T`() {
        val ls = GameBlock.Linescore(SourceId.ESPN, listOf("1", "2", "R", "H", "E"), listOf(listOf("0", "1", "1", "4", "0"), listOf("0", "0", "0", "2", "1")))
        val m = GameDetailTvModel.from(base.copy(blocks = listOf(ls)), 0)
        assertNull("no T", m.linescore!!.totalLabel)
        assertEquals("no totals", listOf<String?>(null, null), m.linescore!!.rows.map { it.total })
    }
    @Test fun `compact stats are numeric only, capped at three, with bar fractions`() {
        val rows = listOf(
            StatRow("Shots", "30", "10"), StatRow("Hits", "5", "5"), StatRow("FG", "46-91", "40-88"), StatRow("Possession", "60", "40", "%"),
            StatRow("PP", "2", "0"), StatRow("Fouls", "9", "11"),
        )
        val m = GameDetailTvModel.from(base.copy(blocks = listOf(GameBlock.Stats(SourceId.ESPN, rows))), 0)
        assertEquals("three numeric rows", listOf("Shots", "Hits", "Possession"), m.stats.map { it.label })
        assertEquals("share", 0.75f, m.stats[0].homeFraction, 0.001f)
        assertEquals("even", 0.5f, m.stats[1].homeFraction, 0.001f)
        assertEquals("unit kept", "60%", m.stats[2].home)
        assertEquals("all zero is even", 0.5f, GameDetailTvModel.from(base.copy(blocks = listOf(GameBlock.Stats(SourceId.ESPN, listOf(StatRow("X", "0", "0"))))), 0).stats[0].homeFraction, 0.001f)
    }
    @Test fun `top performers keep one per category with a prettified label and team tag`() {
        val items = listOf(
            LeaderItem("goals", "Jack Drury", "2", SideKey.HOME), LeaderItem("goals", "Other", "1", SideKey.AWAY),
            LeaderItem("passingYards", "Q B", "242", SideKey.AWAY), LeaderItem("assists", "A B", "3", null), LeaderItem("points", "C D", "4", SideKey.HOME),
        )
        val m = GameDetailTvModel.from(base.copy(header = teams, blocks = listOf(GameBlock.Leaders(SourceId.ESPN, items))), 0)
        assertEquals("one per category, three max", listOf("Goals", "Passing yards", "Assists"), m.leaders.map { it.category })
        assertEquals("tag", "NSH", m.leaders[0].tag)
        assertNull("no side no tag", m.leaders[2].tag)
    }
    @Test fun `upcoming games show kickoff venue and form instead of an empty table`() {
        val start = java.time.Instant.parse("2026-11-08T00:30:00Z").toEpochMilli()
        val d = base.copy(
            state = GameState.UPCOMING, startsAtMs = start, venue = "Bridgestone Arena", live = null,
            blocks = listOf(GameBlock.Form(SourceId.TSDB, listOf("W", "L", "W", "W", "D", "L"), listOf("L", "L"))),
        )
        val m = GameDetailTvModel.from(d, 0, zone = java.time.ZoneId.of("UTC"), locale = java.util.Locale.US)
        assertEquals("kickoff", "Sun 8 Nov · 12:30 AM", m.kickoffLabel)
        assertEquals("venue", "Bridgestone Arena", m.venue)
        assertEquals("last five only", listOf("L", "W", "W", "D", "L"), m.form!!.home)
        assertNull("no linescore", m.linescore)
    }
    @Test fun `kickoff is only for upcoming games`() =
        assertNull("live", GameDetailTvModel.from(base, 0, java.time.ZoneId.of("UTC"), java.util.Locale.US).kickoffLabel)
    @Test fun `scoring plays are marked and tagged`() {
        val tl = GameBlock.Timeline(SourceId.ESPN, listOf(
            TimelineItem("1:00", null, SideKey.HOME, "Goal by X", TimelineKind.GOAL),
            TimelineItem("2:00", null, SideKey.AWAY, "Hit by Y", null),
        ))
        val p = GameDetailTvModel.from(base.copy(header = teams, blocks = listOf(tl)), 0).latestPlays
        assertEquals("newest first, not scoring", false, p[0].scoring)
        assertEquals("goal marked", true, p[1].scoring)
        assertEquals("tag", "NSH", p[1].tag)
    }

    // ---- size tuning: condensed plays, merged performers ----

    @Test fun `a play keeps the scorer and only the first assist`() {
        assertEquals(
            "Pavel Dorofeyev Goal (6) Backhand · assist Mika Zibanejad",
            GameDetailTvModel.condensePlay("Pavel Dorofeyev Goal (6) Backhand, assists: Mika Zibanejad (12), Alexis Lafreniere (11)"),
        )
        assertEquals("one assist", "Nick Perbix Goal (2) Wrist Shot · assist Steven Stamkos", GameDetailTvModel.condensePlay("Nick Perbix Goal (2) Wrist Shot, assists: Steven Stamkos (9)"))
        assertEquals("unassisted untouched", "Ross Colton Goal (5) Snap Shot, unassisted", GameDetailTvModel.condensePlay("Ross Colton Goal (5) Snap Shot, unassisted"))
        assertEquals("dangling assists", "Goal (1)", GameDetailTvModel.condensePlay("Goal (1), assists:"))
    }
    @Test fun `plays in the model are condensed`() {
        val tl = GameBlock.Timeline(SourceId.ESPN, listOf(TimelineItem("1:00", null, null, "A Goal (1), assists: B C (2), D E (3)", TimelineKind.GOAL)))
        assertEquals("A Goal (1) · assist B C", GameDetailTvModel.from(base.copy(blocks = listOf(tl)), 0).latestPlays[0].text)
    }
    @Test fun `one player leading several categories is shown once`() {
        val items = listOf(
            LeaderItem("goals", "Mika Zibanejad", "0", SideKey.HOME), LeaderItem("assists", "Mika Zibanejad", "2", SideKey.HOME),
            LeaderItem("points", "Mika Zibanejad", "2", SideKey.HOME), LeaderItem("rebounds", "Other Guy", "9", SideKey.AWAY),
        )
        val m = GameDetailTvModel.from(base.copy(header = teams, blocks = listOf(GameBlock.Leaders(SourceId.ESPN, items))), 0)
        assertEquals("two rows, not four", 2, m.leaders.size)
        assertEquals("merged line", "0 G, 2 A, 2 P", m.leaders[0].value)
        assertEquals("name once", "Mika Zibanejad", m.leaders[0].athlete)
        assertEquals("single stays", "Other Guy", m.leaders[1].athlete)
    }
}
