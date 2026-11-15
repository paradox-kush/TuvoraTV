package com.nuvio.tv.core.sports.detail

import java.time.Instant

/**
 * Game-detail block contract v1 (research/sports-mapping/BLOCK_CONTRACT.md). Providers and mappings
 * produce these; the UI never sees a provider shape. Display values are Strings (providers disagree
 * on number types); the sport profile owns labels and units.
 */
enum class GameState { UPCOMING, LIVE, FINAL, POSTPONED }

enum class SourceId(val wire: String) {
    TSDB("tsdb"), ESPN("espn"), DERIVED("derived"), LOCAL("local"),
    /** Polymarket win chance served by OUR backend (devices never call Polymarket). Never part of a league's chain. */
    MARKET("polymarket");

    companion object {
        /** "market" is the mapping's name for it (`killSwitch.market`, chains); "polymarket" is the telemetry/source name. */
        fun fromWire(s: String?): SourceId? =
            if (s.equals("market", ignoreCase = true)) MARKET else entries.firstOrNull { it.wire.equals(s, ignoreCase = true) }
    }
}

enum class BlockType(val wire: String) {
    SITUATION("situation"), WATCH("watch"), LINESCORE("linescore"), STATS("stats"),
    LEADERS("leaders"), TIMELINE("timeline"), LINEUP("lineup"), RESULTS("results"), STANDINGS("standings"),
    FORM("form"), HIGHLIGHTS("highlights"), NEXT("next"), WIN_PROBABILITY("winProbability"),
    /** Phase A: the full play list (timeline stays the scoring summary) and the generic standings table. */
    PLAYS("plays"), STANDINGS_TABLE("standingsTable"),
    /** Phase B: football live field strip and the soccer live strip (single-event tick). */
    FIELD("field"), SOCCER_STRIP("soccerStrip");

    companion object {
        /** Block types the shared core can assemble from a provider (watch/next/standings are not yet). */
        val assemblable: Set<BlockType> = setOf(
            SITUATION, LINESCORE, STATS, LEADERS, TIMELINE, LINEUP, RESULTS, STANDINGS, FORM, HIGHLIGHTS, WIN_PROBABILITY,
            PLAYS, STANDINGS_TABLE, FIELD, SOCCER_STRIP,
        )

        fun fromWire(s: String?): BlockType? = entries.firstOrNull { it.wire == s }
    }
}

enum class SideKey { HOME, AWAY }
enum class TimelineKind { GOAL, CARD, SUB, SCORE, PENALTY, CORNER, SHOT, OFFSIDE, VAR }
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
    /** Hex without '#', from the feed's team colours (Phase A). */
    val color: String? = null,
    val altColor: String? = null,
    /** Poll rank; null = unranked (ESPN's 99 is mapped to null by the assembler). */
    val rank: Int? = null,
)

sealed interface GameHeader {
    data class Teams(val home: Side, val away: Side) : GameHeader
    data class Versus(val a: Side, val b: Side) : GameHeader
    data class Event(val name: String, val session: String? = null, val venue: String? = null) : GameHeader
}

/** clock: "2H 49'", "Q2 5:12"; periodLabel: "Q2"; detail: "Halftime". */
data class LiveInfo(val clock: String? = null, val periodLabel: String? = null, val detail: String? = null)

/** A market storyline ("Goes to overtime", 8%): [yes] is 0..100, [side] set when it is about one team. */
data class Storyline(val key: String, val label: String, val yes: Int, val side: SideKey? = null)

/** One point of the market win-chance series (epoch ms). [home] is plotted as given (soccer: home's non-draw share). */
data class WinPoint(val t: Long, val home: Int, val draw: Int? = null)

data class StatRow(val label: String, val home: String, val away: String, val unit: String? = null)
data class LeaderItem(val category: String, val athlete: String, val value: String, val side: SideKey?)
data class TimelineItem(
    val time: String, val period: String?, val side: SideKey?, val text: String, val kind: TimelineKind?,
    /** ESPN play id (Phase A). */
    val id: String? = null,
    /** Parsed from the play's `wallclock`. */
    val wallclockMs: Long? = null,
    /** "10–21" (home–away) after this play, when the feed has both. */
    val score: String? = null,
)
data class StandingsGroup(val name: String, val columns: List<String>, val rows: List<StandingsTableRow>)
data class StandingsTableRow(val teamId: String?, val name: String, val cells: List<String>, val isGameTeam: Boolean = false)
/** [place] is ESPN's `formationPlace` (1 = keeper, then the rows of the formation); null when the feed has none. */
data class LineupPlayer(val number: String?, val name: String, val pos: String?, val starter: Boolean, val place: Int? = null)

/** [startYardLine] is home-based like the ball; [summary] is the feed's "3 plays, 26 yards, 0:54". */
data class FieldDrive(val startYardLine: Int?, val summary: String?)

/** kind: goal | yellow | red | sub | corner | penalty | var | shot | offside | foul. [text] is the feed's own words. */
data class SoccerEvent(val kind: String, val side: SideKey?, val minute: String?, val text: String)
data class LineupSide(val formation: String?, val players: List<LineupPlayer>)
data class StandingRow(val pos: String, val name: String, val played: String, val points: String, val gd: String? = null, val form: String? = null)

sealed interface GameBlock {
    val type: BlockType
    val source: SourceId

