package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.GameBlock

/** Small pure decisions the game screen shares with the Mobile `GameDetailUiPolicy` (same names, same results). */
object GameDetailUiPolicy {
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
        is GameBlock.WinProbability -> false
    }
}
