package com.nuvio.tv.core.sports.detail

import java.time.Instant

/**
 * Game-detail block contract v1 (research/sports-mapping/BLOCK_CONTRACT.md). Providers and mappings
 * produce these; the UI never sees a provider shape. Display values are Strings (providers disagree
 * on number types); the sport profile owns labels and units.
 */
enum class GameState { UPCOMING, LIVE, FINAL, POSTPONED }

enum class SourceId(val wire: String) {
    TSDB("tsdb"), ESPN("espn"), DERIVED("derived"), LOCAL("local");

    companion object {
        fun fromWire(s: String?): SourceId? = entries.firstOrNull { it.wire.equals(s, ignoreCase = true) }
    }
}

enum class BlockType(val wire: String) {
    SITUATION("situation"), WATCH("watch"), BROADCAST("broadcast"), LINESCORE("linescore"), STATS("stats"),
    LEADERS("leaders"), TIMELINE("timeline"), LINEUP("lineup"), RESULTS("results"), STANDINGS("standings"),
    FORM("form"), HIGHLIGHTS("highlights"), NEXT("next"), WIN_PROBABILITY("winProbability");

    companion object {
        /** Block types the shared core can assemble from a provider (watch/next/standings are not yet). */
        val assemblable: Set<BlockType> = setOf(
            SITUATION, BROADCAST, LINESCORE, STATS, LEADERS, TIMELINE, LINEUP, RESULTS, STANDINGS, FORM, HIGHLIGHTS, WIN_PROBABILITY,
        )

        fun fromWire(s: String?): BlockType? = entries.firstOrNull { it.wire == s }
    }
}

enum class SideKey { HOME, AWAY }
enum class TimelineKind { GOAL, CARD, SUB, SCORE, PENALTY }
enum class RefreshPart { SCOREBOARD, SUMMARY; companion object { val ALL: Set<RefreshPart> = entries.toSet() } }

/** Per-source result of one load, and the closed vocabulary of the `sports_detail` telemetry outcome. */
enum class FetchOutcome(val wire: String) {
    HIT("hit"), EMPTY("empty"), PARSE_FAIL("parse_fail"), HTTP_FAIL("http_fail");

    val isFailure: Boolean get() = this == PARSE_FAIL || this == HTTP_FAIL
}

enum class SourceStatus { OK, MISS, FAILED, DISABLED }

/** FULL: at least one block. MATCH_SHEET: sources answered but had nothing. FAILED: nothing, and a source failed (offer Retry). */
enum class Availability { FULL, MATCH_SHEET, FAILED }

data class Side(
    val name: String,
    val short: String? = null,
    val badgeUrl: String? = null,
    val score: String? = null,
    val record: String? = null,
    val form: List<String>? = null,
)

sealed interface GameHeader {
    data class Teams(val home: Side, val away: Side) : GameHeader
    data class Versus(val a: Side, val b: Side) : GameHeader
    data class Event(val name: String, val session: String? = null, val venue: String? = null) : GameHeader
}

/** clock: "2H 49'", "Q2 5:12"; periodLabel: "Q2"; detail: "Halftime". */
data class LiveInfo(val clock: String? = null, val periodLabel: String? = null, val detail: String? = null)

data class StatRow(val label: String, val home: String, val away: String, val unit: String? = null)
data class LeaderItem(val category: String, val athlete: String, val value: String, val side: SideKey?)
data class TimelineItem(
    val time: String, val period: String?, val side: SideKey?, val text: String, val kind: TimelineKind?,
)
data class LineupPlayer(val number: String?, val name: String, val pos: String?, val starter: Boolean)
data class LineupSide(val formation: String?, val players: List<LineupPlayer>)
data class BroadcastEntry(val region: String?, val name: String)
data class StandingRow(val pos: String, val name: String, val played: String, val points: String, val gd: String? = null, val form: String? = null)

sealed interface GameBlock {
    val type: BlockType
    val source: SourceId

