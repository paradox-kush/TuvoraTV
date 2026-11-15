package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.WinPoint
import kotlin.math.roundToInt

/**
 * How the win-chance chart is drawn: ONE rule for every platform (a TV twin of core's `WinChanceSeries`); a screen only paints what this says.
 * [leaderSide] is the headline leader ("home" on a tie), so the line ends at the big number. [threeWay] is true for a sport with a draw.
 * [reference] is the dashed reference line in 0..1 plot space: 50 % two-way, an even third three-way (a 45 % leader is then above it).
 */
data class WinChanceSpec(val leaderSide: String, val threeWay: Boolean, val headlineDraw: Int?) {
    val reference: Float get() = if (threeWay) 1f / 3f else 0.5f

    companion object {
        /** The legacy home-oriented two-way line; what [ChartMarkers.markers] uses when no spec is given. */
        val HOME_TWO_WAY = WinChanceSpec("home", false, null)
    }
}

/**
 *  - two-way: plot the LEADER's share (home or 100 - home); reference 50 %.
 *  - three-way: plot the LEADER's full chance = share x (100 - draw)/100, where a point's `home` is home's share of the non-draw outcomes; reference 1/3.
 *  - a point without a draw takes the previous point's draw, a leading gap the first known draw, and none at all the headline's: a three-way
 *    line never falls back to the non-draw share.
 *  - markers use the same plotted value ([valueOf]) and the same reference as the line.
 */
object WinChanceSeries {
    fun specOf(w: GameBlock.WinProbability): WinChanceSpec {
        val home = w.homePercent.roundToInt().coerceIn(0, 100)
        val draw = w.drawPercent?.roundToInt()?.coerceIn(0, 100)?.takeIf { it > 0 }
        val away = (w.awayPercent?.roundToInt() ?: (100 - home - (draw ?: 0))).coerceIn(0, 100)
        val threeWay = draw != null || w.history.any { (it.draw ?: 0) > 0 }
        return WinChanceSpec(if (home >= away) "home" else "away", threeWay, draw)
    }

    /** Time-ordered copy where every point has a draw when the chart is three-way. Two-way: unchanged but sorted. */
    fun withDrawFilled(history: List<WinPoint>, spec: WinChanceSpec): List<WinPoint> {
        val sorted = history.sortedBy { it.t }
        if (!spec.threeWay) return sorted
        val first = sorted.firstOrNull { it.draw != null }?.draw ?: spec.headlineDraw ?: 0
        var last = first
        return sorted.map { p ->
            val d = p.draw ?: last
            last = d
            if (p.draw == d) p else p.copy(draw = d)
        }
    }

    /** The plotted 0..1 value of one point whose draw is already filled. */
    fun valueOf(p: WinPoint, spec: WinChanceSpec): Float {
        val home = p.home.coerceIn(0, 100)
        val share = if (spec.leaderSide == "home") home / 100f else (100 - home) / 100f
        if (!spec.threeWay) return share
        val rest = (100 - (p.draw ?: spec.headlineDraw ?: 0).coerceIn(0, 100)) / 100f
        return share * rest
    }

    fun plot(points: List<WinPoint>, spec: WinChanceSpec): List<Float> = withDrawFilled(points, spec).map { valueOf(it, spec) }

    /** The DRAWN line (at most 120 points, time-ordered): null with fewer than 2 points. Markers sit on exactly these x positions. */
    fun chart(history: List<WinPoint>, spec: WinChanceSpec): List<Float>? =
        GameDetailUiPolicy.chartSeries(withDrawFilled(history, spec))?.map { valueOf(it, spec) }
}
