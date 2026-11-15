package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.Availability
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameHeader
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.LeaderItem
import com.nuvio.tv.core.sports.detail.LineupPlayer
import com.nuvio.tv.core.sports.detail.LineupSide
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.SideKey
import com.nuvio.tv.core.sports.detail.SourceId
import com.nuvio.tv.core.sports.detail.StandingRow
import com.nuvio.tv.core.sports.detail.StandingsGroup
import com.nuvio.tv.core.sports.detail.StandingsTableRow
import com.nuvio.tv.core.sports.detail.StatRow
import com.nuvio.tv.core.sports.detail.TimelineItem
import com.nuvio.tv.core.sports.detail.TimelineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val E = SourceId.ESPN

internal fun detailOf(
    vararg blocks: GameBlock,
    home: Side = Side("Home", score = "10"), away: Side = Side("Away", score = "7"),
    state: GameState = GameState.LIVE, id: String = "espn:1",
) = GameDetail(
    gameId = id, sport = "Basketball", leagueId = "1", leagueName = "NBA", profile = "basketball", round = null, venue = null,
    startsAtMs = 0, state = state, header = GameHeader.Teams(home, away), live = null, blocks = blocks.toList(), sources = emptyMap(),
    sourceStatus = emptyMap(), availability = Availability.FULL, asOf = emptyMap(),
)

class GameScreenPoliciesTest {
    private val play = TimelineItem(time = "5:12", period = "2nd Quarter", side = SideKey.HOME, text = "Layup", kind = TimelineKind.SCORE, id = "p1")
    private val plays = GameBlock.Plays(E, listOf(play))
    private val timeline = GameBlock.Timeline(E, listOf(play))
    private val stats = GameBlock.Stats(E, listOf(StatRow("Rebounds", "40", "35")))
    private val leaders = GameBlock.Leaders(E, listOf(LeaderItem("Points", "A. Player", "30", SideKey.HOME)))
    private val lineup = GameBlock.Lineup(E, listOf(LineupSide("4-3-3", listOf(LineupPlayer("1", "K", "G", true)))))
    private val table = GameBlock.StandingsTable(E, listOf(StandingsGroup("East", listOf("W", "L"), listOf(StandingsTableRow("1", "Home", listOf("5", "1"), true)))))
    private val linescore = GameBlock.Linescore(E, listOf("Q1", "T"), listOf(listOf("1", "1"), listOf("2", "2")))

    // ---- GameSectionsPolicy ----
    @Test fun `sections - overview first and each present block opens its own section`() {
        val d = detailOf(linescore, plays, stats, leaders, lineup, table)
        assertEquals("order", listOf(GameSection.OVERVIEW, GameSection.PLAYS, GameSection.STATS, GameSection.LEADERS, GameSection.LINEUPS, GameSection.STANDINGS), GameSectionsPolicy.sections(d))
    }

    @Test fun `sections - no blocks is just the overview`() {
        assertEquals("only overview", listOf(GameSection.OVERVIEW), GameSectionsPolicy.sections(detailOf()))
    }

    @Test fun `sections - a section disappears when its block is empty`() {
        val d = detailOf(GameBlock.Plays(E, emptyList()), GameBlock.Stats(E, emptyList()), GameBlock.Leaders(E, emptyList()),
            GameBlock.StandingsTable(E, emptyList()), GameBlock.Lineup(E, listOf(LineupSide(null, emptyList()))))
        assertEquals("overview only", listOf(GameSection.OVERVIEW), GameSectionsPolicy.sections(d))
    }

    @Test fun `sections - plays fall back to the scoring timeline and standings to the soccer table`() {
        val d = detailOf(timeline, GameBlock.Standings(E, listOf(StandingRow("1", "Home", "10", "25"))))
        assertEquals("sections", listOf(GameSection.OVERVIEW, GameSection.PLAYS, GameSection.STANDINGS), GameSectionsPolicy.sections(d))
        assertEquals("timeline fills plays", listOf<GameBlock>(timeline), GameSectionsPolicy.blocksFor(GameSection.PLAYS, d))
        val both = detailOf(plays, timeline)
        assertEquals("full list wins", listOf<GameBlock>(plays), GameSectionsPolicy.blocksFor(GameSection.PLAYS, both))
        assertTrue("summary not repeated on overview", GameSectionsPolicy.blocksFor(GameSection.OVERVIEW, both).none { it is GameBlock.Timeline })
    }

    @Test fun `sections - linescore form and win chance stay on the overview`() {
        val win = GameBlock.WinProbability(E, 60.0)
        val form = GameBlock.Form(E, listOf("W"), listOf("L"))
        val d = detailOf(linescore, win, form, stats)
        assertEquals("overview blocks", listOf<GameBlock>(linescore, win, form), GameSectionsPolicy.blocksFor(GameSection.OVERVIEW, d))
    }

    // ---- StandingsWindow ----
    private fun rows(n: Int, teams: Set<Int>) = (1..n).map { StandingsTableRow("t$it", "Team $it", listOf("$it"), it in teams) }
    private fun block(vararg groups: List<StandingsTableRow>) =
        GameBlock.StandingsTable(E, groups.mapIndexed { i, r -> StandingsGroup("G$i", listOf("#"), r) })

    @Test fun `standings window - one group keeps both game teams for 3 5 and 7`() {
        val b = block(rows(16, setOf(2, 11)))
        for (max in listOf(3, 5, 7)) {
            val w = StandingsWindow.window(b, max)
            val kept = w.groups.single().rows
            assertTrue("max $max keeps both teams", kept.count { it.isGameTeam } == 2)
            assertTrue("max $max row count", kept.size <= max)
            assertEquals("max $max table order", kept.sortedBy { it.teamId!!.drop(1).toInt() }, kept)
        }
    }