    data class Situation(override val source: SourceId, val text: String, val parts: Map<String, String> = emptyMap()) : GameBlock {
        override val type get() = BlockType.SITUATION
    }
    data class Broadcast(override val source: SourceId, val channels: List<BroadcastEntry>) : GameBlock {
        override val type get() = BlockType.BROADCAST
    }
    data class Linescore(override val source: SourceId, val columns: List<String>, val rows: List<List<String>>) : GameBlock {
        override val type get() = BlockType.LINESCORE
    }
    data class Stats(override val source: SourceId, val rows: List<StatRow>) : GameBlock {
        override val type get() = BlockType.STATS
    }
    data class Leaders(override val source: SourceId, val items: List<LeaderItem>) : GameBlock {
        override val type get() = BlockType.LEADERS
    }
    data class Timeline(override val source: SourceId, val items: List<TimelineItem>) : GameBlock {
        override val type get() = BlockType.TIMELINE
    }
    data class Lineup(override val source: SourceId, val sides: List<LineupSide>) : GameBlock {
        override val type get() = BlockType.LINEUP
    }
    data class Results(override val source: SourceId, val columns: List<String>, val rows: List<List<String>>) : GameBlock {
        override val type get() = BlockType.RESULTS
    }
    data class Standings(override val source: SourceId, val rows: List<StandingRow>) : GameBlock {
        override val type get() = BlockType.STANDINGS
    }
    data class Form(override val source: SourceId, val home: List<String>, val away: List<String>) : GameBlock {
        override val type get() = BlockType.FORM
    }
    data class Highlights(override val source: SourceId, val url: String, val thumbnail: String? = null) : GameBlock {
        override val type get() = BlockType.HIGHLIGHTS
    }
    /** 0..100, home side. */
    data class WinProbability(override val source: SourceId, val homePercent: Double) : GameBlock {
        override val type get() = BlockType.WIN_PROBABILITY
    }
}

/** What the screen was opened with: enough to show a match sheet when no source answers. */
data class GameRef(
    val gameId: String,
    val leagueId: String,
    val homeName: String,
    /** Empty for single-entity events (race weekend, fight card): then [eventName] holds the name. */
    val awayName: String,
    val startsAtMs: Long,
    val sport: String = "",
    val leagueName: String = "",
    val eventName: String? = null,
    val round: String? = null,
    val venue: String? = null,
    val seedHome: Side? = null,
    val seedAway: Side? = null,
    val seedState: GameState = GameState.UPCOMING,
) {
    /** The id in the primary source's own namespace ("tsdb:123" -> "123"). */
    val rawId: String get() = gameId.substringAfter(':')
    val tsdbEventId: String? get() = if (gameId.startsWith("tsdb:")) rawId else null
    val espnEventId: String? get() = if (gameId.startsWith("espn:")) rawId else null

    /** Header and state are only ever taken from this source, so a cross-provider mislink can't change the score. */
    val primary: SourceId get() = if (gameId.startsWith("espn:")) SourceId.ESPN else SourceId.TSDB

    fun seedHeader(): GameHeader =
        if (homeName.isBlank() && awayName.isBlank()) GameHeader.Event(eventName ?: leagueName, venue = venue)
        else GameHeader.Teams(seedHome ?: Side(homeName), seedAway ?: Side(awayName))
}

data class GameDetail(
    val gameId: String,
    val sport: String,
    val leagueId: String,
    val leagueName: String,
    val profile: String?,
    val round: String?,
    val venue: String?,
    val startsAtMs: Long,
    val state: GameState,
    val header: GameHeader,
    val live: LiveInfo?,
    val blocks: List<GameBlock>,
    val sources: Map<BlockType, SourceId>,
    val sourceStatus: Map<SourceId, SourceStatus>,
    val availability: Availability,
    /** Local fetch time per source (never a provider timestamp). UI shows "Updated 2 min ago" when stale > 20 s. */
    val asOf: Map<SourceId, Long>,
) {
    val startsAtIso: String get() = Instant.ofEpochMilli(startsAtMs).toString()

    fun block(type: BlockType): GameBlock? = blocks.firstOrNull { it.type == type }

    /**
     * Folds a (possibly partial) live tick over the previous detail: blocks the tick carries replace
     * older ones, the rest are kept. A failed tick never erases what was already on screen.
     */
    fun mergeOver(older: GameDetail): GameDetail {
        if (availability == Availability.FAILED) {
            return older.copy(
                sourceStatus = sourceStatus,
                availability = if (older.blocks.isEmpty()) Availability.FAILED else older.availability,
            )
        }
        val fresh = blocks.associateBy { it.type }
        val merged = older.blocks.map { fresh[it.type] ?: it } + blocks.filter { b -> older.blocks.none { it.type == b.type } }
        return copy(
            blocks = merged,
            sources = older.sources + sources,
            asOf = older.asOf + asOf,
            live = live ?: older.live,
            availability = if (merged.isNotEmpty()) Availability.FULL else availability,
        )
    }

    companion object {
        fun skeleton(ref: GameRef, profile: String? = null) = GameDetail(
            gameId = ref.gameId, sport = ref.sport, leagueId = ref.leagueId, leagueName = ref.leagueName,
            profile = profile, round = ref.round, venue = ref.venue, startsAtMs = ref.startsAtMs,
            state = ref.seedState, header = ref.seedHeader(), live = null, blocks = emptyList(),
            sources = emptyMap(), sourceStatus = emptyMap(), availability = Availability.MATCH_SHEET, asOf = emptyMap(),
        )
    }
}
