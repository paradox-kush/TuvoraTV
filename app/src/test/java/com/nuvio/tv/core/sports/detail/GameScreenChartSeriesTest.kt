package com.nuvio.tv.core.sports.detail

import com.nuvio.tv.core.sports.detail.screen.ChartMarker
import com.nuvio.tv.core.sports.detail.screen.ChartMarkers
import com.nuvio.tv.core.sports.detail.screen.GameDetailTvModel
import com.nuvio.tv.core.sports.detail.screen.GameDetailUiPolicy
import com.nuvio.tv.core.sports.detail.screen.WinChanceSeries
import com.nuvio.tv.core.sports.detail.screen.detailOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ONE series/axis rule for the win-chance chart (hand-port of core's GameScreenChartSeriesTest; JUnit argument order). */
class GameScreenChartSeriesTest {
    private fun two(home: Int, history: List<WinPoint> = emptyList()) = GameBlock.WinProbability(SourceId.ESPN, home.toDouble(), history = history)
    private fun three(home: Int, draw: Int, away: Int, history: List<WinPoint> = emptyList()) =
        GameBlock.WinProbability(SourceId.ESPN, home.toDouble(), away.toDouble(), draw.toDouble(), history = history)

    @Test fun `the drawn series is capped at 120 points`() {
        val many = (1L..500L).map { WinPoint(it, (it % 101).toInt()) }
        assertEquals("cap", 120, GameDetailUiPolicy.chartSeries(many)!!.size)
    }

    @Test fun `two-way - the line is the leaders share and the reference is 50 percent`() {
        val w = two(35, listOf(WinPoint(1, 30), WinPoint(2, 35)))
        val spec = WinChanceSeries.specOf(w)
        assertEquals("leader", "away", spec.leaderSide)
        assertFalse("two-way", spec.threeWay)
        assertEquals("reference", 0.5f, spec.reference, 0f)
        assertEquals("plot", listOf(0.7f, 0.65f), WinChanceSeries.plot(w.history, spec))
    }

    @Test fun `three-way - the line is the leaders FULL chance so it ends at the big number`() {
        val w = three(45, 30, 25, listOf(WinPoint(1, 50, draw = 30), WinPoint(2, 64, draw = 30)))
        val spec = WinChanceSeries.specOf(w)
        assertEquals("leader", "home", spec.leaderSide)
        assertTrue("three-way", spec.threeWay)
        val p = WinChanceSeries.plot(w.history, spec)
        assertEquals("first", 0.35f, p[0], 0.0001f)
        assertEquals("second", 0.448f, p[1], 0.0001f)
    }

    @Test fun `three-way - the reference is an even third so a 45 percent leader does not read as losing`() {
        val w = three(45, 30, 25, listOf(WinPoint(1, 64, draw = 30), WinPoint(2, 64, draw = 30)))
        val spec = WinChanceSeries.specOf(w)
        assertEquals("reference", 1f / 3f, spec.reference, 0.0001f)
        assertTrue("a 45% leader is above the reference line", WinChanceSeries.plot(w.history, spec).last() > spec.reference)
    }

    @Test fun `three-way - a point without a draw uses the last known draw then the first known then the headline`() {
        val w = three(40, 20, 40, listOf(WinPoint(1, 50), WinPoint(2, 50, draw = 30), WinPoint(3, 50), WinPoint(4, 50, draw = 10), WinPoint(5, 50)))
        assertEquals("leading gap takes the first known draw; gaps carry the previous one", listOf<Int?>(30, 30, 30, 10, 10),
            WinChanceSeries.withDrawFilled(w.history, WinChanceSeries.specOf(w)).map { it.draw })
        val none = three(40, 20, 40, listOf(WinPoint(1, 50), WinPoint(2, 50)))
        assertEquals("no point has a draw: the headline's", listOf<Int?>(20, 20), WinChanceSeries.withDrawFilled(none.history, WinChanceSeries.specOf(none)).map { it.draw })
    }

    @Test fun `a draw that appears only in the history makes the chart three-way`() {
        assertTrue("three-way", WinChanceSeries.specOf(two(50, listOf(WinPoint(1, 50, draw = 25), WinPoint(2, 50, draw = 25)))).threeWay)
    }

    @Test fun `two-way ignores a stray draw of zero and never divides by it`() {
        val w = GameBlock.WinProbability(SourceId.ESPN, 60.0, drawPercent = 0.0, history = listOf(WinPoint(1, 60, draw = 0), WinPoint(2, 62, draw = 0)))
        val spec = WinChanceSeries.specOf(w)
        assertFalse("two-way", spec.threeWay)
        assertEquals("plot", listOf(0.6f, 0.62f), WinChanceSeries.plot(w.history, spec))
    }

    @Test fun `a tie leads home like the headline model`() = assertEquals("leader", "home", WinChanceSeries.specOf(two(50)).leaderSide)

    @Test fun `markers sit on the plotted line and flip with the leader`() {
        val t0 = 1_000_000L
        val h = (0 until 20).map { WinPoint(t0 + it * 30_000L, if (it == 0) 31 else 30) } // movement keeps the series visible (G3)
        val play = TimelineItem(time = "", period = null, side = SideKey.HOME, text = "Goal", kind = TimelineKind.GOAL, id = "g", wallclockMs = t0 + 5 * 30_000L)
        val m = ChartMarkers.markers(h, listOf(play), "Ice Hockey", spec = WinChanceSeries.specOf(two(30, h))).single()
        assertEquals("on the leader's line", 0.7f, m.y, 0.0001f)
        assertEquals("the default spec is the two-way home line", 0.3f, ChartMarkers.markers(h, listOf(play), "Ice Hockey").single().y, 0.0001f)
    }

    @Test fun `marker label side follows the reference of its own chart`() {
        assertTrue(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.40f, "G", SideKey.HOME, reference = 1f / 3f)))
        assertFalse(ChartMarkers.labelAbove(ChartMarker(0.2f, 0.30f, "G", SideKey.HOME, reference = 1f / 3f)))
    }

    @Test fun `short series and empty history give no plot`() {
        val spec = WinChanceSeries.specOf(two(50))
        assertNull(WinChanceSeries.chart(listOf(WinPoint(1, 50)), spec)); assertNull(WinChanceSeries.chart(emptyList(), spec))
    }

    @Test fun `chart returns the drawn series values capped at 120`() {
        val h = (1L..400L).map { WinPoint(it, if (it == 1L) 41 else 40) } // one point of movement: a flat series is hidden (G3)
        val c = WinChanceSeries.chart(h, WinChanceSeries.specOf(two(40, h)))!!
        assertEquals("cap", 120, c.size); assertEquals("leader's share", 0.6f, c.last(), 0.0001f)
    }

    @Test fun `the TV model plots the leaders full three-way chance with the third reference`() {
        val w = three(45, 30, 25, (0 until 5).map { WinPoint(1_800_000_000_000L + it * 30_000L, if (it == 0) 60 else 64, draw = 30) })
        val chart = GameDetailTvModel.from(detailOf(w), 0L).chart!!
        assertEquals("ends at the 45% headline", 0.448f, chart.values.last(), 0.0001f)
        assertEquals("reference", 1f / 3f, chart.reference, 0.0001f)
        assertFalse("home leads", chart.leaderIsAway)
    }
    @Test fun `the TV model colours an away leader and plots its share`() {
        val w = two(30, (0 until 5).map { WinPoint(1_800_000_000_000L + it * 30_000L, if (it == 0) 31 else 30) })
        val chart = GameDetailTvModel.from(detailOf(w), 0L).chart!!
        assertEquals("away share", 0.7f, chart.values.last(), 0.0001f)
        assertTrue("away leads", chart.leaderIsAway)
    }
}
