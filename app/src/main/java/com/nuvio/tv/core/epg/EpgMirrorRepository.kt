package com.nuvio.tv.core.epg

import android.util.Log
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.content.EpgProgramme
import com.nuvio.tv.core.iptv.epg.XmltvParser
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.data.local.XtreamAccountStore
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client of the backend's EPG mirror (`epg` storage bucket, filled by the epg-sync edge
 * function): keeps a local canonical EPG the apps fall back to when the panel's own EPG is
 * missing (Starshare fills 6% of epg_channel_id…), and the channel mappings that power the
 * Sports Centre's EPG-first event matching.
 *
 * Sync flow (12h TTL, single-flight, everything crash-safe via meta-last):
 *   1. manifest.json — tiny; unchanged generatedAt short-circuits the rest.
 *   2. channels-index.json.gz — every mirrored source's channel ids+names → SQLite.
 *   3. Map every enabled playlist's live channels onto EPG ids ([EpgChannelIndex], transient).
 *   4. Download the programme feeds that actually cover the user's channels (best sources
 *      first, capped) and store a bounded window of programmes for mapped channels only.
 */
@Singleton
class EpgMirrorRepository @Inject constructor(
    private val db: EpgMirrorDb,
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val accountStore: XtreamAccountStore,
    private val clientFactory: IptvClientFactory,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(com.nuvio.tv.core.diagnostics.HttpTraceInterceptor("EPG"))
        .build()
    private val syncMutex = Mutex()
    /** Survives the settings screen: a region change rebuilds even after the picker closes. */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    // Feed parsing burns ~100% of a core for a minute+ on budget boxes (multi-MB
    // XMLTV pulls). Dispatchers.IO workers run at default priority and starve the
    // UI on 4-core devices — run the whole sync on one THREAD_PRIORITY_BACKGROUND
    // thread instead so playback/UI always win the cores.
    private val syncDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "epg-mirror-sync")
    }.asCoroutineDispatcher()

    // --- public queries ---------------------------------------------------------

    /** Mirror now/next for a provider channel, or empty when unmapped/uncovered. */
    suspend fun nowNext(providerKey: String, streamId: Int, nowMs: Long): List<EpgProgramme> {
        val epgId = db.mappingFor(providerKey)[streamId] ?: return emptyList()
        return db.nowNext(epgId, nowMs)
    }

    /** streamId → epgId for one playlist (empty until a sync has mapped it). */
    suspend fun mappingFor(providerKey: String): Map<Int, String> = db.mappingFor(providerKey)

    /** Mirror programmes overlapping [fromMs, toMs) for a provider channel (guide timeline rows). */
    suspend fun programmesWindow(providerKey: String, streamId: Int, fromMs: Long, toMs: Long): List<EpgProgramme> {
        val epgId = db.mappingFor(providerKey)[streamId] ?: return emptyList()
        return db.programmesWindow(epgId, fromMs, toMs)
    }

    /** Candidate programmes for an event window; callers score them (see RadarChannelMatcher). */
    suspend fun programmesInWindow(tokens: List<String>, fromMs: Long, toMs: Long): List<EpgProgramme> =
        db.searchProgrammes(tokens, fromMs, toMs)

    /** The mirror's display name for an EPG channel (for "via BBC One" labels). */
    suspend fun channelNameFor(epgId: String): String? = db.indexNameFor(epgId)

    /** Drop a removed playlist's mappings and schedule state (account-removal purge). */
    suspend fun purgeProvider(providerKey: String) {
        db.purgeProvider(providerKey)
        db.deleteMeta(mappedGenKey(providerKey))
        db.deleteMeta(attemptAtKey(providerKey))
    }

    // --- region selection (the picker) ---------------------------------------------

    /**
     * Regions the viewer chose, or empty for "no preference" (everything — the opt-in default).
     * Stored in the mirror's own meta table: it is EPG cache state, it belongs with the data it
     * filters, and it needs no new settings plumbing.
     */
    suspend fun selectedRegions(): Set<String> =
        db.meta(META_REGIONS).orEmpty().split(REGION_SEPARATOR)
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Every region the mirror publishes, for the picker (works offline after one sync). */
    suspend fun availableRegions(): List<EpgRegion> = EpgRegionCatalog.catalogFrom(db.sources())

    /**
     * Applies a new selection and rebuilds against it.
     *
     * The index is stored pre-filtered, so a changed selection invalidates it: clear the sync
     * stamps so the next [ensureFresh] re-downloads, and clear every account's mapped-generation
     * so mappings are re-derived against the new index.
     */
    suspend fun setSelectedRegions(regions: Set<String>) {
        val normalized = regions.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalized == selectedRegions()) return
        db.setMeta(META_REGIONS, normalized.joinToString(REGION_SEPARATOR))
        db.setMeta(META_SYNCED_AT, "0")
        db.setMeta(META_GENERATION, "")
        for (key in db.metaKeysWithPrefix(MAPPED_GEN_PREFIX)) db.deleteMeta(key)
        Log.i(TAG, "epg regions set to ${normalized.ifEmpty { setOf("<all>") }}; index will rebuild")
        // Rebuild on the repository's own scope, NOT the caller's: ensureFresh runs the whole
        // download+match episode, and awaiting it here would freeze the settings screen for
        // minutes (and be cancelled outright when the viewer navigates away).
        scope.launch { ensureFresh(force = true) }
    }

    /**
     * Emits once a sync has COMMITTED programmes.
     *
     * A screen holding "this channel had nothing" verdicts (the guide's per-channel cooldown)
     * should retire them when this fires — the data it concluded was absent has just landed.
     * Replay-less on purpose: a screen opened afterwards reads the committed rows directly.
     */
    private val _programmesCommitted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val programmesCommitted: SharedFlow<Unit> = _programmesCommitted.asSharedFlow()

    /**
     * Start a sync that OUTLIVES the caller.
     *
     * Every screen that wants a warm mirror must use this rather than launching [ensureFresh] in
     * its own `viewModelScope`, because the sync is minutes long and the viewer will not stay.
     * Measured on an Onn 4K (2026-08-18, telemetry): the guide's `viewModelScope.launch` reached
     * the match phase (`mapped 1616/11429`, ~11.6 s) and was then killed —
     * `epg_ingest{outcome=error, JobCancellationException, programmes=0}`, twice — so the mirror
     * downloaded NOTHING. The S24 ran the identical code against the identical panels in the same
     * hour and committed 61,320 programmes, because mobile launches it off a repository-scoped
     * job. Worse than losing one run: `META_GENERATION` is written at the very END, so a cancelled
     * sync never records completion and the next visit repeats the whole expensive episode.
     *
     * Cheap to call repeatedly — [ensureFresh] is single-flighted by [syncMutex] and TTL-gated.
     */
    fun warm() {
        scope.launch { ensureFresh() }
    }

    // --- sync ---------------------------------------------------------------------

    /**
     * Refresh the mirror if stale (12h) and map any newly-added playlists. Cheap when fresh.
     * Never throws; a failed sync leaves the previous data serving. Call fire-and-forget from
     * the surfaces that consume the mirror (Sports tab, live guide).
     */
    suspend fun ensureFresh(force: Boolean = false): Unit = withContext(syncDispatcher) {
        if (!syncMutex.tryLock()) return@withContext
        try {
            val now = System.currentTimeMillis()
            val lastSync = db.meta(META_SYNCED_AT)?.toLongOrNull() ?: 0L
            val fresh = !force && now - lastSync < SYNC_TTL_MS
            // `sourcesAreEmpty` forces the full path once after upgrading to region support:
            // the published source list is only written when the index is (re)built.
            if (fresh && !db.indexIsEmpty() && !db.sourcesAreEmpty()) {
                // Whatever the stored index was built from is what any mapping must agree with.
                mapAccountsIfNeeded(now, force = false, generation = db.meta(META_GENERATION).orEmpty())
                return@withContext
            }

            val base = storageBase() ?: return@withContext
            val manifest = fetchManifest(base) ?: return@withContext
            val generation = manifest.generatedAt.orEmpty()
            if (!force && generation.isNotEmpty() && generation == db.meta(META_GENERATION) &&
                !db.indexIsEmpty() && !db.sourcesAreEmpty()
            ) {
                // Mirror unchanged upstream — just stamp freshness and cover new accounts.
                db.setMeta(META_SYNCED_AT, now.toString())
                mapAccountsIfNeeded(now, force = false, generation = generation)
                return@withContext
            }

            // Stream the channels-index: decode ONE source at a time (the whole ChannelsIndexDoc
            // tree and the whole decompressed body are never materialized), store the selected
            // regions' rows into a bounded shadow, and swap it in only after a fully successful
            // parse — so an oversized/malformed/truncated response leaves the previous generation
            // intact. Twin of the KMP EpgMirrorRepository.ingestChannelsIndexStream.
            val committed = ingestChannelsIndexStream(db, json, selectedRegions()) { onChunk ->
                streamIndexChars(base, manifest.channelsIndexPath ?: "channels-index.json.gz", onChunk)
            }
            if (!committed) return@withContext

            // The index just changed, so this is the one moment a re-match can produce a new
            // answer — but the policy still admits at most ONE account per sync, so a bump that
            // affects every account spreads over visits instead of stacking a 49k-channel
            // foreground episode (research/tv-epg-mirror-spin.md).
            mapAccountsIfNeeded(now, force, generation)

            // Download programme feeds for the sources that cover the user's channels.
            val mappedIds = db.mappedEpgIds()
            if (mappedIds.isNotEmpty()) {
                val bySlugCover = HashMap<String, Int>()
                val idsBySlug = HashMap<String, MutableSet<String>>()
                db.forEachIndexRow { r ->
                    if (r.epgId in mappedIds) {
                        idsBySlug.getOrPut(r.slug) { mutableSetOf() }.add(r.epgId)
                    }
                }
                idsBySlug.forEach { (slug, ids) -> bySlugCover[slug] = ids.size }
                val chosen = bySlugCover.entries
                    .sortedByDescending { it.value }
                    .filter { it.value >= MIN_SLUG_COVER }
                    .take(MAX_FEEDS)
                    .map { it.key }
                if (chosen.isNotEmpty()) {
                    val windowStart = now - WINDOW_BACK_MS
                    val windowEnd = now + WINDOW_AHEAD_MS
                    val covered = mutableSetOf<String>()
                    val stored = db.replaceProgrammes { writer ->
                        for (slug in chosen) {
                            val want = idsBySlug[slug].orEmpty().minus(covered)
                            if (want.isEmpty()) continue
                            // Feeds download from their ORIGIN (GitHub CDN etc.) — the
                            // backend publishes pointers only, no bytes transit Supabase.
                            val feedUrl = manifest.urlFor(slug) ?: continue
                            val seen = mutableSetOf<String>()
                            streamFeed(feedUrl, want) { p ->
                                if (p.endMs > windowStart && p.startMs < windowEnd) {
                                    writer.add(p)
                                    seen.add(p.channelId)
                                }
                            }
                            covered += seen
                        }
                    }
                    Log.i(TAG, "mirror sync: $stored programmes for ${covered.size} channels from $chosen")
                    EpgTelemetry.ingestFinished(
                        source = EpgTelemetry.Source.MIRROR,
                        outcome = if (stored > 0) EpgTelemetry.Outcome.OK else EpgTelemetry.Outcome.EMPTY,
                        programmes = stored,
                        channels = mappedIds.size,
                        channelsCovered = covered.size,
                        durationMs = System.currentTimeMillis() - now,
                    )
                    // Tell any live screen its "no EPG" verdicts are stale now.
                    _programmesCommitted.tryEmit(Unit)
                }
            }

            db.setMeta(META_GENERATION, generation)
            db.setMeta(META_SYNCED_AT, now.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "mirror sync failed", t)
            EpgTelemetry.ingestFinished(
                source = EpgTelemetry.Source.MIRROR,
                outcome = EpgTelemetry.Outcome.ERROR,
                errorClass = t::class.simpleName,
            )
        } finally {
            syncMutex.unlock()
        }
    }

    /** Meta keys for one account's mapping schedule (cleared by [purgeProvider]). */
    private fun mappedGenKey(accountId: String) = "$MAPPED_GEN_PREFIX$accountId"
    private fun attemptAtKey(accountId: String) = "acct_attempt_ms:$accountId"

    /**
     * Re-match the accounts [EpgRemapPolicy] selects — never-mapped ones (cooldown-gated),
     * at most one aged one, or all under `force`. The expensive parts — each account's full
     * channel-list fetch and the tens-of-MB transient [EpgChannelIndex] — only happen when
     * at least one account is due, which in steady state is one account a week, not every
     * account on every generation bump (that was the Onn "background spin",
     * research/tv-epg-mirror-spin.md: 115.7s CPU per Sports-tab visit on a 2GB box).
     *
     * "Mapped" is meta-stamped on a COMPLETED match run, even one with zero hits — keying it
     * on row presence made all-24/7 accounts re-run the episode on every surface visit.
     */
    private suspend fun mapAccountsIfNeeded(nowMs: Long, force: Boolean, generation: String) {
        val accounts = accountStore.accounts.first().filter { it.enabled }
        if (accounts.isEmpty()) return
        var agedBudgetLeft = true
        val due = accounts.filter { acc ->
            val mappedGen = db.meta(mappedGenKey(acc.id)).orEmpty()
            val attemptedAt = db.meta(attemptAtKey(acc.id))?.toLongOrNull() ?: 0L
            val decision = EpgRemapPolicy.decide(nowMs, force, mappedGen, generation, attemptedAt, agedBudgetLeft)
            if (decision == EpgRemapPolicy.Decision.REMATCH && mappedGen.isNotEmpty() && !force) {
                agedBudgetLeft = false
            }
            decision == EpgRemapPolicy.Decision.REMATCH
        }
        if (due.isEmpty()) return

        val pairs = ArrayList<Pair<String, List<String>>>(64_000)
        var lastId = ""
        var names = ArrayList<String>()
        db.forEachIndexRow { r ->
            if (r.epgId != lastId) {
                if (lastId.isNotEmpty()) pairs.add(lastId to names)
                lastId = r.epgId
                names = ArrayList(3)
            }
            names.add(r.name)
        }
        if (lastId.isNotEmpty()) pairs.add(lastId to names)
        if (pairs.isEmpty()) return
        val index = EpgChannelIndex.build(pairs)

        for (acc in due) {
            val matchStartedMs = System.currentTimeMillis()
            db.setMeta(attemptAtKey(acc.id), nowMs.toString())
            val channels = runCatching { clientFactory.clientFor(acc).liveChannels(acc) }
                .getOrNull()?.getOrNull()
            // Failed or empty fetch: attempt stamped, mappedAt not — the cooldown owns the retry.
            if (channels.isNullOrEmpty()) continue
            val mappings = channels.mapNotNull { ch ->
                index.match(ch.name, ch.epgChannelId)?.let { hit ->
                    EpgMappingRow(ch.streamId, hit.epgId, hit.tier)
                }
            }
            db.replaceMapping(acc.id, mappings)
            // Stamped even at zero hits: the run COMPLETED against this index.
            db.setMeta(mappedGenKey(acc.id), generation.ifEmpty { NO_GENERATION })
            Log.i(TAG, "mapped ${mappings.size}/${channels.size} channels for ${acc.name}")
            // The match rate is what "my EPG stopped working" almost always turns out to be — it
            // collapses when a provider renumbers its catalog, and we have never been able to see
            // it. No account name or host here: counts only.
            EpgTelemetry.mappingFinished(
                matched = mappings.size,
                channels = channels.size,
                durationMs = System.currentTimeMillis() - matchStartedMs,
            )
        }
    }

    // --- transport ------------------------------------------------------------------

    private fun storageBase(): String? {
        val url = runCatching { supabaseProvider.selectedBackend.normalizedSupabaseUrl }.getOrNull()
            ?.trim()?.trimEnd('/')
        if (url.isNullOrBlank()) return null
        return "$url/storage/v1/object/public/epg"
    }

    private fun fetchManifest(base: String): MirrorManifest? = runCatching {
        http.newCall(Request.Builder().url("$base/manifest.json").get().build()).execute().use { resp ->
            check(resp.isSuccessful) { "manifest HTTP ${resp.code}" }
            // Bound the manifest read too (small JSON): a corrupt/runaway manifest is rejected
            // without OOM, matching the KMP manifest cap.
            val text = readBoundedChars(checkNotNull(resp.body) { "empty manifest body" }.charStream(), MAX_FETCH_CHARS)
                ?: run { Log.w(TAG, "manifest exceeded $MAX_FETCH_CHARS chars; rejected"); return@use null }
            json.decodeFromString<MirrorManifest>(text)
        }
    }.onFailure { Log.d(TAG, "manifest fetch failed: $it") }.getOrNull()

    /**
     * Stream the (gunzipped) channels-index to [onChunk] as decoded char chunks, NEVER materializing
     * the whole decompressed body. The response is closed on any throw (`.use`) — so when the element
     * parser rejects an oversized/malformed record and throws through [onChunk], the upstream read
     * stops and the socket is released rather than draining the rest of the body. The GZIP wrap caps
     * the INFLATED size the parser sees, defeating a gz bomb.
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun streamIndexChars(base: String, path: String, onChunk: (String) -> Unit) {
        // Cancellation must stop the actual blocking read (a synchronous reader ignores coroutine
        // cancellation): wire it to Call.cancel() on the cancelling transition, then ensureActive()
        // maps the resulting read failure to CancellationException. Twin of the KMP httpStreamLines.
        val call = http.newCall(Request.Builder().url("$base/$path").get().build())
        val cancelHook = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
            ?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { resp ->
                check(resp.isSuccessful) { "index HTTP ${resp.code}" }
                val body = checkNotNull(resp.body) { "empty index body" }
                GZIPInputStream(body.byteStream()).bufferedReader().use { reader ->
                    val buf = CharArray(64 * 1024)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) onChunk(String(buf, 0, n))
                    }
                }
            }
        } catch (t: Throwable) {
            kotlin.coroutines.coroutineContext.ensureActive() // cancel-induced failure → CancellationException
            throw t
        } finally {
            cancelHook?.dispose()
        }
    }

    /** Stream-parse one feed from its origin URL, emitting programmes for [wantIds]. */
    private fun streamFeed(url: String, wantIds: Set<String>, onProgramme: (EpgProgramme) -> Unit) {
        runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                check(resp.isSuccessful) { "feed HTTP ${resp.code}" }
                val body = checkNotNull(resp.body) { "empty feed body" }
                val reader = GZIPInputStream(body.byteStream()).bufferedReader()
                val parser = android.util.Xml.newPullParser().apply {
                    setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    setInput(reader)
                }
                XmltvParser.parseProgrammes(parser, wantIds, onProgramme)
            }
        }.onFailure { Log.w(TAG, "feed $url failed: $it") }
    }

    // --- wire models ------------------------------------------------------------------

    @Serializable
    private data class MirrorManifest(
        val generatedAt: String? = null,
        val files: List<MirrorFile> = emptyList(),
        val channelsIndexPath: String? = null,
    ) {
        fun urlFor(slug: String): String? = files.firstOrNull { it.slug == slug && it.error == null }?.url
    }

    @Serializable
    private data class MirrorFile(
        val slug: String,
        val url: String? = null,
        val error: String? = null,
    )

    private companion object {
        const val TAG = "EpgMirror"
        const val META_SYNCED_AT = "synced_at"
        const val META_REGIONS = "selected_regions"
        /** Region names cannot contain it, unlike the comma the backend uses inside `countries`. */
        const val REGION_SEPARATOR = "\u0001"
        const val MAPPED_GEN_PREFIX = "acct_mapped_gen:"
        const val META_GENERATION = "generation"
        const val SYNC_TTL_MS = 12 * 60 * 60 * 1000L
        /** Stamped when the mirror publishes no generation, so "matched once" is still recorded. */
        const val NO_GENERATION = "-"
        /** Only download a feed when it covers a meaningful slice of the user's channels. */
        const val MIN_SLUG_COVER = 25
        const val MAX_FEEDS = 4
        /** Programme window kept locally: enough for "started earlier" + two days of guide. */
        const val WINDOW_BACK_MS = 6 * 60 * 60 * 1000L
        const val WINDOW_AHEAD_MS = 48 * 60 * 60 * 1000L
    }
}

