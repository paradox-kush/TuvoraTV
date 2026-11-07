package com.nuvio.tv.core.sports.detail

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** What a source returned for one load. Sources never throw; failures are [FetchOutcome] values. */
data class SourceLoad(
    val outcome: FetchOutcome,
    val header: GameHeader? = null,
    val state: GameState? = null,
    val live: LiveInfo? = null,
    val blocks: Map<BlockType, GameBlock> = emptyMap(),
    /** The source answered that it is switched off (server kill switch). */
    val disabled: Boolean = false,
    /** Blocks this load actually asked the provider for; null = every wanted block. Telemetry reports only these. */
    val asked: Set<BlockType>? = null,
) {
    companion object {
        fun ok(blocks: Map<BlockType, GameBlock>, header: GameHeader? = null, state: GameState? = null, live: LiveInfo? = null) =
            SourceLoad(if (blocks.isNotEmpty() || header != null) FetchOutcome.HIT else FetchOutcome.EMPTY, header, state, live, blocks)

        fun miss() = SourceLoad(FetchOutcome.EMPTY)
        fun failed(outcome: FetchOutcome) = SourceLoad(outcome)
        fun disabled() = SourceLoad(FetchOutcome.EMPTY, disabled = true)
    }
}

interface BlockSource {
    val id: SourceId

    /** false = this source is not consulted for [parts] at all (no request, no status, no telemetry). */
    fun wants(parts: Set<RefreshPart>): Boolean = true

    suspend fun load(ref: GameRef, parts: Set<RefreshPart>, mapping: SportsMapping): SourceLoad
}

/**
 * Per-game memory of a source that missed or failed, so a probe isn't repeated every tick. A miss
 * (no event / no data) is remembered for 10 minutes, a transient failure for 30 seconds.
 */
