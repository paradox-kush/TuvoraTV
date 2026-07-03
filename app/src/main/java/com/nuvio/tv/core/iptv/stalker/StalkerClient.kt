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
import java.net.URLEncoder
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

    /** Verify = handshake succeeds (session authenticates) + account_info is reachable. */
    suspend fun verify(acc: XtreamAccount): Result<Unit> = runCatching {
        val session = sessions.sessionFor(acc)
        // A successful get_genres proves the full handshake+get_profile+authorised-browse chain.
        session.request(mapOf("type" to "itv", "action" to "get_genres"))
        Unit
    }

    /** Account status for the settings row. Stalker returns expiry as free text in `phone`. */
    suspend fun accountInfo(acc: XtreamAccount): Result<XtreamAccountInfo> = runCatching {
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
        orderedList(acc, "itv", categoryId).map { item ->
            XtreamChannel(
                streamId = item.int("id") ?: 0,
                name = item.str("name").orEmpty(),
                logo = item.str("logo")?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                epgChannelId = item.str("xmltv_id")?.takeIf { it.isNotBlank() },
                categoryId = item.str("tv_genre_id") ?: item.str("genre_id"),
                hasArchive = (item.int("tv_archive") ?: 0) > 0,
                // Placeholder: create_link resolves the real single-use URL at play time.
                streamUrl = ""
            )
        }.filter { it.streamId > 0 }
    }

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = runCatching {
        orderedList(acc, "vod", categoryId).map { item ->
            XtreamMovie(
                streamId = item.int("id") ?: 0,
                name = item.str("name").orEmpty(),
                poster = (item.str("screenshot_uri") ?: item.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) },
                categoryId = item.str("category_id"),
                rating = item.str("rating_imdb") ?: item.str("rating"),
                streamUrl = "",   // create_link at play time
                tmdb = null,
                containerExtension = null
            )
        }.filter { it.streamId > 0 }
    }

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = runCatching {
        orderedList(acc, "series", categoryId).map { item ->
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
     * Episodes for a Stalker series. The series get_ordered_list rows carry a `series` array of
     * episode numbers; each episode plays via create_link on the SERIES cmd with `series={n}`. We
     * expose them as [XtreamEpisode]s whose stream URL is a placeholder (resolved fresh at play via
     * the episode content id -> [resolveStreamUrl]).
     *
     * ponytail: seasons aren't modelled by these portals (flat episode list) — everything lands in
     * season 1. Grouping by a season field is the upgrade path if a portal ever provides one.
     */
    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail> = runCatching {
        // Re-fetch the series row to read its episode list + cmd (portals don't have a get_series_info).
        val row = orderedList(acc, "series", null).firstOrNull { it.int("id") == seriesId }
        val plot = row?.str("description")
        val backdrop = (row?.str("screenshot_uri") ?: row?.str("cover"))?.takeIf { it.isNotBlank() }?.let { absolutize(acc, it) }
        val episodeNums = row?.get("series")?.let { el ->
            runCatching { el.asJsonArray.mapNotNull { it.asString.trim().toIntOrNull() } }.getOrDefault(emptyList())
        }.orEmpty()
        val episodes = episodeNums.sorted().map { n ->
            XtreamEpisode(
                // Episode content id encodes seriesId + episode number so resolveStreamUrl can rebuild the cmd.
                episodeId = "$seriesId:$n",
                season = 1,
                episodeNum = n,
                title = "Episode $n",
                plot = null,
                still = null,
                streamUrl = ""   // create_link at play time (series cmd + series={n})
            )
        }
        XtreamSeriesDetail(tmdbId = null, plot = plot, backdrop = backdrop, episodes = episodes)
    }

    /** Now/next EPG. Stalker's itv get_short_epg returns programmes with begin/end timestamps. */
    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = runCatching {
        val js = sessions.sessionFor(acc).request(
            mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to streamId.toString(), "size" to limit.toString())
        )
        val list = js as? com.google.gson.JsonArray
            ?: (js as? JsonObject)?.get("data") as? com.google.gson.JsonArray
            ?: return@runCatching emptyList()
        val now = System.currentTimeMillis()
        list.mapNotNull { it as? JsonObject }.map { p ->
            val startMs = (p.long("start_timestamp") ?: 0L) * 1000
            val endMs = (p.long("stop_timestamp") ?: 0L) * 1000
            XtreamProgram(
                title = p.str("name").orEmpty(),
                description = p.str("descr").orEmpty(),
                startMs = startMs,
                endMs = endMs,
                nowPlaying = now in startMs until endMs
            )
        }
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

    /** Episode play: create_link on the series cmd with `series={episodeNum}`. */
    suspend fun resolveEpisodeUrl(acc: XtreamAccount, seriesId: Int, episodeNum: Int): String? {
        val session = sessions.sessionFor(acc)
        val cmd = seriesCmd(acc, seriesId) ?: return null
        return createLink(session, "vod", cmd, extraParams = mapOf("series" to episodeNum.toString()))
    }

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

    private suspend fun liveCmd(acc: XtreamAccount, streamId: Int): String? =
        orderedList(acc, "itv", null).firstOrNull { it.int("id") == streamId }?.str("cmd")

    private suspend fun vodCmd(acc: XtreamAccount, streamId: Int): String? =
        orderedList(acc, "vod", null).firstOrNull { it.int("id") == streamId }?.str("cmd")

    private suspend fun seriesCmd(acc: XtreamAccount, seriesId: Int): String? =
        orderedList(acc, "series", null).firstOrNull { it.int("id") == seriesId }?.str("cmd")

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
    private suspend fun orderedList(acc: XtreamAccount, type: String, categoryId: String?): List<JsonObject> {
        val session = sessions.sessionFor(acc)
        val out = ArrayList<JsonObject>()
        var page = 1
        var total = Int.MAX_VALUE
        while (out.size < total && out.size < MAX_ITEMS && page <= MAX_PAGES) {
            val params = buildMap {
                put("type", type)
                put("action", "get_ordered_list")
                put("genre", categoryId ?: "*")
                if (type != "itv") put("category", categoryId ?: "*")
                put("p", page.toString())
                put("sortby", "number")
                put("JsHttpRequest", "1-xml")
            }.filterKeys { it != "JsHttpRequest" }
            val js = runCatching { session.request(params) }.getOrNull() ?: break
            val obj = js as? JsonObject ?: break
            total = obj.int("total_items") ?: obj.int("max_page_items")?.let { it * MAX_PAGES } ?: out.size
            val data = obj.get("data") as? com.google.gson.JsonArray ?: break
            if (data.size() == 0) break
            data.mapNotNullTo(out) { it as? JsonObject }
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
    }
}

/** URL-encode a Stalker cmd for the create_link query (kept as a helper for testability parity). */
internal fun encodeStalkerCmd(cmd: String): String = URLEncoder.encode(cmd, "UTF-8")
