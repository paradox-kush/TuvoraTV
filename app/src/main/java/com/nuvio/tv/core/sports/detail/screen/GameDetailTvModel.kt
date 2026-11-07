package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.BlockType
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameHeader
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.TimelineItem

data class TvLinescore(val columns: List<String>, val rows: List<List<String>>)

/**
 * The light set the TV screen shows: header, live line, situation, win probability, by-period linescore
 * and the latest plays. Stats, lineups, standings and results tables are deliberately never included.
 */
data class GameDetailTvModel(
    val title: String,
    val subtitle: String?,
    val sides: List<Side>,
    val liveLine: String?,
    val isLive: Boolean,
    val situation: String?,
    val homeWinPercent: Int?,
    val linescore: TvLinescore?,
    val latestPlays: List<TimelineItem>,
    val updatedLabel: String?,
    val shownBlocks: Set<BlockType>,
) {
    companion object {
        const val PLAYS = 5
        const val STALE_AFTER_MS = 20_000L

        fun from(d: GameDetail, nowMs: Long): GameDetailTvModel {
            val situation = (d.block(BlockType.SITUATION) as? GameBlock.Situation)?.text
            val win = (d.block(BlockType.WIN_PROBABILITY) as? GameBlock.WinProbability)?.homePercent?.toInt()?.coerceIn(0, 100)
            val line = (d.block(BlockType.LINESCORE) as? GameBlock.Linescore)?.let { TvLinescore(it.columns, it.rows) }
            val plays = (d.block(BlockType.TIMELINE) as? GameBlock.Timeline)?.items?.takeLast(PLAYS)?.reversed().orEmpty()
            val (title, sides) = when (val h = d.header) {
                is GameHeader.Teams -> "${h.home.name} v ${h.away.name}" to listOf(h.home, h.away)
                is GameHeader.Versus -> "${h.a.name} v ${h.b.name}" to listOf(h.a, h.b)
                is GameHeader.Event -> h.name to emptyList()
            }
            val subtitle = listOfNotNull(d.leagueName.takeIf { it.isNotBlank() }, d.round?.let { "Round $it" }, d.venue).joinToString(" · ").ifBlank { null }
            val live = d.live?.let { it.clock ?: it.detail }
            return GameDetailTvModel(
                title = title, subtitle = subtitle, sides = sides,
                liveLine = live ?: (d.header as? GameHeader.Event)?.session,
                isLive = d.state == GameState.LIVE,
                situation = situation, homeWinPercent = win, linescore = line, latestPlays = plays,
                updatedLabel = updatedLabel(d, nowMs),
                shownBlocks = buildSet {
                    if (situation != null) add(BlockType.SITUATION)
                    if (win != null) add(BlockType.WIN_PROBABILITY)
                    if (line != null) add(BlockType.LINESCORE)
                    if (plays.isNotEmpty()) add(BlockType.TIMELINE)
                },
            )
        }

        private fun updatedLabel(d: GameDetail, nowMs: Long): String? {
            val at = d.asOf.values.maxOrNull() ?: return null
            val age = nowMs - at
            if (age <= STALE_AFTER_MS) return null
            return if (age < 60_000) "Updated ${age / 1000} s ago" else "Updated ${age / 60_000} min ago"
        }
    }
}
