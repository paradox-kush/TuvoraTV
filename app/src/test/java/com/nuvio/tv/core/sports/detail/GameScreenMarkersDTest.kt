package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.ChartMarker
import com.nuvio.tv.core.sports.detail.screen.ChartMarkers
import com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel
import com.nuvio.tv.core.sports.detail.screen.GameDetailUiPolicy
import com.nuvio.tv.core.sports.detail.screen.GameMomentPolicy
import com.nuvio.tv.core.sports.detail.screen.Moment
import com.nuvio.tv.core.sports.detail.screen.detailOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase D (spec 05): chart markers, period dividers, the chartMarkers switch and the remaining moments. */
class GameScreenMarkersDTest {
    private val t0 = 1_800_000_000_000L
    private fun series(n: Int, stepMs: Long = 30_000L, home: (Int) -> Int = { 40 + it % 30 }) = List(n) { WinPoint(t0 + it * stepMs, home(it)) }
    private fun play(atMs: Long, kind: TimelineKind, text: String = "x", side: SideKey? = SideKey.HOME, id: String = "p$atMs", period: String? = null) =
        TimelineItem(time = "1:00", period = period, side = side, text = text, kind = kind, id = id, wallclockMs = atMs)

    @Test fun `markers - a goal lands on the nearest series point and sits on the drawn line`() {
        val h = series(20)
        val m = ChartMarkers.markers(h, listOf(play(t0 + 10 * 30_000L + 4_000L, TimelineKind.GOAL)), "Ice Hockey").single()
        assertEquals("G", m.label); assertEquals(SideKey.HOME, m.side)
        assertEquals(10f / 19f, m.x, 0.0001f); assertEquals("y is the series value", h[10].home / 100f, m.y, 0.0001f)
    }
    @Test fun `markers - no series point within 90 seconds means no marker and never an estimate`() {
        val sparse = series(10, stepMs = 300_000L)
        assertEquals(emptyList<ChartMarker>(), ChartMarkers.markers(sparse, listOf(play(t0 + 150_000L, TimelineKind.GOAL)), "Ice Hockey"))
        assertEquals(1, ChartMarkers.markers(sparse, listOf(play(t0 + 300_000L + 89_000L, TimelineKind.GOAL)), "Ice Hockey").size)
        assertEquals(emptyList<ChartMarker>(), ChartMarkers.markers(sparse, listOf(play(t0 + 300_000L + 91_000L, TimelineKind.GOAL)), "Ice Hockey"))
        assertEquals("tolerance is a parameter", 1, ChartMarkers.markers(sparse, listOf(play(t0 + 150_000L, TimelineKind.GOAL)), "Ice Hockey", toleranceMs = 150_000L).size)
    }
    @Test fun `markers - a play with no wall clock never gets one`() =
        assertEquals(emptyList<ChartMarker>(), ChartMarkers.markers(series(20), listOf(play(t0, TimelineKind.GOAL).copy(wallclockMs = null)), "Ice Hockey"))

