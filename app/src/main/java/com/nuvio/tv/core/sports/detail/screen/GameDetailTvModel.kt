package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.BlockType
import com.nuvio.tv.core.sports.detail.GameBlock
import com.nuvio.tv.core.sports.detail.GameDetail
import com.nuvio.tv.core.sports.detail.GameHeader
import com.nuvio.tv.core.sports.detail.GameState
import com.nuvio.tv.core.sports.detail.LiveInfo
import com.nuvio.tv.core.sports.detail.MarketPolicy
import com.nuvio.tv.core.sports.detail.Side
import com.nuvio.tv.core.sports.detail.SideKey
import com.nuvio.tv.core.sports.detail.SourceId
import com.nuvio.tv.core.sports.detail.TimelineKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One line of the by-period table: a team, its period cells and its total. */
data class TvLineRow(val label: String, val badgeUrl: String?, val cells: List<String>, val total: String?, val leading: Boolean)

/**
 * [currentIndex] is the period being played (highlighted) while live; [totalLabel] is null when the sport's own
 * columns already end in totals (baseball R/H/E).
 */
data class TvLinescore(val columns: List<String>, val rows: List<TvLineRow>, val totalLabel: String?, val currentIndex: Int?)

/** One play line; [tag] is the short name of the side it belongs to, [scoring] marks goals/baskets. */
data class TvPlay(val time: String, val text: String, val tag: String?, val side: SideKey?, val scoring: Boolean)

data class TvLeader(val category: String, val athlete: String, val value: String, val tag: String?, val side: SideKey?)

/** A compact comparison row; [homeFraction] is the home share (0..1) the bar is drawn from. */
data class TvStat(val label: String, val home: String, val away: String, val homeFraction: Float)

data class TvForm(val home: List<String>, val away: List<String>)

/** Short name (abbreviation) when the source has one, else the full name. */
val Side.tvName: String get() = short?.takeIf { it.isNotBlank() } ?: name

/**
 * What the TV screen shows: a 10-foot, two-pane layout. Left: scoreboard header, situation + win probability,
 * by-period table, source row. Right: top performers, a compact stats comparison (at most [STATS] rows) and the
 * latest plays. Lineups, standings and results tables are never included. Upcoming games swap the live
 * blocks for kickoff, venue and form.
 */
data class TvWinChance(
    val homePercent: Int,
    val awayPercent: Int,
    val drawPercent: Int?,
    val caption: String?,
    val line: String?,
    val staleLabel: String?,
)