/** Manifest fetch cap (small JSON) — a corrupt/runaway manifest is rejected without OOM. The
 *  channels-index no longer accumulates: it is streamed element-wise (below). Matches the KMP cap. */
private const val MAX_FETCH_CHARS = 4_000_000

// --- streaming channels-index bounds — twin of the KMP constants ---
// Working-set bounds (retained memory, independent of catalog size):
/** One source's metadata string (slug/label/countries) cap — larger ⇒ reject the update. */
private const val MAX_SCALAR_CHARS = 8_192
/** One channel object's JSON cap — larger ⇒ reject. A channel is `{"id":..,"names":[..]}`; tiny. */
private const val MAX_CHANNEL_CHARS = 64 * 1024
/** Max nesting depth inside a channel object or a skipped field — larger ⇒ reject. */
private const val MAX_DEPTH = 32
/** Rows buffered before a flush+clear: the parser callback can't suspend, so a full batch is the peak
 *  retained selected-row memory. Kept modest so working memory stays flat. */
private const val INDEX_BATCH = 4_000
/** Max source records before the update is rejected (the mirror publishes ~tens of regions). */
private const val MAX_SOURCES = 4_096
// Total-accepted bound (NOT a working-set bound — rows are streamed to disk in INDEX_BATCH flushes):
/** Max selected index rows accepted in one refresh. The full multi-region index is ~49k channels; a
 *  household stores ~13%. Well above the real ceiling so a valid catalog is never rejected. */