    @Test fun `markers - at most max and the newest ones win`() {
        val h = series(100)
        val plays = (1..12).map { play(t0 + it * 6 * 30_000L, TimelineKind.GOAL, id = "g$it") }
        val m = ChartMarkers.markers(h, plays, "Ice Hockey")
        assertEquals(8, m.size); assertEquals((5..12).map { "g$it" }, m.map { it.playId })
        assertEquals("TV draws 5", 5, ChartMarkers.markers(h, plays, "Ice Hockey", max = ChartMarkers.TV_MAX_MARKERS).size)
    }
    @Test fun `markers - labels per sport and what is not marked`() {
        val h = series(20)
        fun labels(sport: String, vararg p: TimelineItem) = ChartMarkers.markers(h, p.toList().mapIndexed { i, it -> it.copy(wallclockMs = t0 + (2 + i * 5) * 30_000L, id = "i$i") }, sport).map { it.label }
        assertEquals(listOf("TD", "FG"), labels("American Football", play(0, TimelineKind.SCORE, "J.Doe pass for 12 yards TOUCHDOWN"), play(0, TimelineKind.SCORE, "A.Kicker 42 yard field goal is GOOD")))
        assertEquals(emptyList<String>(), labels("American Football", play(0, TimelineKind.SCORE, "A.Kicker extra point is GOOD"), play(0, TimelineKind.PENALTY, "Holding")))
        assertEquals(listOf("G", "PPG"), labels("Ice Hockey", play(0, TimelineKind.GOAL), play(0, TimelineKind.PPG)))
        assertEquals(listOf("G", "RC"), labels("Soccer", play(0, TimelineKind.GOAL), play(0, TimelineKind.CARD, "Ismael Thiago (Brentford) is shown the red card.")))
        assertEquals(emptyList<String>(), labels("Soccer", play(0, TimelineKind.CARD, "Kevin Schade (Brentford) is shown the yellow card."), play(0, TimelineKind.SUB)))
        assertEquals("basketball has none", emptyList<String>(), labels("Basketball", play(0, TimelineKind.SCORE, "Layup")))
        assertEquals(emptyList<String>(), labels("Cricket", play(0, TimelineKind.GOAL)))
    }
    @Test fun `markers - the label sits above the line from 50 percent`() {
        assertTrue(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.5f, "G", SideKey.HOME))); assertTrue(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.9f, "G", SideKey.HOME)))
        assertFalse(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.49f, "G", SideKey.HOME)))
    }
    @Test fun `markers - an overlapping older marker is dropped`() {
        val h = series(100)
        val plays = listOf(play(t0 + 40 * 30_000L, TimelineKind.GOAL, id = "old"), play(t0 + 41 * 30_000L, TimelineKind.GOAL, id = "new"), play(t0 + 80 * 30_000L, TimelineKind.GOAL, id = "far"))
        assertEquals(listOf("new", "far"), ChartMarkers.markers(h, plays, "Ice Hockey").map { it.playId })
    }
    @Test fun `markers - matched on the full series then placed on the drawn 120 point one`() {
        val h = series(300) { 30 + it % 50 }
        val drawn = GameDetailUiPolicy.chartPoints(h)!!
        assertEquals("drawn series is capped (spec 05: 120)", 120, drawn.size)
        val m = ChartMarkers.markers(h, listOf(play(t0 + 151 * 30_000L, TimelineKind.GOAL)), "Ice Hockey").single()
        val idx = Math.round(m.x * (drawn.size - 1))
        assertEquals("ON the drawn polyline", drawn[idx], m.y, 0.00001f)
        assertTrue("halfway through the game, x=${m.x}", Math.abs(m.x - 0.5f) < 0.03f)
    }
    @Test fun `markers - empty inputs`() {
        assertEquals(emptyList<ChartMarker>(), ChartMarkers.markers(emptyList(), listOf(play(t0, TimelineKind.GOAL)), "Ice Hockey"))
        assertEquals(emptyList<ChartMarker>(), ChartMarkers.markers(series(1), listOf(play(t0, TimelineKind.GOAL)), "Ice Hockey"))
        assertEquals(emptyList<ChartMarker>(), ChartMarkers.markers(series(20), emptyList(), "Ice Hockey"))
        assertTrue(ChartMarkers.dividers(emptyList(), listOf(play(t0, TimelineKind.PERIOD_START, period = "2nd"))).isEmpty())
        assertTrue(ChartMarkers.dividers(series(20), emptyList()).isEmpty())
    }

    private fun detailWith(switches: Set<String>, plays: GameBlock.Plays) =
        detailOf(GameBlock.WinProbability(SourceId.MARKET, 50.0, history = series(20)), plays, sport = "Ice Hockey").copy(uiSwitches = switches)

    @Test fun `switch - chartMarkers off removes markers and dividers and on brings them back`() {
        val plays = GameBlock.Plays(SourceId.ESPN, listOf(play(t0 + 5 * 30_000L, TimelineKind.GOAL)),
            chartItems = listOf(play(t0 + 5 * 30_000L, TimelineKind.GOAL), play(t0 + 9 * 30_000L, TimelineKind.PERIOD_START, period = "2nd")))
        assertTrue(ChartMarkers.markersFor(detailWith(emptySet(), plays)).isEmpty()); assertTrue(ChartMarkers.dividersFor(detailWith(emptySet(), plays)).isEmpty())
        assertEquals(1, ChartMarkers.markersFor(detailWith(setOf("chartMarkers"), plays)).size); assertEquals(1, ChartMarkers.dividersFor(detailWith(setOf("chartMarkers"), plays)).size)
    }
    @Test fun `switch - ESPN only win probability has no series so no markers`() {
        val d = detailOf(GameBlock.WinProbability(SourceId.ESPN, 50.0), GameBlock.Plays(SourceId.ESPN, emptyList(), chartItems = listOf(play(t0, TimelineKind.GOAL))), sport = "Ice Hockey").copy(uiSwitches = setOf("chartMarkers"))
        assertTrue(ChartMarkers.markersFor(d).isEmpty())
    }
    @Test fun `the model carries markers on the chart only with the switch on and caps them at 5 on TV`() {
        val goals = (1..8).map { play(t0 + it * 2 * 30_000L, TimelineKind.GOAL, id = "g$it") }
        val d = detailOf(GameBlock.WinProbability(SourceId.MARKET, 50.0, history = series(40)), GameBlock.Plays(SourceId.ESPN, goals.reversed(), chartItems = goals), sport = "Ice Hockey")
        assertTrue("off", GameDetailTvModel.from(d, 0L).chart!!.markers.isEmpty())
        assertEquals("on, TV cap", 5, GameDetailTvModel.from(d.copy(uiSwitches = setOf("chartMarkers")), 0L).chart!!.markers.size)
    }

    @Test fun `dividers - starts become labelled x positions on the drawn series`() {
        val h = series(100)
        val d = ChartMarkers.dividers(h, listOf(play(t0 + 30 * 30_000L, TimelineKind.PERIOD_START, period = "2nd"), play(t0 + 60 * 30_000L, TimelineKind.PERIOD_START, period = "3rd")), "Ice Hockey")
        assertEquals(listOf("P2", "P3"), d.map { it.second }); assertEquals(30f / 99f, d[0].first, 0.0001f)
    }
    @Test fun `dividers - a feed with only period ends marks the start of the next period`() {
        val d = ChartMarkers.dividers(series(100), listOf(play(t0 + 25 * 30_000L, TimelineKind.PERIOD_END, period = "1st Quarter"), play(t0 + 50 * 30_000L, TimelineKind.PERIOD_END, period = "2nd Quarter")), "Basketball")
        assertEquals(listOf("Q2", "Q3"), d.map { it.second })
    }
    @Test fun `dividers - a start wins over the previous end and overtime reads OT`() {
        val plays = listOf(play(t0 + 29 * 30_000L, TimelineKind.PERIOD_END, period = "1st"), play(t0 + 30 * 30_000L, TimelineKind.PERIOD_START, period = "2nd"), play(t0 + 70 * 30_000L, TimelineKind.PERIOD_END, period = "3rd"))
        val d = ChartMarkers.dividers(series(100), plays, "Ice Hockey")
        assertEquals(listOf("P2", "OT"), d.map { it.second }); assertEquals(30f / 99f, d[0].first, 0.0001f)
    }
    @Test fun `dividers - soccer reads 45 and 90 and out-of-series or clockless boundaries are skipped`() {
        val h = series(100)
        assertEquals(listOf("45'", "90'"), ChartMarkers.dividers(h, listOf(play(t0 + 40 * 30_000L, TimelineKind.PERIOD_END, period = "1st Half"), play(t0 + 80 * 30_000L, TimelineKind.PERIOD_END, period = "2nd Half")), "Soccer").map { it.second })
        assertTrue(ChartMarkers.dividers(h, listOf(play(t0 + 9_000 * 30_000L, TimelineKind.PERIOD_START, period = "2nd")), "Ice Hockey").isEmpty())
        assertTrue(ChartMarkers.dividers(h, listOf(play(0, TimelineKind.PERIOD_START, period = "2nd").copy(wallclockMs = null)), "Ice Hockey").isEmpty())
        assertTrue("start of the game is no divider", ChartMarkers.dividers(h, listOf(play(t0 + 5 * 30_000L, TimelineKind.PERIOD_START, period = "1st")), "Ice Hockey").isEmpty())
    }

    // ---- assembler: every key event for the chart ----
    private val event = Json.parseToJsonElement("""{"id":"1","status":{"type":{"state":"in"}},"competitions":[{"competitors":[
        {"id":"10","homeAway":"home","team":{"id":"10","displayName":"H"}},{"id":"20","homeAway":"away","team":{"id":"20","displayName":"A"}}]}]}""")
    private fun nhlPlay(id: Int, type: String, text: String, period: Int, wall: String, team: String? = null, scoring: Boolean = false, strength: String? = null) =
        """{"id":"$id","type":{"text":"$type"},"text":"$text","period":{"number":$period,"displayValue":"${period}th"},"clock":{"displayValue":"1:00"},"wallclock":"$wall","scoringPlay":$scoring${team?.let { ",\"team\":{\"id\":\"$it\"}" } ?: ""}${strength?.let { ",\"strength\":{\"text\":\"$it\"}" } ?: ""}}"""

    @Test fun `plays block - chartItems keep every goal and period boundary even beyond the display cap`() {
        val plays = buildList {
            add(nhlPlay(1, "Period Start", "Start of 1st Period", 1, "2026-11-15T01:00:00Z"))
            add(nhlPlay(2, "Goal", "A Skater Goal (1)", 1, "2026-11-15T01:10:00Z", "10", true, "Even Strength"))
            add(nhlPlay(3, "Period End", "End of 1st Period", 1, "2026-11-15T01:20:00Z"))
            add(nhlPlay(4, "Period Start", "Start of 2nd Period", 2, "2026-11-15T01:40:00Z"))
            for (i in 5..80) add(nhlPlay(i, "Shot", "Shot $i", 2, "2026-11-15T02:00:00Z"))
            add(nhlPlay(81, "Goal", "B Skater Goal (2)", 2, "2026-11-15T02:10:00Z", "20", true, "Power Play"))
        }.joinToString(",")
        val b = EspnBlockAssembler(SportsFixtures.mapping).block(BlockType.PLAYS, "hockey", EspnDocs(event, Json.parseToJsonElement("""{"plays":[$plays]}"""))) as GameBlock.Plays
        assertEquals("display cap unchanged", 60, b.items.size)
        assertEquals(listOf(TimelineKind.PERIOD_START, TimelineKind.GOAL, TimelineKind.PERIOD_END, TimelineKind.PERIOD_START, TimelineKind.PPG), b.chartItems.map { it.kind })
        assertEquals(listOf(SideKey.HOME, SideKey.AWAY), b.chartItems.mapNotNull { it.side }); assertNotNull(b.chartItems.first().wallclockMs)
    }
    @Test fun `plays block - football scores and quarter ends, soccer goals red cards and halftime`() {
        val nfl = """{"drives":{"previous":[{"plays":[
            {"id":"1","type":{"text":"Passing Touchdown"},"text":"J.Doe pass to X for 12 yards TOUCHDOWN","period":{"number":1},"clock":{"displayValue":"9:00"},"wallclock":"2026-11-15T01:10:00Z","scoringPlay":true,"start":{"team":{"id":"10"}}},
            {"id":"2","type":{"text":"End Period"},"text":"END QUARTER 1","period":{"number":1},"clock":{"displayValue":"0:00"},"wallclock":"2026-11-15T01:20:00Z","scoringPlay":false},
            {"id":"3","type":{"text":"Rush"},"text":"run","period":{"number":2},"clock":{"displayValue":"14:00"},"wallclock":"2026-11-15T01:25:00Z","scoringPlay":false}]}]}}"""
        val fb = EspnBlockAssembler(SportsFixtures.mapping).block(BlockType.PLAYS, "football", EspnDocs(event, Json.parseToJsonElement(nfl))) as GameBlock.Plays
        assertEquals(listOf(TimelineKind.SCORE, TimelineKind.PERIOD_END), fb.chartItems.map { it.kind })
        val soccer = """{"commentary":[
            {"time":{"value":600.0,"displayValue":"10'"},"text":"Goal! 1-0","play":{"id":"a","type":{"text":"Goal"},"team":{"displayName":"H"},"period":{"number":1},"clock":{"displayValue":"10'"},"wallclock":"2026-11-15T01:10:00Z"}},
            {"time":{"value":1300.0,"displayValue":"22'"},"text":"Corner","play":{"id":"b","type":{"text":"Corner Awarded"},"team":{"displayName":"H"},"period":{"number":1},"clock":{"displayValue":"22'"},"wallclock":"2026-11-15T01:22:00Z"}},
            {"time":{"value":2400.0,"displayValue":"40'"},"text":"Player is shown the red card.","play":{"id":"c","type":{"text":"Red Card"},"team":{"displayName":"A"},"period":{"number":1},"clock":{"displayValue":"40'"},"wallclock":"2026-11-15T01:40:00Z"}},
            {"time":{"value":2700.0,"displayValue":"45'"},"text":"Halftime","play":{"id":"d","type":{"text":"Halftime"},"period":{"number":1},"clock":{"displayValue":"45'"},"wallclock":"2026-11-15T01:46:00Z"}}]}"""
        val sb = EspnBlockAssembler(SportsFixtures.mapping).block(BlockType.PLAYS, "soccer", EspnDocs(event, Json.parseToJsonElement(soccer))) as GameBlock.Plays
        assertEquals(listOf(TimelineKind.GOAL, TimelineKind.CARD, TimelineKind.PERIOD_END), sb.chartItems.map { it.kind })
        assertEquals(listOf(SideKey.HOME, SideKey.AWAY, null), sb.chartItems.map { it.side })
        assertTrue("halftime is a chart boundary, not a plays row", sb.items.none { it.kind == TimelineKind.PERIOD_END })
    }
    @Test fun `the resolver puts the on switches on the detail and the bundled mapping has chartMarkers on`() {
        assertEquals(setOf("chartMarkers"), SportsFixtures.mapping.uiSwitches)
    }

    // ---- moments ----
    private fun md(plays: GameBlock.Plays, switches: Set<String> = setOf("chartMarkers"), vararg extra: GameBlock) =
        detailOf(GameBlock.WinProbability(SourceId.MARKET, 50.0, history = series(40)), plays, *extra, sport = "Ice Hockey").copy(uiSwitches = switches)

    @Test fun `moments - a new chart marker pops once and only while the switch is on`() {
        val a = GameBlock.Plays(SourceId.ESPN, listOf(play(t0 + 5 * 30_000L, TimelineKind.GOAL, id = "g1")), chartItems = listOf(play(t0 + 5 * 30_000L, TimelineKind.GOAL, id = "g1")))
        val b = a.copy(chartItems = a.chartItems + play(t0 + 20 * 30_000L, TimelineKind.PPG, id = "g2", side = SideKey.AWAY))
        val d = GameMomentPolicy.diff(md(a), md(b)).filterIsInstance<Moment.MarkerAdded>()
        assertEquals(1, d.size); assertEquals("PPG", d.single().marker.label); assertEquals("g2", d.single().marker.playId)
        assertTrue("switch off", GameMomentPolicy.diff(md(a, emptySet()), md(b, emptySet())).filterIsInstance<Moment.MarkerAdded>().isEmpty())
        assertTrue(GameMomentPolicy.diff(md(b), md(b)).filterIsInstance<Moment.MarkerAdded>().isEmpty())
    }
    @Test fun `moments - a stat row changing and the leader flipping`() {
        fun d(rows: List<StatRow>) = md(GameBlock.Plays(SourceId.ESPN, emptyList()), setOf("chartMarkers"), GameBlock.Stats(SourceId.ESPN, rows))
        val prev = d(listOf(StatRow("Shots", "10", "12"), StatRow("Turnovers", "5", "3"), StatRow("Possession", "55%", "45%")))
        val next = d(listOf(StatRow("Shots", "13", "12"), StatRow("Turnovers", "4", "4"), StatRow("Possession", "55%", "45%")))
        val m = GameMomentPolicy.diff(prev, next).filterIsInstance<Moment.StatChange>().associateBy { it.label }
        assertEquals(setOf("Shots", "Turnovers"), m.keys); assertTrue(m.getValue("Shots").leaderChanged); assertTrue(m.getValue("Turnovers").leaderChanged)
    }
    @Test fun `moments - non numeric stats and a row that appears are not animated as a flip`() {
        fun d(rows: List<StatRow>) = md(GameBlock.Plays(SourceId.ESPN, emptyList()), setOf("chartMarkers"), GameBlock.Stats(SourceId.ESPN, rows))
        val m = GameMomentPolicy.diff(d(listOf(StatRow("3rd down", "4/11", "5/9"))), d(listOf(StatRow("3rd down", "5/12", "5/9"), StatRow("Shots", "1", "0")))).filterIsInstance<Moment.StatChange>()
        assertEquals(listOf("3rd down"), m.map { it.label }); assertFalse(m.single().leaderChanged)
    }
}
