package com.nuvio.tv.core.sports.detail

import kotlinx.coroutines.TimeoutCancellationException
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
) {
    companion object {
        fun ok(blocks: Map<BlockType, GameBlock>, header: GameHeader? = null, state: GameState? = null, live: LiveInfo? = null) =
            SourceLoad(if (blocks.isNotEmpty() || header != null) FetchOutcome.HIT else FetchOutcome.EMPTY, header, state, live, blocks)

        fun miss() = SourceLoad(FetchOutcome.EMPTY)
        fun failed(outcome: FetchOutcome) = SourceLoad(outcome)
    }
}

interface BlockSource {
    val id: SourceId
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
 * override in the mapping). A source failing or returning nothing for one block never blanks the
 * others; a total failure is reported as [Availability.FAILED], never as an empty match sheet.
 * Never throws (except coroutine cancellation).
 */
class GameDetailResolver(
    private val mappingProvider: () -> SportsMapping,
    private val sources: Map<SourceId, BlockSource>,
    private val telemetry: SportsDetailTelemetry = SportsDetailTelemetry.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val negativeCache: NegativeSourceCache = NegativeSourceCache(clock),
) {
    suspend fun resolve(ref: GameRef, parts: Set<RefreshPart> = RefreshPart.ALL): GameDetail {
        val mapping = mappingProvider()
        val profileName = mapping.profileNameFor(ref.leagueId, ref.sport)
        val profile = mapping.profile(profileName)
        val league = mapping.leagueFor(ref.leagueId)
        val wanted = profile?.blockTypes.orEmpty()
        val sportTag = profileName ?: "unknown"

        val status = LinkedHashMap<SourceId, SourceStatus>()
        val loads = LinkedHashMap<SourceId, SourceLoad>()
        val chain = league?.chain ?: listOf(SourceId.TSDB, SourceId.ESPN)
        val resolved = LinkedHashMap<BlockType, GameBlock>()

        for (id in chain) {
            val source = sources[id] ?: continue
            if (id == SourceId.ESPN) {
                if (!mapping.espnEnabled(profileName, ref.leagueId)) { status[id] = SourceStatus.DISABLED; continue }
                if (profile == null || league?.espnPath == null) continue
                val cooling = negativeCache.cooling(ref.gameId, id)
                if (cooling != null) { status[id] = statusOf(cooling.outcome); continue }
            }
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
            val ms = (System.nanoTime() - started) / 1_000_000
            loads[id] = load
            status[id] = statusOf(load.outcome)
            if (id == SourceId.ESPN && (load.outcome != FetchOutcome.EMPTY || RefreshPart.SCOREBOARD in parts)) {
                negativeCache.record(ref.gameId, id, load.outcome)
            }
            if (load.outcome.isFailure) {
                telemetry.record(SportsDetailEvent(sportTag, null, id, load.outcome, ms))
                continue
            }
            for (type in wanted) {
                if (type in resolved) continue
                val b = load.blocks[type]
                telemetry.record(SportsDetailEvent(sportTag, type, id, if (b != null) FetchOutcome.HIT else FetchOutcome.EMPTY, ms))
                if (b != null) resolved[type] = b
            }
        }

        val primary = loads[ref.primary]
        val blocks = resolved.values.sortedBy { profile?.orderOf(it.type) ?: Int.MAX_VALUE }
        val availability = when {
            blocks.isNotEmpty() -> Availability.FULL
            status.values.any { it == SourceStatus.FAILED } -> Availability.FAILED
            else -> Availability.MATCH_SHEET
        }
        val now = clock()
        return GameDetail(
            gameId = ref.gameId, sport = ref.sport, leagueId = ref.leagueId, leagueName = ref.leagueName,
            profile = profileName, round = ref.round, venue = ref.venue, startsAtMs = ref.startsAtMs,
            state = primary?.state ?: ref.seedState,
            header = primary?.header ?: ref.seedHeader(),
            live = primary?.live ?: loads[SourceId.ESPN]?.live ?: loads[SourceId.TSDB]?.live,
            blocks = blocks,
            sources = resolved.mapValues { it.value.source },
            sourceStatus = status,
            availability = availability,
            asOf = loads.filterValues { it.outcome == FetchOutcome.HIT }.mapValues { now },
        )
    }

    private fun statusOf(o: FetchOutcome) = when (o) {
        FetchOutcome.HIT -> SourceStatus.OK
        FetchOutcome.EMPTY -> SourceStatus.MISS
        else -> SourceStatus.FAILED
    }
}
