package com.nuvio.tv.core.iptv.match

import android.util.Log
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.tmdb.TmdbTitleBundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XtreamTmdbResolver"

data class XtreamMatch(val item: IndexedItem, val via: String)

/** What the panel's info endpoint can tell us about a candidate. */
data class VerifySignal(val tmdb: Int?, val year: Int?)

/**
 * Resolves a TMDB id to a concrete Xtream stream/series id for one account. Twin of
 * NuvioMobile's resolver; rules validated against live panels (8-round campaign).
 *
 * Three tiers, cheapest first:
 *  1. bulk-list `tmdb` field (XUI panels ship it for ~90% of items) — zero API calls
 *  2. verified-mapping cache (local mirror of the Supabase-synced table) — zero API calls
 *  3. normalized-name probes over the SQLite index, then verify candidates via
 *     get_vod_info / get_series_info (~1 call), caching the outcome — including misses.
 */
@Singleton
class XtreamTmdbResolver @Inject constructor(
    private val client: XtreamClient,
    private val index: XtreamMatchIndex,
    private val sync: XtreamMatchSyncService,
) {
    private val buildLock = Mutex()
    private val inFlightBuilds = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val lastFailedBuildMs = mutableMapOf<String, Long>()

    // index builds outlive the stream request that triggered them: a large catalog takes
    // ~a minute on-device, and users navigate away — cancelling the request must not
    // kill (and backoff-poison) the build
    private val buildScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resolve(acc: XtreamAccount, kind: MatchKind, tmdbId: Int, titles: TmdbTitleBundle): XtreamMatch? {
        ensureIndexed(acc, kind)
        val provider = acc.id
        val indexExists = index.builtAt(provider, kind) != null

        // tier 1: the panel told us outright
        index.byTmdb(provider, kind, tmdbId).minByOrNull { rankDistance(it.year, titles.year) }?.let {
            return XtreamMatch(it, "id")
        }

        // tier 2: previously verified (possibly on another device, via Supabase)
        sync.pullOnce(provider)
        index.cachedMapping(provider, kind, tmdbId)?.let { cached ->
            if (cached.sid != null) {
                index.item(provider, kind, cached.sid)?.let { return XtreamMatch(it, "cache") }
                // sid vanished from the catalog — stale mapping, fall through to re-match
            } else if (System.currentTimeMillis() - cached.updatedAtMs < NEGATIVE_TTL_MS) {
                return null // fresh "not on this provider"
            }
        }

        // tier 3: name matching + verification
        val variants = buildList {
            titles.primary?.let { add(TitleVariant(it, "primary")) }
            titles.original?.takeIf { it != titles.primary }?.let { add(TitleVariant(it, "original")) }
            titles.alternatives.forEach { add(TitleVariant(it, "alt")) }
        }
        if (variants.isEmpty()) return null

        var verifyCalls = 0
        for (probe in TitleNormalizer.probesFor(variants)) {
            val bucket = index.probe(provider, kind, probe.key)
            if (bucket.isEmpty()) continue
            // year is a ranking signal, not a gate: panels ship garbage years (epoch 1970
            // defaults), so off-year candidates still get verified — just later and never
            // auto-accepted without a confirming signal.
            val ordered = bucket.sortedBy { rankDistance(it.year, titles.year) }
            for (cand in ordered) {
                if (verifyCalls >= MAX_VERIFY_CALLS) break
                val inYear = cand.year == null || titles.year == null || yearDistance(cand.year, titles.year) <= 1
                val signal = fetchVerifySignal(acc, kind, cand).also { verifyCalls++ }
                val decision = verifyDecision(
                    signal = signal,
                    targetTmdb = tmdbId,
                    targetYear = titles.year,
                    nameYear = cand.year,
                    exactTier = probe.exactTier && inYear,
                    via = probe.via,
                )
                if (decision) {
                    Log.d(TAG, "matched tmdb=$tmdbId via=${probe.via} sid=${cand.sid} '${cand.name}'")
                    index.putMapping(provider, kind, tmdbId, cand.sid, cand.name)
                    sync.triggerPush(provider)
                    return XtreamMatch(cand, probe.via)
                }
            }
            if (verifyCalls >= MAX_VERIFY_CALLS) break
        }

        // only cache "not on this provider" when we actually had an index to search —
        // a failed/missing index must not poison the negative cache for 7 days
        if (indexExists) {
            index.putMapping(provider, kind, tmdbId, sid = null, matchedName = null)
            sync.triggerPush(provider)
        }
        return null
    }

    private suspend fun fetchVerifySignal(acc: XtreamAccount, kind: MatchKind, cand: IndexedItem): VerifySignal =
        when (kind) {
            MatchKind.MOVIE -> client.vodMatchSignal(acc, cand.sid).getOrNull()
                ?.let { VerifySignal(it.tmdbId, it.year) }
                ?: VerifySignal(null, null)
            MatchKind.SERIES -> client.seriesInfo(acc, cand.sid).getOrNull()
                ?.let { VerifySignal(it.tmdbId, it.releaseDate?.take(4)?.toIntOrNull()) }
                ?: VerifySignal(null, null)
        }

    /**
     * Builds the SQLite index from the full bulk list when missing or older than 24h.
     * Single-flight per provider+kind; failures back off for an hour. Never throws —
     * resolve degrades to whatever index exists.
     */
    suspend fun ensureIndexed(acc: XtreamAccount, kind: MatchKind) {
        val key = "${acc.id}#${kind.slug}"
        val existing = index.builtAt(acc.id, kind)
        if (existing != null && System.currentTimeMillis() - existing < INDEX_TTL_MS) return

        val (deferred, isOwner) = buildLock.withLock {
            inFlightBuilds[key]?.let { return@withLock it to false }
            // backoff applies with OR without an existing index — a dead panel must not
            // trigger a full-catalog download on every resolve attempt
            if (System.currentTimeMillis() - (lastFailedBuildMs[key] ?: 0) < BUILD_BACKOFF_MS) return
            val d = CompletableDeferred<Unit>()
            inFlightBuilds[key] = d
            d to true
        }

        if (isOwner) {
            buildScope.launch {
                try {
                    val items = when (kind) {
                        MatchKind.MOVIE -> client.vodMovies(acc).getOrThrow().map {
                            IndexedItem(it.streamId, it.name, TitleNormalizer.yearOf(it.name), it.tmdb, it.containerExtension)
                        }
                        MatchKind.SERIES -> client.series(acc).getOrThrow().map {
                            IndexedItem(it.seriesId, it.name, it.year ?: TitleNormalizer.yearOf(it.name), it.tmdb, null)
                        }
                    }
                    index.rebuild(acc.id, kind, items)
                    Log.i(TAG, "indexed ${items.size} ${kind.slug} items for ${acc.name}")
                    buildLock.withLock { lastFailedBuildMs.remove(key) }
                } catch (t: Throwable) {
                    Log.w(TAG, "index build failed for ${acc.name} ${kind.slug}", t)
                    buildLock.withLock { lastFailedBuildMs[key] = System.currentTimeMillis() }
                } finally {
                    buildLock.withLock { inFlightBuilds.remove(key) }
                    deferred.complete(Unit)
                }
            }
        }
        // await is cancellable (the caller's request may die); the build itself is not
        deferred.await()
    }

    companion object {
        private const val INDEX_TTL_MS = 24 * 60 * 60 * 1000L
        private const val BUILD_BACKOFF_MS = 60 * 60 * 1000L
        private const val NEGATIVE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_VERIFY_CALLS = 3

        /**
         * The acceptance rules distilled from the live-panel campaign — pure so the test
         * suite can hammer them:
         *  - panel tmdb id decides outright when present (equality or rejection)
         *  - else best year signal (info year, then name year): exact tiers get ±1, inexact
         *    tiers (trunc/skeleton/nodigit/off-year) demand an exact year
         *  - no signal at all: only exact-tier primary/original matches pass
         */
        fun verifyDecision(
            signal: VerifySignal,
            targetTmdb: Int,
            targetYear: Int?,
            nameYear: Int?,
            exactTier: Boolean,
            via: String,
        ): Boolean {
            signal.tmdb?.let { return it == targetTmdb }
            val year = signal.year ?: nameYear
            if (year != null && targetYear != null) {
                val d = yearDistance(year, targetYear)
                return if (exactTier) d <= 1 else d == 0
            }
            return exactTier && (via.startsWith("primary") || via.startsWith("original"))
        }

        private fun yearDistance(a: Int?, b: Int?): Int = if (a == null || b == null) 0 else if (a > b) a - b else b - a

        /** Verify-order ranking: year-exact candidates first, unknown-year candidates last. */
        private fun rankDistance(a: Int?, b: Int?): Int = if (a == null || b == null) 999 else yearDistance(a, b)
    }
}