data class GameDetailTvModel(
    val title: String,
    val subtitle: String?,
    val sides: List<Side>,
    val state: GameState,
    /** "LIVE · 3rd 10:12", "FINAL", "POSTPONED"; null for an upcoming game (kickoff is shown instead). */
    val pill: String?,
    val liveLine: String?,
    val isLive: Boolean,
    val situation: String?,
    val homeWinPercent: Int?,
    /** The win-chance card: bar, caption ("via Polymarket") and at most ONE extra line (comeback watch, else a storyline). */
    val winChance: TvWinChance?,
    val linescore: TvLinescore?,
    val leaders: List<TvLeader>,
    val stats: List<TvStat>,
    val form: TvForm?,
    val latestPlays: List<TvPlay>,
    val kickoffLabel: String?,
    val venue: String?,
    val updatedLabel: String?,
    val shownBlocks: Set<BlockType>,
) {
    companion object {
        const val PLAYS = 12
        const val STATS = 3
        const val LEADERS = 3
        const val STALE_AFTER_MS = 20_000L

        /** Home/away/draw whole percents (the market gives all three, ESPN only home) and the card's single extra line. */
        private fun tvWinChance(b: GameBlock.WinProbability, d: GameDetail, sides: List<Side>, nowMs: Long): TvWinChance {
            val home = b.homePercent.roundToInt().coerceIn(0, 100)
            val draw = b.drawPercent?.roundToInt()?.coerceIn(0, 100)?.takeIf { it > 0 }
            val away = (b.awayPercent?.roundToInt() ?: (100 - home - (draw ?: 0))).coerceIn(0, 100)
            val homeTag = sides.getOrNull(0)?.tvName
            val awayTag = sides.getOrNull(1)?.tvName
            val comeback = MarketPolicy.comebackWatch(b.history, home, away, d.state, b.asOfMs)
                ?.let { MarketPolicy.comebackText(it, homeTag, awayTag) }
            val line = comeback ?: MarketPolicy.visibleStorylines(b.storylines, d.state, d.live, d.sport)
                .firstOrNull()?.let { MarketPolicy.storylineText(it, homeTag, awayTag) }
            val credit = b.extrasAttribution?.takeIf { line != null && b.source != SourceId.MARKET }
                ?.let { "storyline " + it.replaceFirstChar { c -> c.lowercase() } }
            return TvWinChance(
                homePercent = home, awayPercent = away, drawPercent = draw,
                caption = listOfNotNull(MarketPolicy.caption(b), credit).joinToString(" · ").ifBlank { null },
                line = line,
                staleLabel = MarketPolicy.staleLabel(b, d.state, nowMs),
            )
        }

        fun from(
            d: GameDetail,
            nowMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = Locale.getDefault(),
        ): GameDetailTvModel {
            val sides = when (val h = d.header) {
                is GameHeader.Teams -> listOf(h.home, h.away)
                is GameHeader.Versus -> listOf(h.a, h.b)
                is GameHeader.Event -> emptyList()
            }
            val title = when (val h = d.header) {
                is GameHeader.Teams -> "${h.home.name} v ${h.away.name}"
                is GameHeader.Versus -> "${h.a.name} v ${h.b.name}"
                is GameHeader.Event -> h.name
            }
            val live = d.state == GameState.LIVE
            val line = liveLine(d.live)
            val situation = (d.block(BlockType.SITUATION) as? GameBlock.Situation)?.text
            val winBlock = d.block(BlockType.WIN_PROBABILITY) as? GameBlock.WinProbability
            val win = winBlock?.homePercent?.toInt()?.coerceIn(0, 100)
            val winChance = winBlock?.let { tvWinChance(it, d, sides, nowMs) }
            val linescore = (d.block(BlockType.LINESCORE) as? GameBlock.Linescore)?.let { linescore(it, sides, live, d.state) }
            val leaders = leaders(d, sides)
            val stats = stats(d)
            val form = (d.block(BlockType.FORM) as? GameBlock.Form)?.let { TvForm(it.home.takeLast(5), it.away.takeLast(5)) }
            val plays = (d.block(BlockType.TIMELINE) as? GameBlock.Timeline)?.items?.takeLast(PLAYS)?.reversed().orEmpty().map {
                TvPlay(
                    time = it.time, text = condensePlay(it.text),
                    tag = when (it.side) { SideKey.HOME -> sides.getOrNull(0)?.tvName; SideKey.AWAY -> sides.getOrNull(1)?.tvName; null -> null },
                    side = it.side,
                    scoring = it.kind == TimelineKind.GOAL || it.kind == TimelineKind.SCORE,
                )
            }
            val pill = when (d.state) {
                GameState.LIVE -> if (line != null) "LIVE · ${line.replace(" · ", " ")}" else "LIVE"
                GameState.FINAL -> "FINAL"
                GameState.POSTPONED -> "POSTPONED"
                GameState.UPCOMING -> null
            }
            return GameDetailTvModel(
                title = title,
                subtitle = listOfNotNull(d.leagueName.takeIf { it.isNotBlank() }, d.round?.let { "Round $it" }).joinToString(" · ").ifBlank { null },
                sides = sides, state = d.state, pill = pill,
                liveLine = line ?: (d.header as? GameHeader.Event)?.session,
                isLive = live, situation = situation, homeWinPercent = win, winChance = winChance, linescore = linescore,
                leaders = leaders, stats = stats, form = form, latestPlays = plays,
                kickoffLabel = if (d.state == GameState.UPCOMING && d.startsAtMs > 0) kickoff(d.startsAtMs, zone, locale) else null,
                venue = d.venue ?: (d.header as? GameHeader.Event)?.venue,
                updatedLabel = updatedLabel(d, nowMs),
                shownBlocks = buildSet {
                    if (situation != null) add(BlockType.SITUATION)
                    if (win != null) add(BlockType.WIN_PROBABILITY)
                    if (linescore != null) add(BlockType.LINESCORE)
                    if (leaders.isNotEmpty()) add(BlockType.LEADERS)
                    if (stats.isNotEmpty()) add(BlockType.STATS)
                    if (form != null) add(BlockType.FORM)
                    if (plays.isNotEmpty()) add(BlockType.TIMELINE)
                },
            )
        }

        private fun kickoff(ms: Long, zone: ZoneId, locale: Locale): String =
            DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", locale).withZone(zone).format(Instant.ofEpochMilli(ms))

        private fun linescore(b: GameBlock.Linescore, sides: List<Side>, live: Boolean, state: GameState): TvLinescore? {
            if (b.rows.isEmpty() || b.columns.isEmpty()) return null
            val ownTotals = b.columns.takeLast(3) == listOf("R", "H", "E")
            val current = if (live) b.columns.indices.lastOrNull { i -> b.rows.any { r -> r.getOrNull(i)?.isNotBlank() == true } } else null
            val totals = b.rows.mapIndexed { i, r ->
                if (ownTotals) null
                else sides.getOrNull(i)?.score?.takeIf { it.isNotBlank() } ?: r.mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() }?.sum()?.toString()
            }
            val best = totals.mapNotNull { it?.toIntOrNull() }.maxOrNull()
            val rows = b.rows.mapIndexed { i, r ->
                val side = sides.getOrNull(i)
                TvLineRow(
                    label = side?.tvName.orEmpty(), badgeUrl = side?.badgeUrl, cells = r, total = totals[i],
                    leading = state == GameState.FINAL && best != null && totals[i]?.toIntOrNull() == best && totals.count { it?.toIntOrNull() == best } == 1,
                )
            }
            return TvLinescore(b.columns, rows, if (ownTotals) null else "T", current)
        }

        private val abbreviations = mapOf(
            "goals" to "G", "assists" to "A", "points" to "P", "rebounds" to "REB", "steals" to "STL", "blocks" to "BLK",
            "saves" to "SV", "shots" to "SOG",
        )

        private fun leaders(d: GameDetail, sides: List<Side>): List<TvLeader> {
            val items = (d.block(BlockType.LEADERS) as? GameBlock.Leaders)?.items.orEmpty().distinctBy { it.category }
            fun tag(s: SideKey?) = when (s) { SideKey.HOME -> sides.getOrNull(0)?.tvName; SideKey.AWAY -> sides.getOrNull(1)?.tvName; null -> null }
            // One player leading several categories is shown once ("Mika Zibanejad · 2 A, 2 P"), not once per category.
            val rows = ArrayList<TvLeader>()
            items.groupBy { it.athlete }.forEach { (athlete, own) ->
                val first = own.first()
                rows += if (own.size == 1) TvLeader(pretty(first.category), athlete, first.value, tag(first.side), first.side)
                else TvLeader(
                    category = "Leader", athlete = athlete,
                    value = own.joinToString(", ") { "${it.value} ${abbreviations[it.category.lowercase()] ?: pretty(it.category)}" },
                    tag = tag(first.side), side = first.side,
                )
            }
            return rows.take(LEADERS)
        }

        /**
         * Keeps a play line to two lines on TV: the scorer and the first assist, dropping the rest of the assist list
         * ("Pavel Dorofeyev Goal (6) Backhand, assists: Mika Zibanejad (12), Alexis Lafreniere (11)" ->
         * "Pavel Dorofeyev Goal (6) Backhand · assist Mika Zibanejad").
         */
        fun condensePlay(text: String): String {
            val i = text.indexOf("assists:", ignoreCase = true).takeIf { it >= 0 } ?: return text
            // Drop the shot type after "Goal (n)" ("Goal (6) Backhand" -> "Goal (6)"): the assist is worth more of the 2 lines.
            val head = text.substring(0, i).trim().trimEnd(',', ' ').replace(Regex("(Goal \\(\\d+\\)).*$"), "$1")
            val first = text.substring(i + "assists:".length).trim().split(Regex("\\s*\\(\\d+\\)\\s*,?|,")).firstOrNull { it.isNotBlank() }?.trim()
            return if (first == null) head else "$head · assist $first"
        }

        private fun pretty(k: String): String =
            k.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').trim().lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }

        private fun stats(d: GameDetail): List<TvStat> {
            val rows = (d.block(BlockType.STATS) as? GameBlock.Stats)?.rows.orEmpty()
            return rows.mapNotNull { r ->
                val h = number(r.home) ?: return@mapNotNull null
                val a = number(r.away) ?: return@mapNotNull null
                TvStat(r.label, r.home + (r.unit ?: ""), r.away + (r.unit ?: ""), if (h + a > 0) (h / (h + a)).toFloat() else 0.5f)
            }.take(STATS)
        }

        private fun number(s: String): Double? = s.trim().removeSuffix("%").toDoubleOrNull()?.takeIf { it >= 0 }

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