private const val MAX_ROWS = 400_000

@Serializable
internal data class IndexChannelDoc(
    val id: String,
    val names: List<String> = emptyList(),
)

/**
 * Bounded-memory channels-index ingestion (hand-port of the KMP EpgMirrorRepository.ingestChannelsIndexStream).
 * [feed] streams the (gunzipped) response as chunks; a [ChannelsIndexStreamParser] emits each source's
 * scalar metadata and then its channel objects one at a time — so neither the whole document, nor a
 * whole source's channel list, nor the whole selected catalog is ever materialized. Selected channels
 * accumulate into ONE reusable batch that is flushed to a shadow table and cleared as soon as it reaches
 * [INDEX_BATCH]; the shadow is swapped in atomically only after the entire response has parsed and
 * validated. Any oversized record/field, over-deep nesting, too many sources, exceeding the total-rows
 * cap, or a truncated body throws → no commit → the previous valid generation is retained.
 *
 * Retained memory is O([INDEX_BATCH] rows + [MAX_SOURCES] source metadata + one channel object),
 * independent of channel count; [MAX_ROWS] bounds the *total accepted* catalog, a separate concern.
 *
 * A top-level function (depends only on [db] + [json]) so a Robolectric test can drive it through real
 * framework SQLite without the Hilt graph. Returns true iff a new (non-empty) generation was committed.
 */
