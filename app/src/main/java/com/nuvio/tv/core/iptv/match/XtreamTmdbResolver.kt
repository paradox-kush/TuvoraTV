package com.nuvio.tv.core.iptv.match

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.core.util.DeviceClass
import com.nuvio.tv.core.tmdb.TmdbTitleBundle
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.core.journal.JournalOutcome
import com.nuvio.tv.core.journal.StartupJournal
import com.nuvio.tv.core.util.Guarded
import com.nuvio.tv.core.util.containTask
import com.nuvio.tv.core.util.guarded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.posthog.PostHog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XtreamTmdbResolver"

data class XtreamMatch(val item: IndexedItem, val via: String)

/** What the panel's info endpoint can tell us about a candidate. */
data class VerifySignal(val tmdb: Int?, val year: Int?)

/** [XtreamTmdbResolver.verifyDecision]'s verdict — PROVISIONAL wins only when nothing CONFIRMS. */
enum class VerifyVerdict { CONFIRMED, PROVISIONAL, REJECTED }

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
    @ApplicationContext private val context: Context,
    private val client: XtreamClient,
    private val index: XtreamMatchIndex,
    private val sync: XtreamMatchSyncService,
    private val accountStore: XtreamAccountStore,
) {
    private val buildLock = Mutex()
    private val inFlightBuilds = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val lastFailedBuildMs = mutableMapOf<String, Long>()
    private val buildBackoffPrefs by lazy {
        context.getSharedPreferences(BUILD_BACKOFF_PREFS, Context.MODE_PRIVATE)
    }

    // account ids whose catalog index is currently building — drives the
    // "Preparing catalog…" status on the IPTV settings rows
    private val indexingCounts = mutableMapOf<String, Int>()
    private val _indexing = MutableStateFlow<Set<String>>(emptySet())
    val indexing: StateFlow<Set<String>> = _indexing.asStateFlow()

    // index builds outlive the stream request that triggered them: a large catalog takes
    // ~a minute on-device, and users navigate away — cancelling the request must not
    // kill (and backoff-poison) the build
    private val buildScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes catalog builds across every account — see the use site in [ensureIndexed]. */
    private val buildSlot = Semaphore(1)

    /**
     * Reports how a catalog index build went, so the memory profile of this path is visible
     * in analytics instead of only as an unexplained OS kill. `item_count` is the number
     * that matters: the peak scales with it, and it's the field to look at first when a
     * device starts getting killed.
     */
    private fun reportBuild(kind: MatchKind, itemCount: Int, startedMs: Long, outcome: String, detail: String?) {
        runCatching {
            PostHog.capture(
                event = "iptv_index_build",
                properties = buildMap {
                    put("kind", kind.slug)
                    put("outcome", outcome)
                    put("item_count", itemCount)
                    put("duration_ms", System.currentTimeMillis() - startedMs)
                    put("max_heap_mb", (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt())
                    put("used_heap_mb", ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)).toInt())
                    detail?.let { put("detail", it.take(300)) }
                },
            )
        }
    }

    /**
     * Fire-and-forget index warm-up (account add, app start, sync-in) so the first
     * resolve/search doesn't pay the full-catalog download on demand — on budget boxes
     * that's minutes, which reads as "finding the movie takes forever". Only pure Xtream
     * accounts participate in TMDB matching (M3U/Stalker have no player_api catalog).
     */
    fun warmUp(accounts: List<XtreamAccount>, startDelayMs: Long = 0L) {
        // Recovery safe mode: the app is crash-looping before it reaches an interactive screen, so
        // withhold ALL optional index warm-ups this run (the heaviest cold-start allocation). The
        // per-account durable backoff in ensureIndexed still applies once out of safe mode.
        if (StartupJournal.isSafeMode) return
        // Low-RAM devices used to skip this build outright — justified when it buffered the
        // whole catalog in heap. The build STREAMS now (measured ~54-87 MB total app heap on a
        // 2 GB device mid-build, its normal operating range), and skipping became actively
        // harmful: without an index the hub falls back to whole-category in-memory fetches —
        // the exact allocation pattern low-RAM devices can't afford. They warm up like everyone
        // else, serialized by buildSlot; only the startup delay is stretched so the home screen
        // finishes first.
        val lowRam = DeviceClass.isLowRam(context)
        val delayMs = if (lowRam) startDelayMs * 2 else startDelayMs
        accounts.filter { it.enabled && it.isXtream() }.forEach { acc ->
            buildScope.launch {
                if (delayMs > 0) delay(delayMs)
                // Contain every failure at the task boundary — this is a bare launch on a
                // SupervisorJob scope with no CoroutineExceptionHandler, so an uncaught throwable
                // here crashes the app. ensureIndexed is contracted never to throw, but a foreign
                // throw from an early index-state read must degrade to a skipped warm-up, never a
                // fatal cold-start loop. containTask re-raises CancellationException.
                containTask(onError = { Log.w(TAG, "warm-up index build failed for ${acc.name}", it) }) {
                    // Respect the playlist's content types: get_vod_streams is the single largest
                    // fetch the app makes (~15 MB on a 60k panel, whole catalog in one response —
                    // the API has no paging), so a user who turned Movies off was still paying for it.
                    if (acc.typeEnabled(XtreamAccount.TYPE_LIVE)) ensureIndexed(acc, MatchKind.LIVE)
                    if (acc.typeEnabled(XtreamAccount.TYPE_MOVIES)) ensureIndexed(acc, MatchKind.MOVIE)
                    if (acc.typeEnabled(XtreamAccount.TYPE_SERIES)) ensureIndexed(acc, MatchKind.SERIES)
                }
            }
        }
    }

    /** App-start warm-up for every enabled account, delayed so the home screen wins the cold-start bandwidth. */
    fun warmUpAll() {
        buildScope.launch {
            // Runs from NuvioApplication.onCreate — the earliest, most crash-loop-prone moment.
            // Reading the account store (a Flow.first()) can throw; contain it so a cold start never
            // dies here. Per-account build failures are already contained inside warmUp's launches.
            containTask(onError = { Log.w(TAG, "startup warm-up scheduling failed", it) }) {
                warmUp(accountStore.accounts.first(), startDelayMs = STARTUP_WARM_DELAY_MS)
            }
        }
    }

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
            } else if (System.currentTimeMillis() - cached.updatedAtMs < NEGATIVE_TTL_MS &&
                cached.updatedAtMs > index.lastAddedAt(provider, kind)
            ) {
                // Fresh "not on this provider" — and the catalog hasn't gained titles since the
                // verdict. A panel that added items (daily on real providers) falsifies every
                // older miss, so those fall through to a re-match instead of hiding new content
                // for NEGATIVE_TTL_MS (measured: a title the panel added 3 days after the synced
                // verdict stayed invisible on every device).
                return null
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
        // best candidate whose panel tmdb id disagreed but whose year+exact-name confirmed
        // (junk-id panels) — accepted only after the whole verify budget fails to CONFIRM
        var provisional: XtreamMatch? = null
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
                when (decision) {
                    VerifyVerdict.CONFIRMED -> {
                        Log.d(TAG, "matched tmdb=$tmdbId via=${probe.via} sid=${cand.sid} '${cand.name}'")
                        index.putMapping(provider, kind, tmdbId, cand.sid, cand.name)
                        sync.triggerPush(provider)
                        return XtreamMatch(cand, probe.via)
                    }
                    // probes run best-first, so the first provisional is the best-ranked one
                    VerifyVerdict.PROVISIONAL -> if (provisional == null) {
                        provisional = XtreamMatch(cand, "${probe.via}+id-mismatch")
                    }
                    VerifyVerdict.REJECTED -> {}
                }
            }
            if (verifyCalls >= MAX_VERIFY_CALLS) break
        }
        provisional?.let {
            Log.d(TAG, "matched tmdb=$tmdbId via=${it.via} sid=${it.item.sid} '${it.item.name}' (panel id overridden by exact name+year)")
            index.putMapping(provider, kind, tmdbId, it.item.sid, it.item.name)
            sync.triggerPush(provider)
            return it
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
            MatchKind.LIVE -> VerifySignal(null, null)   // live rows are never TMDB-verified
            MatchKind.MOVIE -> client.vodMatchSignal(acc, cand.sid).getOrNull()
                ?.let { VerifySignal(it.tmdbId, it.year) }
                ?: VerifySignal(null, null)
            MatchKind.SERIES -> client.seriesInfo(acc, cand.sid).getOrNull()
                ?.let { VerifySignal(it.tmdbId, it.releaseDate?.take(4)?.toIntOrNull()) }
                ?: VerifySignal(null, null)
        }

    /**
     * Syncs the SQLite index from the full bulk list when missing or older than the TTL
     * (incremental diff — unchanged titles are validated by fingerprint, not re-indexed).
     * [force] skips the TTL check and awaits the sync — the auto-refresh worker uses it to
     * honor the playlist's own refresh interval. Single-flight per provider+kind; failures
     * back off for an hour. Never throws — resolve degrades to whatever index exists.
     */
    suspend fun ensureIndexed(acc: XtreamAccount, kind: MatchKind, force: Boolean = false) {
        val key = "${acc.id}#${kind.slug}"
        // The index-state read is a SQLite query BEFORE the build's own try/catch, on the caller's
        // coroutine (a warm-up launch, a resolve() call, or a headless refresh worker). A
        // locked/corrupt handle must not escape ensureIndexed — its contract is "never throws;
        // resolve degrades to whatever index exists". Degrade to a skipped build this cycle. A null
        // value (no index yet) is preserved via Guarded.Ok; only a THROW returns early.
        val existing = when (
            val g = guarded(onError = {
                Log.w(TAG, "index-state read failed for ${acc.name} ${kind.slug}; skipping build this cycle", it)
            }) { index.builtAt(acc.id, kind) }
        ) {
            is Guarded.Ok -> g.value
            Guarded.Failed -> return
        }
        if (!force && existing != null && System.currentTimeMillis() - existing < INDEX_TTL_MS) return

        // Durable cross-run backoff (survives an OS kill that bypasses the catch below): if a prior
        // run began this build and never recorded an outcome, defer with bounded backoff instead of
        // repeating a build that may have killed the process. Complements the SharedPrefs failure
        // backoff (lastFailedAt) already checked in the buildLock below. See StartupJournal.
        if (StartupJournal.shouldDefer(StartupJournal.OP_INDEX_BUILD, key)) return

        val (deferred, isOwner) = buildLock.withLock {
            inFlightBuilds[key]?.let { return@withLock it to false }
            // backoff applies with OR without an existing index — a dead panel must not
            // trigger a full-catalog download on every resolve attempt
            if (System.currentTimeMillis() - lastFailedAt(key) < BUILD_BACKOFF_MS) return
            val d = CompletableDeferred<Unit>()
            inFlightBuilds[key] = d
            markIndexingLocked(acc.id, +1)
            d to true
        }

        if (isOwner) {
            buildScope.launch {
                // Attempt-before-work: record the attempt durably BEFORE the heavy build, so an OS
                // kill mid-build (which bypasses every catch below) is detected next run and deferred.
                // A null token means the attempt could not be persisted — withhold the build this run.
                val token = StartupJournal.beginAttempt(StartupJournal.OP_INDEX_BUILD, key)
                if (token == null) {
                    buildLock.withLock {
                        inFlightBuilds.remove(key)
                        markIndexingLocked(acc.id, -1)
                    }
                    deferred.complete(Unit)
                    return@launch
                }
                val startedMs = System.currentTimeMillis()
                var itemCount = 0
                try {
                    // One catalog build at a time across ALL accounts. Each build peaks at the
                    // size of one catalog; letting several accounts warm up concurrently stacked
                    // those peaks in the same heap, which a TV stick's 192 MB ceiling can't take.
                    buildSlot.withPermit {
                        // STREAMED: each parsed row goes straight into the SQLite session and is
                        // then garbage, so a build peaks at one 5k flush-chunk instead of the
                        // whole catalog (~40-50 MB of IndexedItem on a 175k panel — the
                        // allocation this resolver's own OOM handler below exists for).
                        // The category list rides along (P7): stored on success so the hub can
                        // serve section rows without a per-session network fetch.
                        val cats = when (kind) {
                            MatchKind.MOVIE -> client.vodCategories(acc)
                            MatchKind.SERIES -> client.seriesCategories(acc)
                            MatchKind.LIVE -> client.liveCategories(acc)
                        }.getOrNull()
                        val session = index.beginSync(acc.id, kind)
                        val fetched = when (kind) {
                            MatchKind.MOVIE -> client.vodIndexItemsInto(acc, session::accept).getOrThrow()
                            MatchKind.SERIES -> client.seriesIndexItemsInto(acc, session::accept).getOrThrow()
                            MatchKind.LIVE -> client.liveIndexItemsInto(acc, session::accept).getOrThrow()
                        }
                        itemCount = fetched
                        // An empty list where we previously indexed content is a panel glitch, not a
                        // real catalog — fail into the 1h backoff instead of re-fetching every
                        // resolve. Checked BEFORE finish() so built_at stays untouched.
                        check(fetched > 0 || index.builtAt(acc.id, kind) == null) {
                            "panel returned an empty ${kind.slug} list"
                        }
                        val stats = session.finish()
                        cats?.let { index.replaceCategories(acc.id, kind, it.map { c -> c.id to c.name }) }
                        Log.i(TAG, "synced ${kind.slug} index for ${acc.name}: +${stats.added} ~${stats.changed} -${stats.removed} (${stats.total} total)")
                    }
                    reportBuild(kind, itemCount, startedMs, outcome = "ok", detail = null)
                    StartupJournal.record(StartupJournal.OP_INDEX_BUILD, key, token, JournalOutcome.COMPLETED)
                    buildLock.withLock { clearLastFailure(key) }
                } catch (c: CancellationException) {
                    // Preserve cancellation — do NOT swallow it or treat it as a failure/backoff.
                    // Record it as a non-failure so it is not mistaken for an abnormal death next run,
                    // then re-raise so structured cancellation of buildScope still works.
                    StartupJournal.record(StartupJournal.OP_INDEX_BUILD, key, token, JournalOutcome.CANCELLED)
                    throw c
                } catch (oom: OutOfMemoryError) {
                    // Deliberately not rethrown: the build owns the only large allocations here,
                    // so releasing them and backing off recovers, where a rethrow would take the
                    // app down. But it MUST be reported — a swallowed OOM is exactly why this
                    // showed up in telemetry only as an OS low-memory kill with no stack trace.
                    Log.e(TAG, "index build ran out of memory for ${acc.name} ${kind.slug} ($itemCount items)", oom)
                    reportBuild(kind, itemCount, startedMs, outcome = "oom", detail = oom.message)
                    StartupJournal.record(StartupJournal.OP_INDEX_BUILD, key, token, JournalOutcome.EXPECTED_FAILURE)
                    buildLock.withLock { recordFailure(key) }
                } catch (t: Throwable) {
                    Log.w(TAG, "index build failed for ${acc.name} ${kind.slug}", t)
                    reportBuild(kind, itemCount, startedMs, outcome = "error", detail = "${t::class.java.simpleName}: ${t.message}")
                    StartupJournal.record(StartupJournal.OP_INDEX_BUILD, key, token, JournalOutcome.EXPECTED_FAILURE)
                    buildLock.withLock { recordFailure(key) }
                } finally {
                    buildLock.withLock {
                        inFlightBuilds.remove(key)
                        markIndexingLocked(acc.id, -1)
                    }
                    deferred.complete(Unit)
                }
            }
        }
        // A stale-but-present index serves immediately — yesterday's catalog still
        // resolves, and the sync lands in the background. Only a MISSING index (or a
        // forced refresh, which wants the fresh result) is worth blocking the caller for.
        if (existing != null && !force) return
        // await is cancellable (the caller's request may die); the build itself is not
        deferred.await()
    }

    /** Callers hold [buildLock]. Tracks per-account in-flight build counts for [indexing]. */
    private fun markIndexingLocked(accountId: String, delta: Int) {
        val n = (indexingCounts[accountId] ?: 0) + delta
        if (n <= 0) {
            indexingCounts.remove(accountId)
            // Last in-flight build for the account ended (or failed) — drop the running count so
            // the next build starts from zero instead of continuing yesterday's total.
            index.clearBuildProgress(accountId)
        } else {
            indexingCounts[accountId] = n
        }
        _indexing.value = indexingCounts.keys.toSet()
    }

    /**
     * Failed catalog backoff must survive the crash/relaunch loop it is intended to stop.
     * The old in-memory map reset on every OS kill, so malformed panels immediately started
     * another multi-minute download after launch and compounded memory/CPU pressure.
     */
    private fun lastFailedAt(key: String): Long =
        lastFailedBuildMs[key] ?: buildBackoffPrefs.getLong(key, 0L).also {
            if (it > 0L) lastFailedBuildMs[key] = it
        }

    private fun recordFailure(key: String) {
        val now = System.currentTimeMillis()
        lastFailedBuildMs[key] = now
        // Already on Dispatchers.IO. commit() makes the guard durable before another OS kill.
        buildBackoffPrefs.edit().putLong(key, now).commit()
    }

    private fun clearLastFailure(key: String) {
        lastFailedBuildMs.remove(key)
        buildBackoffPrefs.edit().remove(key).commit()
    }

    companion object {
        // Passive staleness ceiling (resolve/search/app-start paths). The auto-refresh worker
        // drives the USER-configured cadence (playlist autoRefreshHours, default 24h) with a
        // cheap incremental sync; this TTL is just the fallback when that's off or never ran.
        private const val INDEX_TTL_MS = 72 * 60 * 60 * 1000L
        private const val BUILD_BACKOFF_MS = 60 * 60 * 1000L
        private const val BUILD_BACKOFF_PREFS = "xtream_index_build_backoff"
        private const val STARTUP_WARM_DELAY_MS = 10_000L
        private const val NEGATIVE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_VERIFY_CALLS = 3

        /**
         * The acceptance rules distilled from the live-panel campaign — pure so the test
         * suite can hammer them:
         *  - a matching panel tmdb id confirms outright
         *  - a MISMATCHING panel id rejects — unless the panel's own year signal confirms
         *    the target exactly on an exact-tier primary/original name hit, which downgrades
         *    to PROVISIONAL instead: junk id feeds exist (a live panel ships the constant
         *    tmdb_id 1111 for its entire catalog), and there the id contradicts the panel's
         *    own metadata. A provisional candidate is used only when nothing CONFIRMS, so
         *    panels with real ids behave exactly as before whenever a genuine match exists.
         *  - else best year signal (info year, then name year): exact tiers get ±1, inexact
         *    tiers (trunc/skeleton/nodigit/off-year) demand an exact year — which is also
         *    why the provisional demands a year distance of exactly 0, never ±1
         *  - no signal at all: only exact-tier primary/original matches pass (alt titles
         *    can't ride the provisional either)
         */
        fun verifyDecision(
            signal: VerifySignal,
            targetTmdb: Int,
            targetYear: Int?,
            nameYear: Int?,
            exactTier: Boolean,
            via: String,
        ): VerifyVerdict {
            val exactPrimary = exactTier && (via.startsWith("primary") || via.startsWith("original"))
            val year = signal.year ?: nameYear
            signal.tmdb?.let {
                if (it == targetTmdb) return VerifyVerdict.CONFIRMED
                return if (exactPrimary && year != null && targetYear != null && yearDistance(year, targetYear) == 0) {
                    VerifyVerdict.PROVISIONAL
                } else {
                    VerifyVerdict.REJECTED
                }
            }
            if (year != null && targetYear != null) {
                val d = yearDistance(year, targetYear)
                return if (if (exactTier) d <= 1 else d == 0) VerifyVerdict.CONFIRMED else VerifyVerdict.REJECTED
            }
            return if (exactPrimary) VerifyVerdict.CONFIRMED else VerifyVerdict.REJECTED
        }

        private fun yearDistance(a: Int?, b: Int?): Int = if (a == null || b == null) 0 else if (a > b) a - b else b - a

        /** Verify-order ranking: year-exact candidates first, unknown-year candidates last. */
        private fun rankDistance(a: Int?, b: Int?): Int = if (a == null || b == null) 999 else yearDistance(a, b)
    }
}
