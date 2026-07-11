package com.nuvio.tv.core.epg

import android.util.Log
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.content.EpgProgramme
import com.nuvio.tv.core.iptv.epg.XmltvParser
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.data.local.XtreamAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

    // --- public queries ---------------------------------------------------------

    /** Mirror now/next for a provider channel, or empty when unmapped/uncovered. */
    suspend fun nowNext(providerKey: String, streamId: Int, nowMs: Long): List<EpgProgramme> {
        val epgId = db.mappingFor(providerKey)[streamId] ?: return emptyList()
        return db.nowNext(epgId, nowMs)
    }

    /** streamId → epgId for one playlist (empty until a sync has mapped it). */
    suspend fun mappingFor(providerKey: String): Map<Int, String> = db.mappingFor(providerKey)

    /** Candidate programmes for an event window; callers score them (see RadarChannelMatcher). */
    suspend fun programmesInWindow(tokens: List<String>, fromMs: Long, toMs: Long): List<EpgProgramme> =
        db.searchProgrammes(tokens, fromMs, toMs)

    /** The mirror's display name for an EPG channel (for "via BBC One" labels). */
    suspend fun channelNameFor(epgId: String): String? = db.indexNameFor(epgId)

    /** Drop a removed playlist's mappings (called from account-removal purge). */
    suspend fun purgeProvider(providerKey: String) = db.purgeProvider(providerKey)

    // --- sync ---------------------------------------------------------------------

    /**
     * Refresh the mirror if stale (12h) and map any newly-added playlists. Cheap when fresh.
     * Never throws; a failed sync leaves the previous data serving. Call fire-and-forget from
     * the surfaces that consume the mirror (Sports tab, live guide).
     */
    suspend fun ensureFresh(force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) return@withContext
        try {
            val now = System.currentTimeMillis()
            val lastSync = db.meta(META_SYNCED_AT)?.toLongOrNull() ?: 0L
            val fresh = !force && now - lastSync < SYNC_TTL_MS
            if (fresh && !db.indexIsEmpty()) {
                mapMissingAccounts()
                return@withContext
            }

            val base = storageBase() ?: return@withContext
            val manifest = fetchManifest(base) ?: return@withContext
            val generation = manifest.generatedAt.orEmpty()
            if (!force && generation.isNotEmpty() && generation == db.meta(META_GENERATION) && !db.indexIsEmpty()) {
                // Mirror unchanged upstream — just stamp freshness and cover new accounts.
                db.setMeta(META_SYNCED_AT, now.toString())
                mapMissingAccounts()
                return@withContext
            }

            val index = fetchChannelsIndex(base, manifest.channelsIndexPath ?: "channels-index.json.gz")
                ?: return@withContext
            val rows = ArrayList<EpgIndexRow>(64_000)
            for (src in index.sources) {
                for (ch in src.channels) {
                    if (ch.id.isBlank()) continue
                    if (ch.names.isEmpty()) rows.add(EpgIndexRow(src.slug, ch.id, ch.id))
                    else ch.names.forEach { n -> if (n.isNotBlank()) rows.add(EpgIndexRow(src.slug, ch.id, n)) }
                }
            }
            if (rows.isEmpty()) return@withContext
            db.replaceIndex(rows)

            // Transient matcher index + slug coverage, then persist per-account mappings.
            mapAccounts(allAccounts = true)

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

    /** Map playlists that have no mapping rows yet (added since the last full sync). */
    private suspend fun mapMissingAccounts() {
        val mapped = db.mappedProviderKeys()
        val missing = accountStore.accounts.first().filter { it.enabled && it.id !in mapped }
        if (missing.isEmpty()) return
        mapAccounts(allAccounts = false, only = missing.map { it.id }.toSet())
    }

    /**
     * Build the transient matcher index from the stored channels-index and persist mappings
     * for the enabled playlists ([only] restricts to specific ids). The index is dropped on
     * return — mappings live in SQLite.
     */
    private suspend fun mapAccounts(allAccounts: Boolean, only: Set<String> = emptySet()) {
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

        val accounts = accountStore.accounts.first()
            .filter { it.enabled && (allAccounts || it.id in only) }
        for (acc in accounts) {
            val channels = runCatching { clientFactory.clientFor(acc).liveChannels(acc) }
                .getOrNull()?.getOrNull() ?: continue
            if (channels.isEmpty()) continue
            val mappings = channels.mapNotNull { ch ->
                index.match(ch.name, ch.epgChannelId)?.let { hit ->
                    EpgMappingRow(ch.streamId, hit.epgId, hit.tier)
                }
            }
            db.replaceMapping(acc.id, mappings)
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
        const val META_GENERATION = "generation"
        const val SYNC_TTL_MS = 12 * 60 * 60 * 1000L
        /** Only download a feed when it covers a meaningful slice of the user's channels. */
        const val MIN_SLUG_COVER = 25
        const val MAX_FEEDS = 4
        /** Programme window kept locally: enough for "started earlier" + two days of guide. */
        const val WINDOW_BACK_MS = 6 * 60 * 60 * 1000L
        const val WINDOW_AHEAD_MS = 48 * 60 * 60 * 1000L
    }
}
