package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.SideKey
import com.nuvio.tv.core.sports.detail.TimelineItem
import com.nuvio.tv.core.sports.detail.TimelineKind
import com.nuvio.tv.core.sports.detail.WinPoint
import kotlin.math.abs

/** [x], [y] in 0..1 chart space; [playId] ties a marker to its play so a marker is recognised across snapshots. */
/** [y] is in the PLOTTED space of [WinChanceSeries]; [reference] is that chart's reference line, which decides the label side. */
data class ChartMarker(val x: Float, val y: Float, val label: String, val side: SideKey?, val playId: String? = null, val reference: Float = 0.5f)

/**
 * Scoring moments and period boundaries on the win-chance chart (Phase D; a TV twin of the Mobile `ChartMarkers`). Rule: a play with no
 * wall clock, or with no series point within the tolerance, gets NO marker - a position is never estimated from the game clock. Markers are
 * matched on the FULL series, then placed on the nearest point of the DRAWN (down-sampled) series, so a marker always sits on the drawn line.
 */
object ChartMarkers {
    const val MAX_MARKERS = 8
    /** TV draws fewer markers (10-foot readability). */
    const val TV_MAX_MARKERS = 5
    const val TOLERANCE_MS = 90_000L
    const val SWITCH = "chartMarkers"
    /** Labels closer than this share of the chart width would overlap: the newer one is kept. */
    private const val MIN_GAP_X = 0.05f

    /** The label sits above the line from the chart's reference (50 % two-way, an even third three-way) and below it under that. */
    fun labelAbove(m: ChartMarker): Boolean = m.y >= m.reference

    /** football TD / FG, hockey G / PPG, soccer G / RC (red card); basketball none. At most [max] (the newest), returned left to right. */
    fun markers(history: List<WinPoint>, plays: List<TimelineItem>, sport: String, max: Int = MAX_MARKERS, toleranceMs: Long = TOLERANCE_MS, spec: WinChanceSpec = WinChanceSpec.HOME_TWO_WAY): List<ChartMarker> {
        val filled = WinChanceSeries.withDrawFilled(history, spec)
        val full = filled.sortedBy { it.t }
        val drawn = GameDetailUiPolicy.chartSeries(filled) ?: return emptyList()
        val kept = ArrayList<ChartMarker>()
        for (p in plays.sortedByDescending { it.wallclockMs ?: Long.MIN_VALUE }) {
            if (kept.size >= max) break
            val wall = p.wallclockMs ?: continue
            val label = label(sport, p) ?: continue
            val nearest = full.minBy { abs(it.t - wall) }
            if (abs(nearest.t - wall) > toleranceMs) continue
            val idx = drawn.indices.minBy { abs(drawn[it].t - nearest.t) }
            val x = idx / (drawn.size - 1).toFloat()
            if (kept.any { abs(it.x - x) < MIN_GAP_X }) continue // overlap: the kept one is newer
            kept += ChartMarker(x, WinChanceSeries.valueOf(drawn[idx], spec), label, p.side, p.id, spec.reference)
        }
        return kept.sortedBy { it.x }
    }

    /**
     * Period starts as (x, label), left to right. Only a real PERIOD_START play draws a divider (an end of period n-1 is the start of a break
     * for halftime and quarter breaks, so it is never used as a guess): sports whose feed has no period-start plays draw none. A start with no
     * wall clock, outside the series, or the start of the game is skipped.
     */
    fun dividers(history: List<WinPoint>, plays: List<TimelineItem>, sport: String = ""): List<Pair<Float, String>> {
        val drawn = GameDetailUiPolicy.chartSeries(history) ?: return emptyList()
        val tMin = drawn.first().t
        val tMax = drawn.last().t
        val starts = HashMap<Int, Long>()
        for (p in plays) {
            val w = p.wallclockMs ?: continue
            val n = periodNumber(p.period) ?: continue
            when (p.kind) {
                TimelineKind.PERIOD_START -> starts.getOrPut(n) { w }
                else -> Unit
            }
        }
        return starts.keys.filter { it >= 2 }.sorted().mapNotNull { n ->
            val w = starts[n] ?: return@mapNotNull null
            if (w !in tMin..tMax) return@mapNotNull null
            val idx = drawn.indices.minBy { abs(drawn[it].t - w) }
            idx / (drawn.size - 1).toFloat() to boundaryLabel(sport, n)
        }.sortedBy { it.first }
    }

    /** Markers for a screen: none when the `chartMarkers` switch is off, there is no series (ESPN-only win chance) or no key events. */
    fun markersFor(d: GameDetail, max: Int = MAX_MARKERS): List<ChartMarker> {
        if (SWITCH !in d.uiSwitches) return emptyList()
        val w = d.blocks.filterIsInstance<GameBlock.WinProbability>().firstOrNull() ?: return emptyList()
        val items = d.blocks.filterIsInstance<GameBlock.Plays>().firstOrNull()?.chartItems ?: return emptyList()
        return markers(w.history, items, d.sport, max, spec = WinChanceSeries.specOf(w))
    }

    fun dividersFor(d: GameDetail): List<Pair<Float, String>> {
        if (SWITCH !in d.uiSwitches) return emptyList()
        val series = (d.blocks.filterIsInstance<GameBlock.WinProbability>().firstOrNull())?.history ?: return emptyList()
        val items = d.blocks.filterIsInstance<GameBlock.Plays>().firstOrNull()?.chartItems ?: return emptyList()
        return dividers(series, items, d.sport)
    }

    private fun label(sport: String, p: TimelineItem): String? {
        val s = sport.lowercase()
        val text = p.text.lowercase()
        return when {
            "football" in s -> if (p.kind == TimelineKind.SCORE) when { "touchdown" in text -> "TD"; "field goal" in text -> "FG"; else -> null } else null
            "hockey" in s -> when (p.kind) { TimelineKind.GOAL -> "G"; TimelineKind.PPG -> "PPG"; else -> null }
            "soccer" in s -> when { p.kind == TimelineKind.GOAL -> "G"; p.kind == TimelineKind.CARD && "red card" in text -> "RC"; else -> null }
            else -> null
        }
    }

    private fun periodNumber(period: String?): Int? = period?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    private fun boundaryLabel(sport: String, n: Int): String {
        val s = sport.lowercase()
        return when {
            "soccer" in s -> when (n) { 2 -> "45'"; 3 -> "90'"; else -> "ET" }
            "hockey" in s -> if (n <= 3) "P$n" else "OT"
            "basketball" in s || "football" in s -> if (n <= 4) "Q$n" else "OT"
            else -> "P$n"
        }
    }
}
