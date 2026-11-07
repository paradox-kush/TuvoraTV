package com.nuvio.tv.core.sports.detail

/** BoundedLoad semantics: a load ends Loaded / Empty / Failed, and a failure is never rendered as empty. */
sealed interface DetailLoad {
    data class Loaded(val detail: GameDetail) : DetailLoad
    /** The sources answered and there is no such game. */
    data object Empty : DetailLoad
    /** Every source failed (or hit its deadline) and nothing was shown before. Show Retry. */
    data class Failed(val timedOut: Boolean = false) : DetailLoad
}

/** One open game screen. The caller owns the lifecycle and the cadence ([GameDetailRefreshPolicy.plan]). */
interface GameDetailSource {
    /**
     * [fetchEvent]: refresh score/status. [fetchSummary]: refresh the heavier per-game payloads. Anything
     * not refreshed is kept from the last tick (live ticks fold over the previous detail). The first tick
     * always fetches both. A failed tick after a good one keeps showing the good detail.
     */
    suspend fun tick(fetchEvent: Boolean = true, fetchSummary: Boolean = true): DetailLoad
}

interface SportsDetailService {
    fun open(ref: GameRef): GameDetailSource

    /** ETag poll of the remote mapping; failures and invalid bodies keep the last good one. Cheap (304). */
    suspend fun syncMapping(): OfferResult?

    fun refreshConfig(): GameDetailRefreshPolicy.Config

    /** The active mapping (bundled, or the last good remote one). */
    fun currentMapping(): SportsMapping
}

object SportsDetail {
    /**
     * Wires the core. [functionsBase] is the backend functions root (`https://<project>.supabase.co/functions/v1`),
     * or null when no backend is configured (ESPN-only then).
     */
    fun create(
        http: HttpFetcher,
        functionsBase: () -> String?,
        persistence: MappingPersistence = InMemoryMappingPersistence(),
        telemetry: SportsDetailTelemetry = SportsDetailTelemetry.None,
        clock: () -> Long = System::currentTimeMillis,
    ): SportsDetailService {
        val store = SportsMappingStore(BundledSportsMapping.load(), persistence, SmokeFixture.bundled()).also { it.restore() }
        val resolver = GameDetailResolver(
            mappingProvider = { store.current },
            sources = listOf<BlockSource>(TheSportsDbSource(http, functionsBase, clock), EspnSource(http, clock)).associateBy { it.id },
            telemetry = telemetry,
            clock = clock,
        )
        val updater = SportsMappingUpdater(store, HttpMappingRemote(http, functionsBase), persistence)
        return object : SportsDetailService {
            override fun open(ref: GameRef): GameDetailSource = ResolvingGameDetailSource(resolver, ref)
            override suspend fun syncMapping() = updater.refresh()
            override fun refreshConfig() = GameDetailRefreshPolicy.Config.from(store.current.refresh)
            override fun currentMapping() = store.current
        }
    }
}

internal class ResolvingGameDetailSource(private val resolver: GameDetailResolver, private val ref: GameRef) : GameDetailSource {
    private var last: GameDetail? = null

    override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad {
        val parts = buildSet {
            if (fetchEvent || last == null) add(RefreshPart.SCOREBOARD)
            if (fetchSummary || last == null) add(RefreshPart.SUMMARY)
        }
        val fresh = resolver.resolve(ref, parts)
        val prev = last
        val merged = if (prev == null) fresh else fresh.mergeOver(prev)
        if (merged.availability == Availability.FAILED) return DetailLoad.Failed()
        last = merged
        return DetailLoad.Loaded(merged)
    }
}
