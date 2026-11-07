package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.BlockType
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameHeader
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.LiveInfo
import com.nuvio.tv.core.sports.detail.SideKey

data class TvLinescore(val columns: List<String>, val rows: List<List<String>>)

/** One play line; [tag] is the short name of the side it belongs to (so "who scored" is never ambiguous). */
data class TvPlay(val time: String, val text: String, val tag: String?)

/** Short name (abbreviation) when the source has one, else the full name: never a truncated team name on TV. */
val Side.tvName: String get() = short?.takeIf { it.isNotBlank() } ?: name

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
    val latestPlays: List<TvPlay>,
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
            val sidesList = when (val h = d.header) {
                is GameHeader.Teams -> listOf(h.home, h.away)
                is GameHeader.Versus -> listOf(h.a, h.b)
                is GameHeader.Event -> emptyList()
            }
            val plays = (d.block(BlockType.TIMELINE) as? GameBlock.Timeline)?.items?.takeLast(PLAYS)?.reversed().orEmpty().map {
                TvPlay(it.time, it.text, when (it.side) { SideKey.HOME -> sidesList.getOrNull(0)?.tvName; SideKey.AWAY -> sidesList.getOrNull(1)?.tvName; null -> null })
            }
            val (title, sides) = when (val h = d.header) {
                is GameHeader.Teams -> "${h.home.name} v ${h.away.name}" to listOf(h.home, h.away)
                is GameHeader.Versus -> "${h.a.name} v ${h.b.name}" to listOf(h.a, h.b)
                is GameHeader.Event -> h.name to emptyList()
            }
            val subtitle = listOfNotNull(d.leagueName.takeIf { it.isNotBlank() }, d.round?.let { "Round $it" }, d.venue).joinToString(" · ").ifBlank { null }
            val live = liveLine(d.live)
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

        private val pauseWords = Regex("halftime|half time|intermission|break|end of", RegexOption.IGNORE_CASE)

        /** "2nd · 0:00" while play runs, the pause name ("Intermission", "Halftime") while it doesn't, else the bare clock/detail. */
        fun liveLine(l: LiveInfo?): String? {
            if (l == null) return null
            val detail = l.detail?.takeIf { it.isNotBlank() }
            if (detail != null && pauseWords.containsMatchIn(detail)) return detail
            val clock = l.clock?.takeIf { it.isNotBlank() }
            val period = l.periodLabel?.takeIf { it.isNotBlank() }
            return when {
                period != null && clock != null -> "$period · $clock"
                clock != null -> clock
                period != null -> period
                else -> detail
            }
        }

        private fun updatedLabel(d: GameDetail, nowMs: Long): String? {
            val at = d.asOf.values.maxOrNull() ?: return null
            val age = nowMs - at
            if (age <= STALE_AFTER_MS) return null
            return if (age < 60_000) "Updated ${age / 1000} s ago" else "Updated ${age / 60_000} min ago"
        }
    }
}
