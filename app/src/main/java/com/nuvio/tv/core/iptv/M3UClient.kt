package com.nuvio.tv.core.iptv

import android.util.Log
import com.nuvio.tv.core.iptv.content.ContentChannel
import com.nuvio.tv.core.iptv.content.ContentSeries
import com.nuvio.tv.core.iptv.content.ContentVod
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.iptv.content.M3UKind
import com.nuvio.tv.core.iptv.content.M3UParser
import com.nuvio.tv.core.iptv.epg.XmltvClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * An [IptvClient] backed by a parsed M3U/URL playlist. Unlike Xtream there is NO API — the parsed
 * playlist IS the catalog, so it MUST live in a DB ([IptvContentDb]) and CANNOT be held in RAM
 * (provider lists reach 192MB / 685k entries).
 *
 *  - [ensureIngested] fetches the playlist URL and STREAMS it line-by-line into the DB in chunks
 *    (never loading the whole body). Idempotent + single-flight per playlist; a fresh ingest runs
 *    when the playlist has never been ingested or is stale.
 *  - the query methods (liveChannels / vodMovies / series / seriesInfo) read rows back and map
 *    them to the SAME domain models Xtream emits, so downstream (hub/search/registry/player) is
 *    identical. Live stream URL is the M3U url directly.
 *
 * The playlist id used to key the DB is [XtreamAccount.id] (a stable `m3u:scheme://host/path` id
 * from [m3uAccountFromUrl]), which is exactly the accountId the registry embeds in content ids, so
 * ids line up end-to-end (registry -> meta/stream short-circuit -> deep-link rebuild).
 */
