package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameBlock

/** Small pure decisions the game screen shares with the Mobile `GameDetailUiPolicy` (same names, same results). */
object GameDetailUiPolicy {
    private const val CHART_MAX_POINTS = 120

    /** The DRAWN series: time-ordered and down-sampled to at most 120 points (spec 05) (null when < 2 points). Markers sit on exactly these points. */
    fun chartSeries(history: List<com.nuvio.tv.core.sports.detail.WinPoint>): List<com.nuvio.tv.core.sports.detail.WinPoint>? {
        if (history.size < 2) return null
        val sorted = history.sortedBy { it.t }
        return if (sorted.size <= CHART_MAX_POINTS) sorted else (0 until CHART_MAX_POINTS).map { sorted[(it.toLong() * (sorted.size - 1) / (CHART_MAX_POINTS - 1)).toInt()] }
    }

    /** Momentum line as given by the server (home's per-point non-draw share): 0..1, time-ordered, at most 120 points; null when < 2 points. */
    fun chartPoints(history: List<com.nuvio.tv.core.sports.detail.WinPoint>): List<Float>? = chartSeries(history)?.map { it.home.coerceIn(0, 100) / 100f }


    /** Stat rows where the LOWER value leads (turnovers, fouls...): the leading side's emphasis flips. */
    private val LOWER_IS_BETTER = setOf("turnovers", "fouls", "personal fouls", "penalty minutes", "giveaways", "penalties", "total penalties")

    fun lowerIsBetter(label: String): Boolean = label.trim().lowercase() in LOWER_IS_BETTER

    /** An empty block hides instead of rendering a heading over nothing. */
    fun isEmpty(b: GameBlock): Boolean = when (b) {
        is GameBlock.Stats -> b.rows.isEmpty()
        is GameBlock.Linescore -> b.rows.isEmpty() || b.columns.isEmpty()
        is GameBlock.Timeline -> b.items.isEmpty()
        is GameBlock.Plays -> b.items.isEmpty()
        is GameBlock.StandingsTable -> b.groups.all { it.rows.isEmpty() }
        is GameBlock.Leaders -> b.items.isEmpty()
        is GameBlock.Lineup -> b.sides.all { it.players.isEmpty() }
        is GameBlock.Standings -> b.rows.isEmpty()
        is GameBlock.Form -> b.home.isEmpty() && b.away.isEmpty()
        is GameBlock.Results -> b.rows.isEmpty()
        is GameBlock.Situation -> b.text.isBlank()
        is GameBlock.Highlights -> b.url.isBlank()
        is GameBlock.Field, is GameBlock.SoccerStrip, is GameBlock.BasketballStrip, is GameBlock.HockeyStrip -> false
        is GameBlock.WinProbability -> false
    }
}