/** Serializes channels-index ingests so two never interleave shadow begin/insert/commit. `ensureFresh`
 *  already single-flights via its own mutex; this additionally guards a direct call. It does NOT gate
 *  guide reads (they run on Dispatchers.IO against WAL SQLite), so serializing ingests never blocks the
 *  guide. Twin of the KMP EpgMirrorRepository.indexIngestMutex. */
private val indexIngestMutex = kotlinx.coroutines.sync.Mutex()

/**
 * Bounded-memory channels-index ingestion in two phases (twin of the KMP implementation).
 *
 * PHASE 1 (no EPG DB write): [feed] streams the response; EVERY source's channels are appended to
 * bounded off-DB [EpgIngestStaging], tagged by a stable per-source ordinal. Keep is NOT decided here —
 * a late/duplicate slug/label/countries could still change it — so field order is fully independent
 * and no channel is selected on a stale value. The document, a whole source's channel list, and the
 * whole catalog stay out of memory.
 *
 * PHASE 2 (WAL DB writes, no network): once metadata is final, [EpgRegionCatalog.slugsFor] decides the
 * kept sources; staged rows for kept ordinals are promoted into the shadow in [INDEX_BATCH] batches
 * and swapped atomically. The previous generation serves until the swap; any failure/cancellation
 * drops the shadow and keeps the prior generation. On TV the network read never blocks guide reads:
 * phase 1 touches no DB, and guide reads run on Dispatchers.IO against WAL — NOT on the single-thread
 * EPG dispatcher this ingest occupies.
 */