class NegativeSourceCache(private val clock: () -> Long) {
    data class Entry(val outcome: FetchOutcome, val untilMs: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun cooling(gameId: String, source: SourceId): Entry? =
        map["$gameId|${source.wire}"]?.takeIf { it.untilMs > clock() }

    fun record(gameId: String, source: SourceId, outcome: FetchOutcome) {
        val key = "$gameId|${source.wire}"
        when (outcome) {
            FetchOutcome.HIT -> map.remove(key)
            FetchOutcome.EMPTY -> map[key] = Entry(outcome, clock() + MISS_TTL_MS)
            else -> map[key] = Entry(outcome, clock() + FAILURE_TTL_MS)
        }
    }

    companion object {
        const val MISS_TTL_MS = 10 * 60_000L
        const val FAILURE_TTL_MS = 30_000L
    }
}

/**
 * Resolves a game block by block along the source chain (default TheSportsDB then ESPN; per-league
 * override in the mapping). Sources are asked IN PARALLEL (each under the mapping's per-call deadline), so the
 * worst case is one deadline, not the sum; results are then folded in chain order. A source failing or
 * returning nothing for one block never blanks the others; a total failure is [Availability.FAILED], never an
 * empty match sheet. Never throws (except coroutine cancellation).
 *
 * Header, state and score always come from the game's primary source; when that source is switched off the
 * header comes from ESPN, and when it merely failed the hub seed stands in ([GameDetail.headerFromPrimary] = false).
 */
class GameDetailResolver(
    private val mappingProvider: () -> SportsMapping,
    private val sources: Map<SourceId, BlockSource>,
    private val telemetry: SportsDetailTelemetry = SportsDetailTelemetry.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val negativeCache: NegativeSourceCache = NegativeSourceCache(clock),
) {
    /** Telemetry is reported once per (sport, block, source, outcome) per session, not per tick. */
    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun report(e: SportsDetailEvent) {
        if (reported.add("${e.sport}|${e.block?.wire}|${e.source.wire}|${e.outcome.wire}")) telemetry.record(e)
    }

    private class Timed(val load: SourceLoad, val ms: Long)

    suspend fun resolve(ref: GameRef, parts: Set<RefreshPart> = RefreshPart.ALL): GameDetail = coroutineScope {
        val mapping = mappingProvider()
        val profileName = mapping.profileNameFor(ref.leagueId, ref.sport)
        val profile = mapping.profile(profileName)
        val league = mapping.leagueFor(ref.leagueId)
        val wanted = profile?.blockTypes.orEmpty()
        val sportTag = profileName ?: "unknown"

        val status = LinkedHashMap<SourceId, SourceStatus>()
        val chain = league?.chain ?: listOf(SourceId.TSDB, SourceId.ESPN)
        val jobs = LinkedHashMap<SourceId, Deferred<Timed>>()

        for (id in chain) {
            val source = sources[id] ?: continue
            if (!source.wants(parts)) continue
            if (id == SourceId.ESPN) {
                if (!mapping.espnEnabled(profileName, ref.leagueId)) { status[id] = SourceStatus.DISABLED; continue }
                if (profile == null || league?.espnPath == null) continue
                val cooling = negativeCache.cooling(ref.gameId, id)
                if (cooling != null) { status[id] = statusOf(cooling.outcome); continue }
            }
            if (id == SourceId.TSDB && !mapping.gameDetailEnabled(ref.sport.ifBlank { league?.sport }, ref.leagueId)) {
                status[id] = SourceStatus.DISABLED; continue
            }
            jobs[id] = async {
                val started = System.nanoTime()
                val load = try {
                    withTimeout(mapping.deadlineMs) { source.load(ref, parts, mapping) }
                } catch (_: TimeoutCancellationException) {
                    SourceLoad.failed(FetchOutcome.HTTP_FAIL)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SourceLoad.failed(FetchOutcome.HTTP_FAIL)
                }
                Timed(load, (System.nanoTime() - started) / 1_000_000)
            }
        }

        val loads = LinkedHashMap<SourceId, SourceLoad>()
        val resolved = LinkedHashMap<BlockType, GameBlock>()
        for ((id, job) in jobs) {
            val (load, ms) = job.await().let { it.load to it.ms }
            loads[id] = load
            status[id] = if (load.disabled) SourceStatus.DISABLED else statusOf(load.outcome)
            if (id == SourceId.ESPN && (load.outcome != FetchOutcome.EMPTY || RefreshPart.SCOREBOARD in parts)) {
                negativeCache.record(ref.gameId, id, load.outcome)
            }
            if (load.outcome.isFailure || load.disabled || (load.blocks.isEmpty() && load.header == null)) {
                report(SportsDetailEvent(sportTag, null, id, if (load.disabled) FetchOutcome.EMPTY else load.outcome, ms))
                continue
            }
            for (type in wanted) {
                if (type in resolved) continue
                val b = load.blocks[type]
                if (b == null && load.asked != null && type !in load.asked) continue
                report(SportsDetailEvent(sportTag, type, id, if (b != null) FetchOutcome.HIT else FetchOutcome.EMPTY, ms))
                if (b != null) resolved[type] = b
            }
        }

        val primaryId = ref.primary
        val hit = { id: SourceId -> loads[id]?.takeIf { it.outcome == FetchOutcome.HIT && !it.disabled } }
        val espn = hit(SourceId.ESPN)
        val primary = hit(primaryId)
        // Primary switched off (server kill switch): ESPN becomes the authority for header and state.
        val authority = primary ?: espn.takeIf { status[primaryId] == SourceStatus.DISABLED }
        val state = authority?.state ?: espn?.state ?: ref.seedState
        val live = when {
            authority != null && authority === espn -> espn.live
            primaryId == SourceId.TSDB && espn?.live != null && sameScore(authority?.header, espn.header) -> espn.live
            else -> authority?.live
        }
        val blocks = resolved.values
            .filter { BlockVisibility.visible(it.type, state) }
            .sortedBy { profile?.orderOf(it.type) ?: Int.MAX_VALUE }
        val availability = when {
            blocks.isNotEmpty() -> Availability.FULL
            status.values.any { it == SourceStatus.FAILED } -> Availability.FAILED
            else -> Availability.MATCH_SHEET
        }
        val now = clock()
        GameDetail(
            gameId = ref.gameId, sport = ref.sport, leagueId = ref.leagueId, leagueName = ref.leagueName,
            profile = profileName, round = ref.round, venue = ref.venue, startsAtMs = ref.startsAtMs,
            state = state,
            header = authority?.header ?: ref.seedHeader(),
            live = live,
            blocks = blocks,
            sources = blocks.associate { it.type to it.source },
            sourceStatus = status,
            availability = availability,
            asOf = loads.filterValues { it.outcome == FetchOutcome.HIT }.mapValues { now },
            headerFromPrimary = authority?.header != null,
        )
    }

    /** ESPN's richer live clock is only used when it agrees with the primary on the score; never across a mislink. */
    private fun sameScore(a: GameHeader?, b: GameHeader?): Boolean = when (a) {
        is GameHeader.Teams -> (b as? GameHeader.Teams)?.let { a.home.score != null && a.home.score == it.home.score && a.away.score == it.away.score } == true
        is GameHeader.Versus -> (b as? GameHeader.Versus)?.let { a.a.score != null && a.a.score == it.a.score && a.b.score == it.b.score } == true
        is GameHeader.Event -> true
        null -> false
    }

    private fun statusOf(o: FetchOutcome) = when (o) {
        FetchOutcome.HIT -> SourceStatus.OK
        FetchOutcome.EMPTY -> SourceStatus.MISS
        else -> SourceStatus.FAILED
    }
}