    @Test fun `standings window - two groups drop the one without a game team when short`() {
        val b = block(rows(8, setOf(3)), rows(8, emptySet()), rows(8, setOf(5)))
        val w = StandingsWindow.window(b, 5)
        assertEquals("only groups with a team", listOf("G0", "G2"), w.groups.map { it.name })
        assertTrue("budget", w.groups.sumOf { it.rows.size } <= 5)
        assertTrue("teams kept", w.groups.all { g -> g.rows.any { it.isGameTeam } })
    }

    @Test fun `standings window - a table that fits is unchanged and zero rows is empty`() {
        val b = block(rows(4, setOf(1)))
        assertEquals("unchanged", b, StandingsWindow.window(b, 7))
        assertTrue("zero", StandingsWindow.window(b, 0).groups.isEmpty())
    }

    // ---- GameMomentPolicy ----
    @Test fun `moments - open prev null different game and entering live yield nothing`() {
        val d = detailOf()
        assertTrue("prev null", GameMomentPolicy.diff(null, d).isEmpty())
        assertTrue("other game", GameMomentPolicy.diff(detailOf(id = "espn:2"), detailOf(home = Side("Home", score = "12"))).isEmpty())
        assertTrue("upcoming to live", GameMomentPolicy.diff(detailOf(state = GameState.UPCOMING), detailOf(home = Side("Home", score = "12"))).isEmpty())
    }

    @Test fun `moments - score 1 to 2 gives one ScoreChange for the right side`() {
        val m = GameMomentPolicy.diff(detailOf(home = Side("H", score = "1")), detailOf(home = Side("H", score = "2")))
        assertEquals("one moment", listOf<Moment>(Moment.ScoreChange(SideKey.HOME, "2")), m)
        val a = GameMomentPolicy.diff(detailOf(away = Side("A", score = "1")), detailOf(away = Side("A", score = "3")))
        assertEquals("away side", listOf<Moment>(Moment.ScoreChange(SideKey.AWAY, "3")), a)
    }

    @Test fun `moments - an unchanged snapshot yields nothing`() {
        assertTrue("same", GameMomentPolicy.diff(detailOf(plays, GameBlock.WinProbability(E, 55.0)), detailOf(plays, GameBlock.WinProbability(E, 55.0))).isEmpty())
    }

    @Test fun `moments - win chance move and a new first play`() {
        val m = GameMomentPolicy.diff(detailOf(GameBlock.WinProbability(E, 55.0)), detailOf(GameBlock.WinProbability(E, 61.0)))
        assertEquals("win move", listOf<Moment>(Moment.WinChanceMove(55, 61)), m)
        val p2 = play.copy(id = "p2", text = "Dunk")
        val n = GameMomentPolicy.diff(detailOf(GameBlock.Plays(E, emptyList())), detailOf(GameBlock.Plays(E, listOf(p2))))
        assertEquals("new first play once", listOf<Moment>(Moment.NewPlay(p2)), n)
        val again = GameMomentPolicy.diff(detailOf(GameBlock.Plays(E, listOf(p2))), detailOf(GameBlock.Plays(E, listOf(p2, play))))
        assertTrue("same newest play is nothing", again.isEmpty())
    }

    @Test fun `moments - the newest scoring-timeline entry is the LAST one`() {
        val old = play.copy(id = null, time = "1:00", text = "A"); val fresh = play.copy(id = null, time = "2:00", text = "B")
        val m = GameMomentPolicy.diff(detailOf(GameBlock.Timeline(E, listOf(old))), detailOf(GameBlock.Timeline(E, listOf(old, fresh))))
        assertEquals("timeline newest is last", listOf<Moment>(Moment.NewPlay(fresh)), m)
    }

    // ---- TeamColor ----
    private val surface = "14161a"

    @Test fun `team colour - a dark purple is lifted to contrast 3 and a bright gold is untouched`() {
        val lifted = TeamColor.forDark("461d7c", surface)!!
        assertTrue("contrast >= 3.0", TeamColor.contrastOf(lifted, surface) >= 3.0)
        assertEquals("gold unchanged", "f2b200", TeamColor.forDark("f2b200", surface))
        assertNull("garbage", TeamColor.forDark("zzz", surface)); assertNull("null", TeamColor.forDark(null, surface))
    }

    @Test fun `team colour - near identical colours push the away side to its alternate`() {
        val (h, a) = TeamColor.pair(Side("H", color = "f2b200"), Side("A", color = "f2b210", altColor = "ffffff"), surface)
        assertEquals("home", "f2b200", h); assertEquals("away alt", "ffffff", a)
        val (_, far) = TeamColor.pair(Side("H", color = "f2b200"), Side("A", color = "1d4ed8", altColor = "ffffff"), surface)
        assertNotNull("far colours kept", far); assertFalse("not the alt", far == "ffffff")
    }

    // ---- MotionSpec, lowerIsBetter ----
    @Test fun `motion tokens match the material values`() {
        assertEquals(200, MotionSpec.QUICK_MS); assertEquals(500, MotionSpec.MOVE_MS)
        assertEquals(600, MotionSpec.COUNT_MS); assertEquals(700, MotionSpec.PULSE_MS)
    }

    @Test fun `lowerIsBetter lists turnovers fouls penalty minutes giveaways`() {
        listOf("Turnovers", " fouls ", "Penalty Minutes", "Giveaways").forEach { assertTrue(it, GameDetailUiPolicy.lowerIsBetter(it)) }
        listOf("Assists", "Rebounds", "Takeaways").forEach { assertFalse(it, GameDetailUiPolicy.lowerIsBetter(it)) }
    }
}
