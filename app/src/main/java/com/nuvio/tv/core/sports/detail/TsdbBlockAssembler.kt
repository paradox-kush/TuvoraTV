package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * TheSportsDB documents for one event, in TheSportsDB's own shapes (`lookup[]` / `events[]` wrappers).
 * Served by our backend `game-detail` endpoint (per-game cached).
 */
data class TsdbDocs(
    val event: JsonObject? = null,
    val stats: JsonElement? = null,
    val timeline: JsonElement? = null,
    val tv: JsonElement? = null,
    val highlights: JsonElement? = null,
    val lineup: JsonElement? = null,
    val results: JsonElement? = null,
)

/** Hand-written assembler for TheSportsDB's fixed, documented shapes (the mapping file covers ESPN). */
class TsdbBlockAssembler {
    private val r = JsonPathReader

    private fun s(n: JsonElement?, key: String) = r.firstText(n, key)?.takeIf { it.isNotBlank() }

    fun header(docs: TsdbDocs): GameHeader? {
        val e = docs.event ?: return null
        val home = s(e, "strHomeTeam"); val away = s(e, "strAwayTeam")
        return if (home != null && away != null) {
            GameHeader.Teams(
                Side(home, badgeUrl = s(e, "strHomeTeamBadge"), score = s(e, "intHomeScore")),
                Side(away, badgeUrl = s(e, "strAwayTeamBadge"), score = s(e, "intAwayScore")),
            )
        } else {
            s(e, "strEvent")?.let { GameHeader.Event(it, venue = s(e, "strVenue")) }
        }
    }

    fun state(docs: TsdbDocs): GameState? {
        val e = docs.event ?: return null
        if (s(e, "strPostponed").equals("yes", true)) return GameState.POSTPONED
        val st = s(e, "strStatus")?.trim()?.lowercase() ?: return GameState.UPCOMING
        return when (st) {
            "ns", "not started", "tbd", "time to be defined", "scheduled" -> GameState.UPCOMING
            "ft", "aet", "pen", "match finished", "final", "finished", "after over time", "ft_pen", "aot" -> GameState.FINAL
            "pst", "postponed", "canc", "cancelled", "abd", "abandoned", "susp", "suspended" -> GameState.POSTPONED
            "1h", "2h", "ht", "et", "bt", "p", "live", "in progress", "ot", "q1", "q2", "q3", "q4", "p1", "p2", "p3" -> GameState.LIVE
            else -> null
        }
    }

    fun live(docs: TsdbDocs): LiveInfo? {
        if (state(docs) != GameState.LIVE) return null
        val e = docs.event ?: return null
        val code = s(e, "strProgress") ?: s(e, "strStatus")
        return LiveInfo(clock = code, detail = if (code.equals("HT", true)) "Halftime" else null)
    }

    fun all(docs: TsdbDocs): Map<BlockType, GameBlock> =
        BlockType.assemblable.mapNotNull { t -> block(t, docs)?.let { t to it } }.toMap()

    fun block(type: BlockType, docs: TsdbDocs): GameBlock? = try {
        when (type) {
            BlockType.STATS -> stats(docs)
            BlockType.TIMELINE -> timeline(docs)
            BlockType.BROADCAST -> broadcast(docs)
            BlockType.HIGHLIGHTS -> highlights(docs)
            BlockType.LINEUP -> lineup(docs)
            BlockType.RESULTS -> results(docs)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun pretty(k: String) = k.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    private fun stats(d: TsdbDocs): GameBlock.Stats? {
        val rows = r.read(d.stats, "lookup[*]").mapNotNull { n ->
            val label = s(n, "strStat") ?: return@mapNotNull null
            val h = s(n, "intHome"); val a = s(n, "intAway")
            if (h == null && a == null) null else StatRow(pretty(label), h ?: "-", a ?: "-")
        }
        return rows.takeIf { it.isNotEmpty() }?.let { GameBlock.Stats(SourceId.TSDB, it) }
    }

    private fun timeline(d: TsdbDocs): GameBlock.Timeline? {
        val items = r.read(d.timeline, "lookup[*]").mapNotNull { n ->
            val type = s(n, "strTimeline") ?: return@mapNotNull null
            val detail = s(n, "strTimelineDetail") ?: type
            val player = s(n, "strPlayer")
            val assist = s(n, "strAssist")
            val kind = when {
                type.equals("goal", true) -> TimelineKind.GOAL
                type.equals("card", true) -> TimelineKind.CARD
                type.startsWith("subst", true) -> TimelineKind.SUB
                else -> null
            }
            TimelineItem(
                time = s(n, "intTime")?.let { "$it'" }.orEmpty(),
                period = s(n, "strPeriod"),
                side = when (s(n, "strHome")?.lowercase()) { "yes" -> SideKey.HOME; "no" -> SideKey.AWAY; else -> null },
                text = buildString {
                    append(detail)
                    if (player != null) append(" — ").append(player)
                    if (assist != null) append(" (assist ").append(assist).append(')')
                },
                kind = kind,
            )
        }
        return items.takeIf { it.isNotEmpty() }?.let { GameBlock.Timeline(SourceId.TSDB, it) }
    }

    private fun broadcast(d: TsdbDocs): GameBlock.Broadcast? {
        val ch = r.read(d.tv, "lookup[*]").mapNotNull { n ->
            s(n, "strChannel")?.let { BroadcastEntry(s(n, "strCountry"), it) }
        }.distinct()
        return ch.takeIf { it.isNotEmpty() }?.let { GameBlock.Broadcast(SourceId.TSDB, it) }
    }

    private fun highlights(d: TsdbDocs): GameBlock.Highlights? {
        val url = r.read(d.highlights, "lookup[*]").firstNotNullOfOrNull { s(it, "strVideo") } ?: s(d.event, "strVideo") ?: return null
        val thumb = s(d.event, "strThumb")
        return GameBlock.Highlights(SourceId.TSDB, url, thumb)
    }

    private fun lineup(d: TsdbDocs): GameBlock.Lineup? {
        val rows = r.read(d.lineup, "lineup[*]")
        if (rows.isEmpty()) return null
        fun side(home: Boolean): LineupSide? {
            val ps = rows.filter { (s(it, "strHome")?.equals("yes", true) ?: false) == home }
            if (ps.isEmpty()) return null
            return LineupSide(
                formation = ps.firstNotNullOfOrNull { s(it, "strFormation") },
                players = ps.mapNotNull { p ->
                    s(p, "strPlayer")?.let { LineupPlayer(s(p, "intSquadNumber"), it, s(p, "strPosition"), !(s(p, "strSubstitute")?.equals("yes", true) ?: false)) }
                },
            )
        }
        val sides = listOfNotNull(side(true), side(false))
        return sides.takeIf { it.isNotEmpty() }?.let { GameBlock.Lineup(SourceId.TSDB, it) }
    }

    private fun results(d: TsdbDocs): GameBlock.Results? {
        val rows = r.read(d.results, "results[*]").mapNotNull { n ->
            val who = s(n, "strPlayer") ?: s(n, "strTeam") ?: return@mapNotNull null
            listOf(s(n, "intPosition").orEmpty(), who, s(n, "strTeam").orEmpty(), s(n, "strTime") ?: s(n, "strResult").orEmpty())
        }
        return rows.takeIf { it.isNotEmpty() }?.let { GameBlock.Results(SourceId.TSDB, listOf("Pos", "Name", "Team", "Result"), it) }
    }
}
