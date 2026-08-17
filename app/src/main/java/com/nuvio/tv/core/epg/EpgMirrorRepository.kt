package com.nuvio.tv.core.epg

import android.util.Log
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.content.EpgProgramme
import com.nuvio.tv.core.iptv.epg.XmltvParser
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.data.local.XtreamAccountStore
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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

            val index = fetchChannelsIndex(base, manifest.channelsIndexPath ?: "channels-index.json.gz")
                ?: return@withContext
            // Remember what the mirror offers before filtering, so the picker can list every
            // region (including ones the viewer switched off) without a re-fetch.
            val published = index.sources.map {
                EpgSourceInfo(it.slug, it.label ?: it.slug, it.countries, it.channels.size)
            }
            db.replaceSources(published)

            // Only selected regions are STORED. Filtering here rather than at query time is the
            // point of the picker: the index is what costs disk on every device and a match walk
            // per channel, and a household typically uses ~13% of it.
            val keepSlugs = EpgRegionCatalog.slugsFor(selectedRegions(), published)
            val rows = ArrayList<EpgIndexRow>(64_000)
            for (src in index.sources) {
                if (src.slug !in keepSlugs) continue
                for (ch in src.channels) {
                    if (ch.id.isBlank()) continue
                    if (ch.names.isEmpty()) rows.add(EpgIndexRow(src.slug, ch.id, ch.id))
                    else ch.names.forEach { n -> if (n.isNotBlank()) rows.add(EpgIndexRow(src.slug, ch.id, n)) }
                }
            }
            if (rows.isEmpty()) return@withContext
            db.replaceIndex(rows)

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
                }
            }

            db.setMeta(META_GENERATION, generation)
            db.setMeta(META_SYNCED_AT, now.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "mirror sync failed", t)
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
            json.decodeFromString<MirrorManifest>(resp.body?.string().orEmpty())
        }
    }.onFailure { Log.d(TAG, "manifest fetch failed: $it") }.getOrNull()

    private fun fetchChannelsIndex(base: String, path: String): ChannelsIndexDoc? = runCatching {
        http.newCall(Request.Builder().url("$base/$path").get().build()).execute().use { resp ->
            check(resp.isSuccessful) { "index HTTP ${resp.code}" }
            val body = checkNotNull(resp.body) { "empty index body" }
            GZIPInputStream(body.byteStream()).bufferedReader().use { reader ->
                json.decodeFromString<ChannelsIndexDoc>(reader.readText())
            }
        }
    }.onFailure { Log.w(TAG, "channels index fetch failed: $it") }.getOrNull()

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

    @Serializable
    private data class ChannelsIndexDoc(
        val generatedAt: String? = null,
        val sources: List<IndexSourceDoc> = emptyList(),
    )

    @Serializable
    private data class IndexSourceDoc(
        val slug: String,
        val label: String? = null,
        /** Comma-separated country names; drives the region picker. */
        val countries: String? = null,
        val channels: List<IndexChannelDoc> = emptyList(),
    )

    @Serializable
    private data class IndexChannelDoc(
        val id: String,
        val names: List<String> = emptyList(),
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