@Singleton
class M3UClient @Inject constructor(
    private val db: IptvContentDb,
    @Named("m3uIngest") private val http: OkHttpClient,
    private val fileStore: M3UFileStore,
    private val xmltv: XmltvClient,
    private val playlistDns: com.nuvio.tv.core.iptv.dns.PlaylistDns,
) : IptvClient {

    private val ingestLock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val lastFailedMs = mutableMapOf<String, Long>()

    // Ingest outlives the browse request that triggered it (a 192MB parse takes a while and the
    // user navigates); a cancelled request must not kill (and backoff-poison) the ingest.
    private val ingestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Ensures the playlist is ingested (fetch + parse + store) before a browse/search reads it.
     * Single-flight per playlist; failures back off. Never throws — a failed ingest degrades to
     * whatever (possibly empty) catalog exists. Force-triggered on add/edit; lazily triggered on
     * first hub/search access if the DB has no ingest yet.
     */
    suspend fun ensureIngested(acc: XtreamAccount, force: Boolean = false) {
        val id = acc.id
        val builtAt = db.builtAt(id)
        val stale = builtAt == null ||
            (acc.autoRefreshHours > 0 && System.currentTimeMillis() - builtAt > acc.autoRefreshHours * 3_600_000L)
        if (!force && !stale) return

        val (deferred, isOwner) = ingestLock.withLock {
            inFlight[id]?.let { return@withLock it to false }
            if (!force && System.currentTimeMillis() - (lastFailedMs[id] ?: 0) < BUILD_BACKOFF_MS) return
            val d = CompletableDeferred<Unit>()
            inFlight[id] = d
            d to true
        }

        if (isOwner) {
            ingestScope.launch {
                try {
                    ingest(acc)
                    ingestLock.withLock { lastFailedMs.remove(id) }
                } catch (t: Throwable) {
                    Log.w(TAG, "M3U ingest failed for ${acc.name}", t)
                    ingestLock.withLock { lastFailedMs[id] = System.currentTimeMillis() }
                } finally {
                    ingestLock.withLock { inFlight.remove(id) }
                    deferred.complete(Unit)
                }
            }
        }
        deferred.await()
    }

    /**
     * Stream the playlist body into [IptvContentDb]. The byte source depends on the account's
     * source type — a URL fetch ([XtreamAccount.SOURCE_URL]) or the local copy of a picked file
     * ([XtreamAccount.SOURCE_FILE]) — but both feed the SAME [parseInto]/[M3UParser.parseStream]
     * pipeline (reader walked ONCE, never fully materialized). After the catalog lands, refresh the
     * EPG (throttled) so M3U live channels get real now/next.
     */
    private suspend fun ingest(acc: XtreamAccount) = withContext(Dispatchers.IO) {
        if (acc.isM3UFile()) ingestFromFile(acc) else ingestFromUrl(acc)
        // Piggyback an EPG refresh on the catalog ingest. NOT forced — a fresh add has no EPG yet so
        // it fetches, but a frequent catalog auto-refresh (e.g. every 6h) won't blow past the ~2×/day
        // EPG cap (refreshIfStale throttles). A catalog re-ingest resets epg_built_at (finish writes
        // NULL) too, so an edited playlist does re-fetch its EPG. Self-scoped; never fails ingest.
        runCatching { xmltv.refreshIfStale(acc) }
            .onFailure { Log.w(TAG, "EPG refresh failed for ${acc.name}", it) }
    }

    /**
     * URL source: fetch + stream. OkHttp follows redirects + transparently gunzips a gzip body by
     * default; a per-account User-Agent (stored in acc.username) is applied when set.
     */
    private suspend fun ingestFromUrl(acc: XtreamAccount) {
        val request = Request.Builder()
            .url(acc.baseUrl)
            .apply { acc.username.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) } }
            .build()
        // Fetch through the playlist's DoH resolver when it opts into one (shares the ingest pool).
        playlistDns.clientFor(http, acc.dnsProvider).newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            // charStream() decodes the (possibly gunzipped) source incrementally — no full buffer.
            val reader = resp.body.charStream().buffered()
            val writer = db.ingest(acc.id) { w -> parseInto(reader, w) }
            Log.i(TAG, "ingested M3U (url) for ${acc.name}: live=${writer.liveCount} vod=${writer.vodCount} series=${writer.seriesCount}")
        }
    }

    /**
     * File source: stream the LOCAL copy at files/playlists/{id}.m3u through the same parser. The
     * source document may have vanished — that's fine, the whole point of copying it in. If the
     * local copy is ALSO missing (a file playlist synced from another device; contents aren't
     * synced), skip cleanly: leave any prior catalog intact and let the hub show the re-import
     * affordance (builtAt stays as-is). Gzip-aware (.gz exports) via magic-byte sniffing.
     */
    private suspend fun ingestFromFile(acc: XtreamAccount) {
        val file = fileStore.fileFor(acc.id)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "M3U file missing for ${acc.name} (${acc.fileName}) — needs re-import on this device")
            return
        }
        openMaybeGzip(file.inputStream()).use { stream ->
            val reader = stream.bufferedReader(Charsets.UTF_8)
            val writer = db.ingest(acc.id) { w -> parseInto(reader, w) }
            Log.i(TAG, "ingested M3U (file) for ${acc.name}: live=${writer.liveCount} vod=${writer.vodCount} series=${writer.seriesCount}")
        }
    }

    /** Wrap a raw file stream in a GZIPInputStream when it starts with the gzip magic (0x1f 0x8b),
     *  so a `.m3u.gz` export ingests transparently — mirroring OkHttp's transparent gunzip. */
    private fun openMaybeGzip(raw: InputStream): InputStream {
        val buffered = raw.buffered()
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    /** Route each parsed entry to the DB writer. The heavy streaming walk lives in
     *  [M3UParser.parseStream] (reader walked ONCE, never fully materialized). The #EXTM3U header's
     *  url-tvg is captured for XMLTV EPG resolution. */
    private fun parseInto(reader: BufferedReader, w: IptvContentDb.IngestWriter) {
        var sid = 1
        M3UParser.parseStream(reader, onHeaderTvgUrl = { w.setTvgUrl(it) }) { entry ->
            when (entry.kind) {
                M3UKind.LIVE -> w.addChannel(ContentChannel(sid++, entry.name, entry.logo, entry.tvgId, entry.group, entry.url))
                M3UKind.SERIES -> w.addEpisodeFrom(entry)
                M3UKind.VOD -> {
                    // Promote "Show S01E02" .mp4 rows (shipped by many providers under /movie/) into
                    // the series lane; genuine movies stay VOD.
                    val ep = M3UParser.seriesEpisodeOf(entry.name)
                    if (ep != null) w.addEpisode(entry.group, ep.first, ep.second, ep.third, entry.name, entry.logo, entry.url, entry.ext)
                    else w.addVod(ContentVod(sid++, entry.name, entry.logo, entry.group, entry.url, entry.ext))
                }
            }
        }
    }

    // --- IptvClient (reads from the ingested catalog) -----------------------

    override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> = runCatching {
        ensureIngested(acc)
        db.categoriesFor(acc.id, IptvContentDb.TYPE_LIVE).map { XtreamCategory(it.id, it.name) }
    }

    override suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>> = runCatching {
        ensureIngested(acc)
        db.categoriesFor(acc.id, IptvContentDb.TYPE_VOD).map { XtreamCategory(it.id, it.name) }
    }

    override suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>> = runCatching {
        ensureIngested(acc)
        db.categoriesFor(acc.id, IptvContentDb.TYPE_SERIES).map { XtreamCategory(it.id, it.name) }
    }

    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = runCatching {
        ensureIngested(acc)
        db.channelsFor(acc.id, categoryId).map { it.toChannel() }
    }

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = runCatching {
        ensureIngested(acc)
        db.vodFor(acc.id, categoryId).map { it.toMovie() }
    }

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = runCatching {
        ensureIngested(acc)
        db.seriesFor(acc.id, categoryId).map { it.toSeriesItem() }
    }

    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail> = runCatching {
        ensureIngested(acc)
        val header = db.seriesRow(acc.id, seriesId)
        val episodes = db.episodesFor(acc.id, seriesId).map { e ->
            XtreamEpisode(
                episodeId = e.episodeSid,
                season = e.season,
                episodeNum = e.episodeNum,
                title = e.title.ifBlank { "Episode ${e.episodeNum}" },
                plot = null,
                still = e.logo,
                streamUrl = e.url
            )
        }
        XtreamSeriesDetail(tmdbId = null, plot = null, backdrop = header?.logo, episodes = episodes)
    }

    /**
     * Now/next for an M3U live channel, from the XMLTV EPG (if one is configured — account.epgUrl
     * or the M3U's url-tvg header). Resolves the channel's tvg-id, then the programme spanning now +
     * the one after. Returns empty (guide shows "No information") when the channel has no tvg-id or
     * no EPG has been fetched. Also nudges a throttled EPG refresh so a first browse warms it.
     */
    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = runCatching {
        val tvgId = db.channelRow(acc.id, streamId)?.tvgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return@runCatching emptyList()
        // Lazily ensure the EPG is present/fresh (single-flight + throttled inside XmltvClient).
        runCatching { xmltv.refreshIfStale(acc) }
        val now = System.currentTimeMillis()
        db.epgNowNext(acc.id, tvgId, now).map { p ->
            XtreamProgram(
                title = p.title,
                description = p.desc.orEmpty(),
                startMs = p.startMs,
                endMs = p.endMs,
                nowPlaying = now in p.startMs until p.endMs
            )
        }
    }

    override suspend fun resolveStreamUrl(acc: XtreamAccount, kind: String, streamId: Int): String? {
        // URLs aren't formula-derivable for M3U (they're arbitrary provider URLs) — look them up.
        // ensureIngested so a fresh cold-start deep link can still resolve.
        ensureIngested(acc)
        return when (kind) {
            "live" -> db.channelUrl(acc.id, streamId)
            "movie" -> db.vodUrl(acc.id, streamId)
            else -> null
        }
    }

    private fun ContentChannel.toChannel() = XtreamChannel(
        streamId = sid, name = name, logo = logo, epgChannelId = tvgId, categoryId = categoryId,
        hasArchive = false, streamUrl = url
    )

    private fun ContentVod.toMovie() = XtreamMovie(
        streamId = sid, name = name, poster = logo, categoryId = categoryId, rating = null,
        streamUrl = url, tmdb = null, containerExtension = ext
    )

    private fun ContentSeries.toSeriesItem() = XtreamSeriesItem(
        seriesId = sid, name = name, poster = logo, categoryId = categoryId, plot = null, rating = null
    )

    companion object {
        private const val TAG = "M3UClient"
        private const val BUILD_BACKOFF_MS = 60 * 60 * 1000L
    }
}

/** Add an episode from a parsed /series/ M3U entry (name may or may not carry SxxExx). */
private fun IptvContentDb.IngestWriter.addEpisodeFrom(entry: com.nuvio.tv.core.iptv.content.M3UEntry) {
    val se = M3UParser.seriesEpisodeOf(entry.name)
    val series = se?.first ?: entry.name
    val season = se?.second ?: 1
    val episodeNum = se?.third ?: 0
    addEpisode(entry.group, series, season, episodeNum, entry.name, entry.logo, entry.url, entry.ext)
}
