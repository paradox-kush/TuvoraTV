package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nuvio.tv.core.iptv.IptvClient
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamAccountInfo
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.core.iptv.XtreamEpisode
import com.nuvio.tv.core.iptv.XtreamMovie
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.iptv.XtreamSeriesDetail
import com.nuvio.tv.core.iptv.XtreamSeriesItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An [IptvClient] backed by a Stalker portal (MAG/Ministra). Browses via the stateful
 * [StalkerSession] (handshake + get_profile + itv/vod/series get_ordered_list) and maps the raw
 * `{"js": …}` responses to the SAME domain models Xtream emits, so the whole hybrid lane (registry
 * ids, native detail, direct-stream playback) is identical downstream.
 *
 * PLAYBACK: [resolveStreamUrl] calls `create_link` FRESH at play time and strips the launcher
 * prefix. The returned URL carries a single-use / time-limited `play_token`, so it is NEVER cached —
 * the registry item's stream URL is a placeholder, and every play resolves anew (see the stream
 * short-circuit + the live guide's resolve-before-tune).
 */
@Singleton
class StalkerClient @Inject constructor(
    private val sessions: StalkerSessionManager,
    private val contentDb: com.nuvio.tv.core.iptv.content.IptvContentDb,
) : IptvClient {

    // Browse-time rows keyed accountId:type:id — see [row]. This is what keeps play/detail from
    // re-paging the whole catalog (the request storm that got a live portal to block us).
    private val rowCache = ConcurrentHashMap<String, JsonObject>()

    /** A row's use_http_tmp_link/use_load_balancing verdicts (null = key absent on the row). */
    private data class LinkFlags(val useHttpTmpLink: Boolean?, val useLoadBalancing: Boolean?)

    // Static-vs-mint evidence per row, keyed like [rowCache] — small enough to keep for the whole
    // lineup (the raw 13MB rows are NOT retained; get_all_channels items never enter rowCache).
    private val linkFlags = ConcurrentHashMap<String, LinkFlags>()

    // The live lineup per account (one get_all_channels request, filtered client-side) + each
    // channel's create_link `cmd`. Mapped to the domain model so the raw 13MB JSON isn't retained.
    // The live lineup lives in IptvContentDb (P6): one get_all_channels per LINEUP_TTL_MS,
    // replaced wholesale via replaceLiveLineup, every browse a local indexed read. That kills the
    // 13MB re-download every cold start AND makes a favorited channel playable offline — the cmd
    // (create_link's stable input) is persisted per row; only the single-use play URL never is.
    private val liveMutex = Mutex()

    // Bulk EPG lands in IptvContentDb.epg_programmes (streamed, chunk-inserted — see
    // [ensureBulkEpg]); nothing guide-sized is retained in memory anymore. This set only marks
    // portals whose get_epg_info genuinely has no data, so they use the per-channel fallback.
    private val epgUnsupported = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val epgMutex = Mutex()

    // Season rows per series (one movie_id=<id> request), keyed accountId:seriesId — see [seasonsOf].
    private val seasonCache = ConcurrentHashMap<String, List<StalkerSeason>>()
    private val seasonMutex = Mutex()

    /** Drop an account's cached lineup/rows (portal or MAC edited, playlist removed). */
    fun evictCaches(accountId: String) {
        epgUnsupported.remove(accountId)
        seasonCache.keys.removeAll { it.startsWith("$accountId:") }
        rowCache.keys.removeAll { it.startsWith("$accountId:") }
        linkFlags.keys.removeAll { it.startsWith("$accountId:") }
    }

    /** Verify = handshake succeeds (session authenticates) + account_info is reachable. */
    suspend fun verify(acc: XtreamAccount): Result<Unit> = runCatching {
        val session = sessions.sessionFor(acc)
        // A successful get_genres proves the full handshake+get_profile+authorised-browse chain.
        session.request(mapOf("type" to "itv", "action" to "get_genres"))
        Unit
    }

    /** Account status for the settings row. Stalker returns expiry as free text in `phone`. */
    override suspend fun accountInfo(acc: XtreamAccount): Result<XtreamAccountInfo> = runCatching {
        val js = sessions.sessionFor(acc)
            .request(mapOf("type" to "account_info", "action" to "get_main_info"))
        val obj = js as? JsonObject ?: JsonObject()
        // `phone` is free text like "February 20, 2027" — surface it verbatim as the status.
        val expiry = obj.str("phone")?.takeIf { it.isNotBlank() }
        XtreamAccountInfo(
            status = if (expiry != null) "Active" else null,
            expiresText = expiry,
            expiresAtEpochSec = null,
            activeConnections = null,
            maxConnections = null
        )
    }

    override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> = runCatching {
        if (ensureLineup(acc)) {
            return@runCatching contentDb.categoriesFor(acc.id, com.nuvio.tv.core.iptv.content.IptvContentDb.TYPE_LIVE)
                .map { XtreamCategory(it.id, it.name) }
        }
        // Mirror unavailable (portal down mid-refresh with no stored lineup): live portal call.
        categories(acc, "itv", "get_genres").getOrThrow()
    }

    override suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "vod", "get_categories")

    override suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "series", "get_categories")

    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = runCatching {
        if (!ensureLineup(acc)) return@runCatching emptyList()
        contentDb.channelsFor(acc.id, categoryId).map { r ->
            XtreamChannel(
                streamId = r.sid,
                name = r.name,
                logo = r.logo?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                epgChannelId = r.tvgId,
                categoryId = r.categoryId,
                hasArchive = r.hasArchive,
                streamUrl = ""   // create_link resolves the real single-use URL at play time
            )
        }
    }

    /** Windowed lineup read for the hub (item 5). Ensures the mirror, then a paged indexed read. */
    suspend fun liveChannelsPage(acc: XtreamAccount, categoryId: String?, offset: Int, limit: Int): List<XtreamChannel> {
        if (!ensureLineup(acc)) return emptyList()
        return contentDb.pageChannels(acc.id, categoryId, offset, limit).map { r ->
            XtreamChannel(
                streamId = r.sid,
                name = r.name,
                logo = r.logo?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                epgChannelId = r.tvgId,
                categoryId = r.categoryId,
                hasArchive = r.hasArchive,
                streamUrl = ""   // create_link resolves the real single-use URL at play time
            )
        }
    }

    /**
     * Ensures a fresh (<=[LINEUP_TTL_MS]) live lineup for [acc] is stored in [contentDb],
     * mirroring it when stale: genres + the WHOLE lineup in ONE `get_all_channels` (what every real
     * MAG client uses; TiviMate's playlist add does exactly this — research/iptv-catalog-loading.md).
     * Portals without get_all_channels fall back to the bounded paged fetch, persisted the same way.
     *
     * The lineup used to live in an in-memory map — 13 MB re-downloaded every cold start, and a
     * favorited channel unplayable from Library until the hub happened to be browsed. Now every
     * browse is an indexed read, and the refresh only ever runs from a FOREGROUND browse — never a
     * background worker, because a Stalker handshake evicts the other device on a shared MAC.
     */
    private suspend fun ensureLineup(acc: XtreamAccount, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force) {
            contentDb.builtAt(acc.id)?.takeIf { now - it < LINEUP_TTL_MS }?.let {
                return contentDb.liveCount(acc.id) > 0
            }
        }
        return liveMutex.withLock {
            contentDb.builtAt(acc.id)?.takeIf { !force && now - it < LINEUP_TTL_MS }?.let {
                return@withLock contentDb.liveCount(acc.id) > 0
            }
            val cats = runCatching { categories(acc, "itv", "get_genres").getOrThrow() }.getOrNull()
            val js = runCatching {
                sessions.sessionFor(acc).request(mapOf("type" to "itv", "action" to "get_all_channels"))
            }.getOrNull()
            val arr = (js as? JsonObject)?.get("data") as? com.google.gson.JsonArray
                ?: js as? com.google.gson.JsonArray
            var items = arr?.mapNotNull { it as? JsonObject }.orEmpty()
            // A portal without get_all_channels: bounded paged fetch (rowCache keeps the raw rows).
            if (items.isEmpty()) items = orderedList(acc, "itv", null)
            val rows = items.mapNotNull { item ->
                val id = item.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
                // The raw 13MB rows are dropped after this mapping, so the static-vs-mint flags
                // must be picked off here or the whole lineup would lose its evidence.
                rememberLinkFlags(acc.id, "itv", item, id)
                com.nuvio.tv.core.iptv.content.ContentChannel(
                    sid = id,
                    name = item.str("name").orEmpty(),
                    logo = item.str("logo")?.takeIf { it.isNotBlank() },
                    tvgId = item.str("xmltv_id")?.takeIf { it.isNotBlank() },
                    categoryId = item.str("tv_genre_id") ?: item.str("genre_id"),
                    url = "",
                    cmd = item.str("cmd"),
                    hasArchive = (item.int("tv_archive") ?: 0) > 0,
                )
            }
            // Nothing usable fetched: keep whatever lineup is stored (stale beats empty), and do
            // not stamp freshness — the next browse retries.
            if (rows.isEmpty()) return@withLock contentDb.liveCount(acc.id) > 0
            contentDb.replaceLiveLineup(acc.id, rows, cats.orEmpty().map { it.id to it.name })
            true
        }
    }

    private fun channelOf(acc: XtreamAccount, item: JsonObject, id: Int) = XtreamChannel(
        streamId = id,
        name = item.str("name").orEmpty(),
        logo = item.str("logo")?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
        epgChannelId = item.str("xmltv_id")?.takeIf { it.isNotBlank() },
        categoryId = item.str("tv_genre_id") ?: item.str("genre_id"),
        hasArchive = (item.int("tv_archive") ?: 0) > 0,
        // Placeholder: create_link resolves the real single-use URL at play time.
        streamUrl = ""
    )

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = runCatching {
        val items = orderedList(acc, "vod", categoryId, maxItems = CATEGORY_ITEMS)
        writeThroughVod(acc, items)
        items.map { movieOf(acc, it) }.filter { it.streamId > 0 }
    }

    /**
     * Write-through cache (P6): every VOD/series page browsed is upserted into [contentDb] with its
     * `cmd`, so anything the user has EVER seen on this device stays playable after a cold start
     * (Library / Continue Watching) without re-finding it on the portal. Best-effort by design and
     * NEVER a reason for extra requests — a full Stalker VOD mirror is impossible (14 rows/page).
     */
    private suspend fun writeThroughVod(acc: XtreamAccount, items: List<JsonObject>) {
        if (items.isEmpty()) return
        val rows = items.mapNotNull { item ->
            val id = item.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            com.nuvio.tv.core.iptv.content.ContentVod(
                sid = id,
                name = item.str("name").orEmpty(),
                logo = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() },
                categoryId = item.str("category_id"),
                url = "",
                ext = null,
                cmd = item.str("cmd"),
            )
        }
        runCatching { contentDb.upsertStalkerRows(acc.id, vod = rows) }
    }

    private suspend fun writeThroughSeries(acc: XtreamAccount, items: List<JsonObject>) {
        if (items.isEmpty()) return
        val rows = items.mapNotNull { item ->
            val id = item.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            com.nuvio.tv.core.iptv.content.ContentSeries(
                sid = id,
                name = item.str("name").orEmpty(),
                logo = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() },
                categoryId = item.str("category_id"),
            )
        }
        runCatching { contentDb.upsertStalkerRows(acc.id, series = rows) }
    }

    /**
     * Portal-side VOD search via get_ordered_list's `search` param (what the MAG UI's own search
     * uses). Stalker content never enters the TMDB match index — those player_api builds just fail
     * into backoff, and paging a 63k-movie catalog at 14 rows/page is a DoS — so the TMDB->stream
     * bridge asks the portal directly. Never throws.
     */
    suspend fun searchMovies(acc: XtreamAccount, query: String): List<XtreamMovie> = runCatching {
        val items = orderedList(acc, "vod", null, search = query, maxItems = SEARCH_ITEMS)
        writeThroughVod(acc, items)   // a searched-then-favorited movie must survive a cold start too
        items.map { movieOf(acc, it) }.filter { it.streamId > 0 }
    }.getOrDefault(emptyList())

    private fun movieOf(acc: XtreamAccount, item: JsonObject) = XtreamMovie(
        streamId = item.int("id") ?: 0,
        name = item.str("name").orEmpty(),
        poster = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
        categoryId = item.str("category_id"),
        rating = item.str("rating_imdb") ?: item.str("rating"),
        streamUrl = "",   // create_link at play time
        tmdb = null,
        containerExtension = null
    )

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = runCatching {
        val fetched = orderedList(acc, "series", categoryId, maxItems = CATEGORY_ITEMS)
        writeThroughSeries(acc, fetched)
        fetched.map { item ->
            XtreamSeriesItem(
                seriesId = item.int("id") ?: 0,
                name = item.str("name").orEmpty(),
                poster = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                categoryId = item.str("category_id"),
                plot = item.str("description"),
                rating = item.str("rating_imdb") ?: item.str("rating"),
                tmdb = null,
                year = item.str("year")?.trim()?.take(4)?.toIntOrNull()
            )
        }.filter { it.seriesId > 0 }
    }

    /**
     * Episodes for a Stalker series. A series is a two-level tree: the top-level row is just a
     * container (its own `series` array is EMPTY) and the real episodes hang off SEASON rows fetched
     * with `movie_id=<seriesId>`. Each episode plays via create_link on the SEASON cmd with
     * `series={n}`; the stream URL here is a placeholder (resolved fresh at play via the episode
     * content id -> [resolveStreamUrl]).
     *
     * We used to read the episode list off the top-level row, which is always empty — so every Stalker
     * series showed zero episodes. Seasons ARE modelled (verified on a real portal: Breaking Bad
     * returns Season 2..5 rows, each carrying its own episode numbers + cmd).
     */
    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail> = runCatching {
        // Read the series row for its episode list + cmd (portals have no get_series_info).
        val row = row(acc, "series", seriesId)
        val plot = row?.str("description")
        val backdrop = (row?.str("screenshot_uri") ?: row?.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) }
        val fromPortal = seasonsOf(acc, seriesId)
        // Cold start with the portal unreachable: the write-through rows still list the episodes.
        if (fromPortal.isEmpty()) {
            val stored = contentDb.episodesFor(acc.id, seriesId).map { ep ->
                XtreamEpisode(
                    episodeId = ep.episodeSid,
                    season = ep.season,
                    episodeNum = ep.episodeNum,
                    title = ep.title,
                    plot = null,
                    still = null,
                    streamUrl = ""
                )
            }
            if (stored.isNotEmpty()) {
                return@runCatching XtreamSeriesDetail(tmdbId = null, plot = plot, backdrop = backdrop, episodes = stored)
            }
        }
        val episodes = fromPortal.flatMap { season ->
            season.episodeNums.map { n ->
                XtreamEpisode(
                    // Encodes seriesId + season + episode so resolveStreamUrl can rebuild the cmd.
                    // A legacy 2-part id ("<seriesId>:<ep>") has no season and still parses.
                    episodeId = "$seriesId:${season.number}:$n",
                    season = season.number,
                    episodeNum = n,
                    title = "Episode $n",
                    plot = null,
                    still = null,
                    streamUrl = ""   // create_link at play time (season cmd + series={n})
                )
            }
        }
        XtreamSeriesDetail(tmdbId = null, plot = plot, backdrop = backdrop, episodes = episodes)
    }

    /** Now/next EPG. Stalker's itv get_short_epg returns programmes with begin/end timestamps. */
    /**
     * Now/next for one channel — served from the ONE bulk [bulkEpg] fetch, not a request per channel.
     * The guide UI asks per channel as tiles come into view, so the old per-channel `get_short_epg`
     * meant a request for every tile (measured on mobile: 132 in a single browse).
     */
    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = runCatching {
        if (ensureBulkEpg(acc)) {
            val now = System.currentTimeMillis()
            return@runCatching contentDb.epgNowNext(acc.id, streamId.toString(), now).map {
                XtreamProgram(
                    title = it.title,
                    description = it.desc.orEmpty(),
                    startMs = it.startMs,
                    endMs = it.endMs,
                    nowPlaying = now in it.startMs until it.endMs,
                )
            }
        }
        // Transient bulk failure (network/cooldown): return empty rather than fanning out a
        // per-channel request per visible tile — the next ensure retries. Only a portal that
        // GENUINELY lacks get_epg_info takes the per-channel path.
        if (acc.id !in epgUnsupported) return@runCatching emptyList()
        val js = sessions.sessionFor(acc).request(
            mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to streamId.toString(), "size" to limit.toString())
        )
        val list = js as? com.google.gson.JsonArray
            ?: (js as? JsonObject)?.get("data") as? com.google.gson.JsonArray
            ?: return@runCatching emptyList()
        val now = System.currentTimeMillis()
        list.mapNotNull { it as? JsonObject }.map { programOf(it, now) }
    }

    /**
     * Ensures a fresh (≤[EPG_TTL_MS]) bulk guide for [acc] is stored in [contentDb], fetching
     * `get_epg_info&period=3` when stale. The response is STREAMED through
     * [StalkerEpgStreamParser] into [com.nuvio.tv.core.iptv.content.IptvContentDb.replaceEpg]'s
     * batching writer — it used to be one String plus a full Gson tree plus a retained byChannel
     * map, which is the 174.5 MB failure mode a real client trace demonstrated
     * (research/iptv-catalog-loading.md §3). Peak memory is now one insert batch regardless of
     * guide size, and the rows double as [contentDb.epgSearch] input, so the sports matcher can
     * finally see a Stalker portal's own guide.
     *
     * Retry discipline: [StalkerSession.requestStreamOnce] is a SINGLE attempt; the one re-auth
     * retry lives here because each attempt owns a fresh [replaceEpg] transaction. Marks
     * [epgUnsupported] ONLY when a healthy body genuinely carries no `data` object — a transport
     * failure stays retryable (the old code marked unsupported on any failure, putting the guide
     * on the per-channel fan-out path for the whole session after one network blip).
     */
    private suspend fun ensureBulkEpg(acc: XtreamAccount): Boolean {
        if (acc.id in epgUnsupported) return false
        val now = System.currentTimeMillis()
        contentDb.epgBuiltAt(acc.id)?.takeIf { now - it < EPG_TTL_MS }?.let { return true }
        return epgMutex.withLock {
            contentDb.epgBuiltAt(acc.id)?.takeIf { now - it < EPG_TTL_MS }?.let { return@withLock true }
            val params = mapOf("type" to "itv", "action" to "get_epg_info", "period" to EPG_PERIOD_HOURS)
            var sawData = false
            var count = 0
            var streamed = false
            for (attempt in 1..2) {
                val result = runCatching {
                    contentDb.replaceEpg(acc.id, now) { writer ->
                        val parser = StalkerEpgStreamParser { chId, title, descr, startMs, endMs ->
                            writer.add(
                                com.nuvio.tv.core.iptv.content.EpgProgramme(
                                    channelId = chId.toString(),
                                    startMs = startMs,
                                    endMs = endMs,
                                    title = title,
                                    desc = descr.takeIf { it.isNotBlank() },
                                )
                            )
                        }
                        val gotBytes = sessions.sessionFor(acc).requestStreamOnce(params) { parser.feed(it) }
                        if (!gotBytes) throw StalkerAuthException("empty bulk-EPG body for ${acc.name}")
                        sawData = parser.sawData
                        count = parser.programmeCount
                    }
                }
                if (result.isSuccess) { streamed = true; break }
                val cause = result.exceptionOrNull()
                if (cause is StalkerAuthException && attempt == 1) {
                    sessions.sessionFor(acc).invalidate()   // one re-handshake, then one retry
                    continue
                }
                Log.w(TAG, "bulk EPG ingest failed for ${acc.name}", cause)
                return@withLock false                        // retryable — built_at stays stale
            }
            if (!streamed) return@withLock false
            if (!sawData) {
                epgUnsupported += acc.id                     // healthy body, genuinely no guide
                return@withLock false
            }
            Log.i(TAG, "bulk EPG ingested for ${acc.name}: $count programmes")
            count > 0
        }
    }

    private fun programOf(p: JsonObject, nowMs: Long): XtreamProgram {
        val startMs = (p.long("start_timestamp") ?: 0L) * 1000
        val endMs = (p.long("stop_timestamp") ?: 0L) * 1000
        return XtreamProgram(
            title = p.str("name").orEmpty(),
            description = p.str("descr").orEmpty(),
            startMs = startMs,
            endMs = endMs,
            nowPlaying = nowMs in startMs until endMs
        )
    }

    /**
     * Resolve a playable URL: the row's STATIC cmd when [StalkerPlaybackLinkPolicy] rules
     * create_link unnecessary, else a FRESH create_link (single-use play_token). [kind] is
     * "movie" / "live" (episodes reuse the series cmd with series={n} via [resolveEpisodeUrl]).
     * Returns null if the item is no longer in the portal.
     *
     * [forceFresh] is the one-shot 401/403/410 refresh ladder's entry: it bypasses the static
     * verdict so a static play that died still gets exactly one fresh create_link.
     */
    override suspend fun resolveStreamUrl(acc: XtreamAccount, kind: String, streamId: Int, forceFresh: Boolean): String? {
        val session = sessions.sessionFor(acc)
        return when {
            kind == "live" -> {
                val cmd = liveCmd(acc, streamId) ?: return null
                staticUrlOrNull(acc, "itv", streamId, cmd, forceFresh)?.let { return it }
                createLink(session, "itv", cmd)
            }
            kind == "movie" -> {
                val cmd = vodCmd(acc, streamId) ?: return null
                staticUrlOrNull(acc, "vod", streamId, cmd, forceFresh)?.let { return it }
                createLink(session, "vod", cmd)
            }
            else -> null
        }
    }

    /**
     * The static play URL when [StalkerPlaybackLinkPolicy] rules create_link unnecessary for this
     * row, else null (mint as always).
     *
     * INTEGRATION(WP1): flag evidence lives only in this session's in-memory caches — the
     * DB-cached rows (IptvContentDb ContentChannel/ContentVod) do not carry use_http_tmp_link /
     * use_load_balancing yet; WP1 owns those columns. A cold-start play served purely from the
     * store therefore has no evidence here and MINTS (the safe rule: absence of evidence keeps
     * minting). When WP1's fields land, read them off the stored row in THIS function and cold
     * starts inherit static playback too.
     */
    private fun staticUrlOrNull(acc: XtreamAccount, type: String, id: Int, cmd: String, forceMint: Boolean): String? {
        if (forceMint) return null
        val flags = linkFlags[rowKey(acc.id, type, id)]
        val decision = StalkerPlaybackLinkPolicy.decide(
            useHttpTmpLink = flags?.useHttpTmpLink,
            useLoadBalancing = flags?.useLoadBalancing,
            cmd = cmd,
        )
        return (decision as? StalkerPlaybackLinkPolicy.Decision.Static)?.url
    }

    /** Keep a row's flag evidence — only when the row actually carries a flag key. */
    private fun rememberLinkFlags(accId: String, type: String, item: JsonObject, id: Int) {
        val tmp = item.flag("use_http_tmp_link")
        val lb = item.flag("use_load_balancing")
        if (tmp == null && lb == null) return
        if (linkFlags.size > MAX_CACHED_ROWS) linkFlags.clear()   // same crude cap as rowCache
        linkFlags[rowKey(accId, type, id)] = LinkFlags(tmp, lb)
    }

    /**
     * Episode play. The create_link cmd belongs to the SEASON row (it decodes to
     * `{"type":"series","series_id":536,"season_num":2}`), and the episode rides as `series={n}` — NOT
     * the top-level series row, whose cmd is empty. [season] null = a legacy 2-part episode id from
     * before seasons were modelled; fall back to the first season we find.
     *
     * Episodes ALWAYS mint: the `series={n}` parameter is create_link's argument — the season cmd
     * is a container reference, not a playable address, so the static-cmd policy never applies.
     */
    suspend fun resolveEpisodeUrl(acc: XtreamAccount, seriesId: Int, season: Int?, episodeNum: Int): String? {
        // Season cmd resolution, cheapest first: this session's cache -> the write-through rows
        // (cold-start Continue Watching plays with ZERO portal requests before create_link) ->
        // the portal's season fetch.
        val cmd = seasonCache["${acc.id}:$seriesId"]
            ?.let { se -> (season?.let { n -> se.firstOrNull { it.number == n } } ?: se.firstOrNull())?.cmd }
            ?: contentDb.episodesFor(acc.id, seriesId)
                .let { rows -> (season?.let { n -> rows.firstOrNull { it.season == n } } ?: rows.firstOrNull())?.cmd }
            ?: seasonsOf(acc, seriesId)
                .let { se -> (season?.let { n -> se.firstOrNull { it.number == n } } ?: se.firstOrNull())?.cmd }
            ?: return null
        return createLink(sessions.sessionFor(acc), "vod", cmd, extraParams = mapOf("series" to episodeNum.toString()))
    }

    private class StalkerSeason(val number: Int, val cmd: String, val episodeNums: List<Int>)

    /**
     * The season rows for a series (`movie_id=<seriesId>`), each with its own create_link cmd and
     * episode numbers. One request, cached for the session — seasons don't change mid-browse.
     */
    private suspend fun seasonsOf(acc: XtreamAccount, seriesId: Int): List<StalkerSeason> =
        seasonMutex.withLock {
            seasonCache["${acc.id}:$seriesId"]?.let { return@withLock it }
            val js = runCatching {
                sessions.sessionFor(acc).request(
                    mapOf("type" to "series", "action" to "get_ordered_list",
                        "movie_id" to seriesId.toString(), "p" to "1")
                )
            }.getOrNull()
            val rows = ((js as? JsonObject)?.get("data") as? com.google.gson.JsonArray)
                ?.mapNotNull { it as? JsonObject }.orEmpty()
            val seasons = rows.mapNotNull { r ->
                val cmd = r.str("cmd")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // id is "<seriesId>:<season>"; the name ("Season 2") is the fallback.
                val num = r.str("id")?.substringAfter(':', "")?.trim()?.toIntOrNull()
                    ?: SEASON_NAME.find(r.str("name").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val eps = r.get("series")?.let { el ->
                    runCatching { el.asJsonArray.mapNotNull { it.asString.trim().toIntOrNull() } }
                        .getOrDefault(emptyList())
                }.orEmpty().sorted()
                StalkerSeason(num, cmd, eps)
            }.sortedBy { it.number }
            if (seasons.isNotEmpty()) {
                seasonCache["${acc.id}:$seriesId"] = seasons
                // Write-through (P6): each episode row carries its SEASON's cmd, so an episode in
                // Continue Watching resumes after a cold start with zero portal requests before
                // the create_link itself.
                val epRows = seasons.flatMap { se ->
                    se.episodeNums.map { n ->
                        com.nuvio.tv.core.iptv.content.ContentEpisode(
                            seriesSid = seriesId,
                            episodeSid = "$seriesId:${se.number}:$n",
                            season = se.number,
                            episodeNum = n,
                            title = "Episode $n",
                            logo = null,
                            url = "",
                            ext = null,
                            cmd = se.cmd,
                        )
                    }
                }
                runCatching { contentDb.upsertStalkerRows(acc.id, episodes = epRows) }
            }
            seasons
        }

    /** Portal-side series search — same rationale as [searchMovies]. */
    suspend fun searchSeries(acc: XtreamAccount, query: String): List<XtreamSeriesItem> = runCatching {
        val fetched = orderedList(acc, "series", null, search = query, maxItems = SEARCH_ITEMS)
        writeThroughSeries(acc, fetched)
        fetched.map { item ->
            XtreamSeriesItem(
                seriesId = item.int("id") ?: 0,
                name = item.str("name").orEmpty(),
                poster = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                categoryId = item.str("category_id"),
                plot = item.str("description"),
                rating = item.str("rating_imdb") ?: item.str("rating"),
                tmdb = null,
                year = item.str("year")?.trim()?.take(4)?.toIntOrNull()
            )
        }.filter { it.seriesId > 0 }
    }.getOrDefault(emptyList())

    // --- create_link ----------------------------------------------------------

    private suspend fun createLink(
        session: StalkerSession,
        type: String,
        cmd: String,
        extraParams: Map<String, String> = emptyMap()
    ): String? {
        val params = buildMap {
            put("type", type)
            put("action", "create_link")
            put("cmd", cmd)
            put("forced_storage", "undefined")
            put("disable_ad", "0")
            put("JsHttpRequest", "1-xml")   // harmless dup; StalkerSession adds it too
            putAll(extraParams)
        }.filterKeys { it != "JsHttpRequest" }   // let the session own JsHttpRequest
        val js = runCatching { session.request(params) }.getOrNull() ?: return null
        val obj = js as? JsonObject ?: return null
        val rawCmd = obj.str("cmd") ?: return null
        return StalkerProtocol.extractStreamUrl(rawCmd)
    }

    // --- cmd lookup (browse-time cmd needed for create_link) ------------------

    private suspend fun liveCmd(acc: XtreamAccount, streamId: Int): String? {
        // The mirrored lineup carries every channel's cmd — playing a channel costs nothing but
        // the create_link itself, even on a cold start with the portal briefly unreachable.
        ensureLineup(acc)
        return contentDb.channelRow(acc.id, streamId)?.cmd ?: row(acc, "itv", streamId)?.str("cmd")
    }

    private suspend fun vodCmd(acc: XtreamAccount, streamId: Int): String? =
        // Hot browse rows first, then the write-through store (anything EVER browsed on this
        // device — the cold-start Library play that used to fall into a hopeless bounded scan),
        // then the portal's own search / scan as the true cold miss.
        rowCache[rowKey(acc.id, "vod", streamId)]?.str("cmd")
            ?: contentDb.vodRow(acc.id, streamId)?.cmd
            ?: row(acc, "vod", streamId)?.str("cmd")

    private suspend fun seriesCmd(acc: XtreamAccount, seriesId: Int): String? =
        row(acc, "series", seriesId)?.str("cmd")

    /**
     * The browse row for ONE item. `get_ordered_list` already returns each item's `cmd` (the
     * create_link input), so [orderedList] caches every row it sees and playing anything you browsed
     * costs ZERO extra requests.
     *
     * This used to re-page the ENTIRE catalog (genre=*, up to [MAX_PAGES] requests) per lookup — one
     * tap = ~200 requests — which is what got a real portal's Cloudflare to block the whole IP. The
     * cold-start miss (play straight from the library) still scans, but stops at the match.
     */
    private suspend fun row(acc: XtreamAccount, type: String, id: Int): JsonObject? =
        rowCache[rowKey(acc.id, type, id)]
            ?: orderedList(acc, type, null, stopWhen = { it.int("id") == id })
                .firstOrNull { it.int("id") == id }

    private fun rowKey(accId: String, type: String, id: Int) = "$accId:$type:$id"

    private fun cacheRows(accId: String, type: String, rows: List<JsonObject>) {
        // ponytail: crude cap, not an LRU — swap one in only if this shows up in a memory profile.
        if (rowCache.size > MAX_CACHED_ROWS) rowCache.clear()
        rows.forEach { r ->
            r.int("id")?.let {
                rowCache[rowKey(accId, type, it)] = r
                rememberLinkFlags(accId, type, r, it)
            }
        }
    }

    // --- request helpers ------------------------------------------------------

    private suspend fun categories(acc: XtreamAccount, type: String, action: String): Result<List<XtreamCategory>> = runCatching {
        val js = sessions.sessionFor(acc).request(mapOf("type" to type, "action" to action))
        val arr = js as? com.google.gson.JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val id = obj.str("id") ?: return@mapNotNull null
                if (id == "*") return@mapNotNull null   // "*" = All, skip (the guide adds its own "All")
                XtreamCategory(id, obj.str("title") ?: obj.str("name").orEmpty())
            }
    }

    /**
     * Paginated get_ordered_list across all pages (js.total_items bounds the loop). Returns the flat
     * list of item objects. Capped so a 26k-channel "All" fetch can't run away — categories are the
     * real browse path (matches the Xtream/M3U "All channels" cap).
     */
    private suspend fun orderedList(
        acc: XtreamAccount,
        type: String,
        categoryId: String?,
        search: String? = null,
        maxItems: Int = MAX_ITEMS,
        stopWhen: ((JsonObject) -> Boolean)? = null,
    ): List<JsonObject> {
        val session = sessions.sessionFor(acc)
        val out = ArrayList<JsonObject>()
        var page = 1
        var total = Int.MAX_VALUE
        while (out.size < total && out.size < maxItems && page <= MAX_PAGES) {
            val params = buildMap {
                put("type", type)
                put("action", "get_ordered_list")
                put("genre", categoryId ?: "*")
                if (type != "itv") put("category", categoryId ?: "*")
                search?.let { put("search", it) }
                put("p", page.toString())
                put("sortby", "number")
                put("JsHttpRequest", "1-xml")
            }.filterKeys { it != "JsHttpRequest" }
            val js = runCatching { session.request(params) }.getOrNull() ?: break
            val obj = js as? JsonObject ?: break
            total = obj.int("total_items") ?: obj.int("max_page_items")?.let { it * MAX_PAGES } ?: out.size
            val data = obj.get("data") as? com.google.gson.JsonArray ?: break
            if (data.size() == 0) break
            val rows = data.mapNotNull { it as? JsonObject }
            // Every row carries its `cmd` — keep them so play/detail never re-pages to find one.
            cacheRows(acc.id, type, rows)
            out += rows
            if (stopWhen != null && rows.any(stopWhen)) break   // found the target — stop paging
            page++
        }
        return out
    }

    /** Portal logos/screenshots may be relative — resolve against the portal base. */
    private fun absolutize(acc: XtreamAccount, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = acc.portalUrl.trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    // --- Gson field accessors (portals type fields inconsistently — read leniently) ----

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull }?.let { el ->
            runCatching { el.asInt }.getOrNull() ?: runCatching { el.asString.trim().toInt() }.getOrNull()
        }

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { !it.isJsonNull }?.let { el ->
            runCatching { el.asLong }.getOrNull() ?: runCatching { el.asString.trim().toLong() }.getOrNull()
        }

    /** Portal flags arrive as booleans, numbers or quoted strings — like the tv_archive parse.
     *  Null = the key is absent (or unreadable), which callers treat as "no evidence". */
    private fun JsonObject.flag(key: String): Boolean? {
        val el = get(key)?.takeIf { !it.isJsonNull } ?: return null
        val s = runCatching { el.asString }.getOrNull()?.trim()?.lowercase() ?: return null
        return when {
            s.isEmpty() -> null
            s == "true" -> true
            s == "false" -> false
            else -> s.toIntOrNull()?.let { it != 0 }
        }
    }

    companion object {
        private const val TAG = "StalkerClient"
        private const val MAX_ITEMS = 8000    // ponytail: categories are the browse path; don't slurp 26k
        private const val MAX_PAGES = 200
        private const val MAX_CACHED_ROWS = 10_000

        // A hub category is ONE poster row (no see-all), and a real portal serves get_ordered_list 14
        // rows a page — so paging a 5,000-movie category cost ~200 requests to fill a row nobody
        // scrolls to the end of. 70 items = 5 requests.
        // ponytail: fixed cap, not incremental paging. If a row ever needs to go deeper, page it on
        // demand as the row scrolls rather than raising this.
        private const val CATEGORY_ITEMS = 70

        // How long the mirrored live lineup stays fresh. Refreshed ONLY from a foreground browse
        // (a background Stalker sync would evict the other device on a shared MAC); 12h matches
        // the M3U catalog's cadence.
        private const val LINEUP_TTL_MS = 12L * 60 * 60 * 1000
        private const val SEARCH_ITEMS = 100  // search results: a page or two is plenty

        // get_epg_info window + snapshot freshness. 3h covers now/next; re-fetched every 30 min so
        // "now" keeps up.
        private const val EPG_PERIOD_HOURS = "3"
        private const val EPG_TTL_MS = 30 * 60 * 1000L
        private val SEASON_NAME = Regex("""season\s*(\d+)""", RegexOption.IGNORE_CASE)
    }
}

/** URL-encode a Stalker cmd for the create_link query (kept as a helper for testability parity). */
internal fun encodeStalkerCmd(cmd: String): String = URLEncoder.encode(cmd, "UTF-8")
