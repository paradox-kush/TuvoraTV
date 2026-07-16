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
    private val sessions: StalkerSessionManager
) : IptvClient {

    // Browse-time rows keyed accountId:type:id — see [row]. This is what keeps play/detail from
    // re-paging the whole catalog (the request storm that got a live portal to block us).
    private val rowCache = ConcurrentHashMap<String, JsonObject>()

    // The live lineup per account (one get_all_channels request, filtered client-side) + each
    // channel's create_link `cmd`. Mapped to the domain model so the raw 13MB JSON isn't retained.
    private val liveCache = ConcurrentHashMap<String, List<XtreamChannel>>()
    private val liveCmds = ConcurrentHashMap<String, String>()
    private val liveMutex = Mutex()

    // The whole guide per account in ONE get_epg_info fetch, keyed by channel id (see [bulkEpg]).
    private class EpgSnapshot(val byChannel: Map<Int, List<XtreamProgram>>, val fetchedAtMs: Long)
    private val epgCache = ConcurrentHashMap<String, EpgSnapshot>()
    private val epgUnsupported = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val epgMutex = Mutex()

    // Season rows per series (one movie_id=<id> request), keyed accountId:seriesId — see [seasonsOf].
    private val seasonCache = ConcurrentHashMap<String, List<StalkerSeason>>()
    private val seasonMutex = Mutex()

    /** Drop an account's cached lineup/rows (portal or MAC edited, playlist removed). */
    fun evictCaches(accountId: String) {
        liveCache.remove(accountId)
        epgCache.remove(accountId)
        epgUnsupported.remove(accountId)
        seasonCache.keys.removeAll { it.startsWith("$accountId:") }
        liveCmds.keys.removeAll { it.startsWith("$accountId:") }
        rowCache.keys.removeAll { it.startsWith("$accountId:") }
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

    override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "itv", "get_genres")

    override suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "vod", "get_categories")

    override suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "series", "get_categories")

    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = runCatching {
        val all = allLiveChannels(acc)
        if (categoryId == null) all else all.filter { it.categoryId == categoryId }
    }

    /**
     * The WHOLE live lineup in ONE request, fetched once per account and filtered client-side.
     *
     * `get_all_channels` is what every real MAG client uses (stalkerhek / magplex / stalker-to-m3u all
     * do this). We used to page `get_ordered_list` instead, which a real portal serves **14 rows a
     * page** — 11,286 channels = ~800 requests, so it both hammered the portal into a Cloudflare ban
     * AND silently truncated at MAX_PAGES (we only ever saw ~25% of the lineup).
     */
    private suspend fun allLiveChannels(acc: XtreamAccount): List<XtreamChannel> = liveMutex.withLock {
        liveCache[acc.id]?.let { return@withLock it }
        val js = runCatching {
            sessions.sessionFor(acc).request(mapOf("type" to "itv", "action" to "get_all_channels"))
        }.getOrNull()
        val arr = (js as? JsonObject)?.get("data") as? com.google.gson.JsonArray
            ?: js as? com.google.gson.JsonArray
        val channels = arr?.mapNotNull { it as? JsonObject }?.mapNotNull { item ->
            val id = item.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            item.str("cmd")?.let { liveCmds["${acc.id}:$id"] = it }
            channelOf(acc, item, id)
        }.orEmpty()
        // A portal without get_all_channels falls back to the (expensive) paged path — don't cache an
        // empty lineup, or one bad response would strand the playlist for the session.
        if (channels.isEmpty()) return@withLock pagedLiveChannels(acc)
        channels.also { liveCache[acc.id] = it }
    }

    /** Legacy paged live browse — only for portals that don't answer get_all_channels. */
    private suspend fun pagedLiveChannels(acc: XtreamAccount): List<XtreamChannel> =
        orderedList(acc, "itv", null).mapNotNull { item ->
            val id = item.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
            channelOf(acc, item, id)
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
        orderedList(acc, "vod", categoryId, maxItems = CATEGORY_ITEMS).map { movieOf(acc, it) }.filter { it.streamId > 0 }
    }

    /**
     * Portal-side VOD search via get_ordered_list's `search` param (what the MAG UI's own search
     * uses). Stalker content never enters the TMDB match index — those player_api builds just fail
     * into backoff, and paging a 63k-movie catalog at 14 rows/page is a DoS — so the TMDB->stream
     * bridge asks the portal directly. Never throws.
     */
    suspend fun searchMovies(acc: XtreamAccount, query: String): List<XtreamMovie> = runCatching {
        orderedList(acc, "vod", null, search = query, maxItems = SEARCH_ITEMS)
            .map { movieOf(acc, it) }.filter { it.streamId > 0 }
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
        orderedList(acc, "series", categoryId, maxItems = CATEGORY_ITEMS).map { item ->
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
        val episodes = seasonsOf(acc, seriesId).flatMap { season ->
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
        bulkEpg(acc)?.let { return@runCatching it[streamId].orEmpty().take(limit) }
        // Portal has no get_epg_info — fall back to the per-channel call.
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
     * The WHOLE guide in ONE request (`get_epg_info&period=3` — 2.5MB, ~600 channels, 1s on a real
     * portal), keyed by channel id. Null when the portal doesn't support it, so the caller degrades to
     * the per-channel path. Re-fetched every [EPG_TTL_MS] because "now/next" advances.
     *
     * Only channels that HAVE epg appear — a miss here means the portal has no guide for that channel,
     * NOT that we should ask per-channel (that's what caused the fan-out).
     */
    private suspend fun bulkEpg(acc: XtreamAccount): Map<Int, List<XtreamProgram>>? = epgMutex.withLock {
        if (acc.id in epgUnsupported) return@withLock null
        val now = System.currentTimeMillis()
        epgCache[acc.id]?.takeIf { now - it.fetchedAtMs < EPG_TTL_MS }?.let { return@withLock it.byChannel }
        val js = runCatching {
            sessions.sessionFor(acc).request(mapOf("type" to "itv", "action" to "get_epg_info", "period" to EPG_PERIOD_HOURS))
        }.getOrNull()
        val data = (js as? JsonObject)?.get("data") as? JsonObject
        if (data == null || data.size() == 0) {
            epgUnsupported += acc.id   // don't retry the bulk call all session
            return@withLock null
        }
        val byChannel = HashMap<Int, List<XtreamProgram>>(data.size())
        for ((chId, arr) in data.entrySet()) {
            val id = chId.toIntOrNull() ?: continue
            val progs = (arr as? com.google.gson.JsonArray)?.mapNotNull { it as? JsonObject }
                ?.map { programOf(it, now) }.orEmpty()
            if (progs.isNotEmpty()) byChannel[id] = progs
        }
        epgCache[acc.id] = EpgSnapshot(byChannel, now)
        byChannel
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
     * Resolve a playable URL by running create_link FRESH (single-use play_token). [kind] is
     * "movie" / "live" / "episode:{seriesId}:{n}" (episodes reuse the series cmd with series={n}).
     * Because we don't retain the browse-time cmd, we re-fetch the item's cmd here, then create_link.
     * Returns null if the item is no longer in the portal.
     */
    override suspend fun resolveStreamUrl(acc: XtreamAccount, kind: String, streamId: Int): String? {
        val session = sessions.sessionFor(acc)
        return when {
            kind == "live" -> {
                val cmd = liveCmd(acc, streamId) ?: return null
                createLink(session, "itv", cmd)
            }
            kind == "movie" -> {
                val cmd = vodCmd(acc, streamId) ?: return null
                createLink(session, "vod", cmd)
            }
            else -> null
        }
    }

    /**
     * Episode play. The create_link cmd belongs to the SEASON row (it decodes to
     * `{"type":"series","series_id":536,"season_num":2}`), and the episode rides as `series={n}` — NOT
     * the top-level series row, whose cmd is empty. [season] null = a legacy 2-part episode id from
     * before seasons were modelled; fall back to the first season we find.
     */
    suspend fun resolveEpisodeUrl(acc: XtreamAccount, seriesId: Int, season: Int?, episodeNum: Int): String? {
        val session = sessions.sessionFor(acc)
        val seasons = seasonsOf(acc, seriesId)
        val target = (season?.let { s -> seasons.firstOrNull { it.number == s } } ?: seasons.firstOrNull())
            ?: return null
        return createLink(session, "vod", target.cmd, extraParams = mapOf("series" to episodeNum.toString()))
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
            if (seasons.isNotEmpty()) seasonCache["${acc.id}:$seriesId"] = seasons
            seasons
        }

    /** Portal-side series search — same rationale as [searchMovies]. */
    suspend fun searchSeries(acc: XtreamAccount, query: String): List<XtreamSeriesItem> = runCatching {
        orderedList(acc, "series", null, search = query, maxItems = SEARCH_ITEMS).map { item ->
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
        // The lineup fetch (one request, cached) carries every channel's cmd — so playing a channel
        // costs nothing but the create_link itself.
        allLiveChannels(acc)
        return liveCmds["${acc.id}:$streamId"] ?: row(acc, "itv", streamId)?.str("cmd")
    }

    private suspend fun vodCmd(acc: XtreamAccount, streamId: Int): String? =
        row(acc, "vod", streamId)?.str("cmd")

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
        rows.forEach { r -> r.int("id")?.let { rowCache[rowKey(accId, type, it)] = r } }
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