internal suspend fun ingestChannelsIndexStream(
    db: EpgMirrorDb,
    json: Json,
    selection: Set<String>,
    feed: suspend (onChunk: (String) -> Unit) -> Unit,
): Boolean = indexIngestMutex.withLock {
    val published = ArrayList<EpgSourceInfo>() // index == source ordinal; each source's FINAL metadata
    val staging = EpgIngestStaging()
    try {
        // --- PHASE 1: network → staging, no EPG DB lock ---
        var ordinal = -1
        var slug = ""; var label: String? = null; var countries: String? = null; var channelCount = 0
        var stagedRows = 0
        val handler = object : ChannelsIndexStreamParser.Handler {
            override fun onSourceBegin() {
                ordinal++
                if (ordinal >= MAX_SOURCES) throw EpgElementTooLargeException(ordinal)
                slug = ""; label = null; countries = null; channelCount = 0
            }

            // Last value wins for a repeated key — the final scalars are what onSourceEnd records.
            override fun onSourceScalar(key: String, value: String?) {
                when (key) {
                    "slug" -> slug = value.orEmpty()
                    "label" -> label = value
                    "countries" -> countries = value
                }
            }

            override fun onChannel(channelJson: String) {
                channelCount++
                val ch = json.decodeFromString<IndexChannelDoc>(channelJson)
                if (ch.id.isBlank()) return
                val names = if (ch.names.isEmpty()) listOf(ch.id) else ch.names
                for (n in names) {
                    if (n.isBlank()) continue
                    if (stagedRows >= MAX_ROWS) throw EpgElementTooLargeException(stagedRows)
                    staging.append(encodeStagedRow(EpgStagedRow(ordinal, ch.id, n)))
                    stagedRows++
                }
            }

            override fun onSourceEnd() {
                published.add(EpgSourceInfo(slug, label ?: slug, countries, channelCount))
            }
        }
        val parser = ChannelsIndexStreamParser(MAX_SCALAR_CHARS, MAX_CHANNEL_CHARS, MAX_DEPTH, handler)
        val parsed = runCatching {
            feed { chunk -> parser.accept(chunk) }
            parser.finish() // throws on an absent/truncated array — never publish a partial replacement
            true
        }.getOrElse { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t // propagate; finally disposes staging
            Log.w("EpgMirror", "channels-index stream rejected; kept prior generation", t)
            false
        }
        if (!parsed) return@withLock false // parse failed/truncated → prior generation intact

        // --- keep decision from FINAL metadata (field-order independent; duplicate key = last wins) ---
        val keptSlugs = EpgRegionCatalog.slugsFor(selection, published)
        val slugByKeptOrdinal = HashMap<Int, String>()
        published.forEachIndexed { ord, info -> if (info.slug in keptSlugs) slugByKeptOrdinal[ord] = info.slug }

        // --- PHASE 2: staging → shadow, no network ---
        staging.finishWriting()
        val ingest = db.indexIngest()
        var committed = false
        try {
            ingest.begin()
            val batch = ArrayList<EpgIndexRow>(INDEX_BATCH)
            var promoted = 0
            staging.forEachLine { line ->
                val row = decodeStagedRow(line)
                val rowSlug = slugByKeptOrdinal[row.o] ?: return@forEachLine // skipped region — never promoted
                batch.add(EpgIndexRow(rowSlug, row.i, row.n))
                promoted++
                if (batch.size >= INDEX_BATCH) { ingest.insert(batch); batch.clear() }
            }
            if (batch.isNotEmpty()) ingest.insert(batch)
            if (promoted > 0) { ingest.commit(); committed = true }
        } finally {
            ingest.dropIfUncommitted()
        }
        db.replaceSources(published) // full catalog parsed → publish for the region picker
        return@withLock committed
    } finally {
        staging.dispose()
    }
}

/** Reads up to [maxChars] from [reader], returning null if exceeded — enforced while consuming, so a
 *  decompression-bomb / oversized response never fully lands in memory. Testable with a StringReader. */
internal fun readBoundedChars(reader: java.io.Reader, maxChars: Int): String? {
    val sb = StringBuilder()
    val buf = CharArray(8192)
    while (true) {
        val n = reader.read(buf)
        if (n < 0) break
        if (sb.length.toLong() + n > maxChars) return null
        sb.append(buf, 0, n)
    }
    return sb.toString()
}