    data class Situation(override val source: SourceId, val text: String, val parts: Map<String, String> = emptyMap()) : GameBlock {
        override val type get() = BlockType.SITUATION
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
    /** Newest first (Phase A). */
    data class Plays(override val source: SourceId, val items: List<TimelineItem>) : GameBlock {
        override val type get() = BlockType.PLAYS
    }
    data class StandingsTable(override val source: SourceId, val groups: List<StandingsGroup>) : GameBlock {
        override val type get() = BlockType.STANDINGS_TABLE
    }
    /**
     * American football live field strip. [yardLine] is 0..100 yards from the HOME goal line, exactly as the feed gives it.
     * [possession] null = unknown (no arrow, no drive shading). [line] is the feed's own text, never built by us.
     */
    data class Field(
        override val source: SourceId,
        val yardLine: Int,
        val possession: SideKey? = null,
        val down: Int? = null,
        val distance: Int? = null,
        val line: String? = null,
        val redZone: Boolean = false,
        val homeTimeouts: Int? = null,
        val awayTimeouts: Int? = null,
        val drive: FieldDrive? = null,
    ) : GameBlock {
        override val type get() = BlockType.FIELD
    }
    /** Soccer live strip: last real event, possession, corners, cards and scorers (single-event scoreboard + summary commentary). */
    data class SoccerStrip(
        override val source: SourceId,
        val lastEvent: SoccerEvent? = null,
        val homePossession: Int? = null,
        val homeCorners: Int? = null, val awayCorners: Int? = null,
        val homeYellow: Int = 0, val awayYellow: Int = 0, val homeRed: Int = 0, val awayRed: Int = 0,
        val homeScorers: List<String> = emptyList(), val awayScorers: List<String> = emptyList(),
    ) : GameBlock {
        override val type get() = BlockType.SOCCER_STRIP
    }
    data class Form(override val source: SourceId, val home: List<String>, val away: List<String>) : GameBlock {
        override val type get() = BlockType.FORM
    }
    data class Highlights(override val source: SourceId, val url: String, val thumbnail: String? = null) : GameBlock {
        override val type get() = BlockType.HIGHLIGHTS
    }
    /**
     * 0..100, home side. The market fills [awayPercent]/[drawPercent] (soccer is 3-way: home + draw + away); ESPN gives only home.
     * [storylines] and [history] come from the market even under an ESPN headline ([extrasAttribution] then credits them);
     * [asOfMs] is the SERVER's time for the market data, so a stale served value can say so.
     */
    data class WinProbability(
        override val source: SourceId,
        val homePercent: Double,
        val awayPercent: Double? = null,
        val drawPercent: Double? = null,
        val attribution: String? = null,
        val history: List<WinPoint> = emptyList(),
        val storylines: List<Storyline> = emptyList(),
        val extrasAttribution: String? = null,
        val asOfMs: Long? = null,
    ) : GameBlock {
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

/** What belongs on screen in each state: live-only blocks vanish after the whistle, score blocks wait for it. */
object BlockVisibility {
    private val upcoming = setOf(
        BlockType.WIN_PROBABILITY, BlockType.FORM, BlockType.LINEUP, BlockType.RESULTS, BlockType.NEXT,
        BlockType.STANDINGS, BlockType.HIGHLIGHTS, BlockType.STANDINGS_TABLE,
    )

    fun visible(type: BlockType, state: GameState): Boolean = when (state) {
        GameState.LIVE -> true
        GameState.FINAL -> type != BlockType.SITUATION && type != BlockType.WIN_PROBABILITY && type != BlockType.FIELD
        GameState.UPCOMING -> type in upcoming
        GameState.POSTPONED -> false
    }
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
    /**
     * true when [header]/[state] came from an authoritative source this tick (the game's primary source, or
     * ESPN when the primary is switched off). false = the hub seed stood in; [mergeOver] then keeps the older ones.
     */
    val headerFromPrimary: Boolean = true,
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
        val authoritative = headerFromPrimary
        val mergedState = if (authoritative) state else older.state
        val fresh = blocks.associateBy { it.type }
        val combined = older.blocks.map { fresh[it.type] ?: it } + blocks.filter { b -> older.blocks.none { it.type == b.type } }
        val shown = combined.filter { BlockVisibility.visible(it.type, mergedState) }
        return copy(
            state = mergedState,
            header = if (authoritative) header else older.header,
            startsAtMs = if (authoritative) startsAtMs else older.startsAtMs,
            blocks = shown,
            sources = (older.sources + sources).filterKeys { k -> shown.any { it.type == k } },
            asOf = older.asOf + asOf,
            live = if (mergedState == GameState.LIVE) live ?: older.live else live,
            availability = if (shown.isNotEmpty()) Availability.FULL else availability,
            headerFromPrimary = authoritative || older.headerFromPrimary,
        )
    }

    companion object {
        fun skeleton(ref: GameRef, profile: String? = null) = GameDetail(
            gameId = ref.gameId, sport = ref.sport, leagueId = ref.leagueId, leagueName = ref.leagueName,
            profile = profile, round = ref.round, venue = ref.venue, startsAtMs = ref.startsAtMs,
            state = ref.seedState, header = ref.seedHeader(), live = null, blocks = emptyList(),
            sources = emptyMap(), sourceStatus = emptyMap(), availability = Availability.MATCH_SHEET, asOf = emptyMap(),
            headerFromPrimary = false,
        )
    }
}
