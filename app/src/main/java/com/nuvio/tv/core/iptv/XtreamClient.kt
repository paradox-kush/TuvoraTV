package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.dns.PlaylistDns
import com.nuvio.tv.data.remote.api.XtreamApi
import com.nuvio.tv.data.remote.dto.XtreamEpgEntryDto
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-content-type category include lists.
 * Semantics: null = ALL categories (including ones the provider adds later);
 * empty list = none; non-empty list = only those category ids.
 */
data class CategorySelections(
    val live: List<String>? = null,
    val movies: List<String>? = null,
    val series: List<String>? = null
) {
    fun forType(type: String): List<String>? = when (type) {
        XtreamAccount.TYPE_LIVE -> live
        XtreamAccount.TYPE_MOVIES -> movies
        XtreamAccount.TYPE_SERIES -> series
        else -> null
    }

    fun withType(type: String, selection: List<String>?): CategorySelections = when (type) {
        XtreamAccount.TYPE_LIVE -> copy(live = selection)
        XtreamAccount.TYPE_MOVIES -> copy(movies = selection)
        XtreamAccount.TYPE_SERIES -> copy(series = selection)
        else -> this
    }

    val allNull: Boolean get() = live == null && movies == null && series == null
}

/**
 * A configured IPTV playlist the user has added. P1 of the playlist manager: still Xtream-only,
 * but carries the shared playlist options (source type, EPG override, DNS, refresh, content-type
 * toggles, category selections). All new fields default so previously-persisted JSON loads
 * unchanged (see the Gson normalizer in XtreamAccountStore).
 */
data class XtreamAccount(
    val id: String,            // stable key (the normalized base url)
    val name: String,          // user-facing label
    val baseUrl: String,       // e.g. http://host:port  (no trailing slash, no path)
    val username: String,
    val password: String,
    val enabled: Boolean = true,
    val sourceType: String = SOURCE_XTREAM,
    val epgUrl: String? = null,
    val dnsProvider: String = DNS_SYSTEM,
    val autoRefreshHours: Int = DEFAULT_AUTO_REFRESH_HOURS,   // 0 = off; 24 = default
    val contentTypes: Set<String> = DEFAULT_CONTENT_TYPES,
    val categorySelections: CategorySelections = CategorySelections(),
    /**
     * Display name of the picked M3U for a [SOURCE_FILE] playlist (the local copy lives at
     * `files/playlists/{id}.m3u`; see M3UFileStore). Null for every other source type. A file
     * playlist that arrives with no local copy (synced from another device — file contents are
     * NOT synced) keeps this so the UI can offer "re-import on this device".
     */
    val fileName: String? = null
) {
    fun typeEnabled(type: String): Boolean = type in contentTypes

    /** Category filter: null selection = all (incl. future); empty = none; list = only those ids. */
    fun allowsCategory(type: String, categoryId: String?): Boolean {
        val selection = categorySelections.forType(type) ?: return true
        return categoryId != null && categoryId in selection
    }

    companion object {
        // Source types (P1: only xtream is functional; url/file/stalker are reserved for later phases).
        const val SOURCE_XTREAM = "xtream"
        const val SOURCE_URL = "url"
        const val SOURCE_FILE = "file"
        const val SOURCE_STALKER = "stalker"

        // DNS provider ids persisted to dnsProvider (opaque strings; behaviour wired in P3).
        const val DNS_SYSTEM = "system"
        const val DNS_CLOUDFLARE = "cloudflare"
        const val DNS_GOOGLE = "google"
        const val DNS_MULLVAD = "mullvad"
        const val DNS_QUAD9 = "quad9"
        const val DNS_DNSSB = "dnssb"

        // Auto-refresh options (hours). 0 = off; 24 = product default.
        const val DEFAULT_AUTO_REFRESH_HOURS = 24
        val AUTO_REFRESH_OPTIONS = listOf(0, 6, 12, 24, 48, 72)

        const val TYPE_LIVE = "live"
        const val TYPE_MOVIES = "movies"
        const val TYPE_SERIES = "series"
        val DEFAULT_CONTENT_TYPES = setOf(TYPE_LIVE, TYPE_MOVIES, TYPE_SERIES)
    }
}

/** Account status from the panel's `user_info` (player_api.php with no action). */
data class XtreamAccountInfo(
    val status: String?,               // "Active", "Expired", ...
    val expiresAtEpochSec: Long?,      // null = unlimited/unknown
    val activeConnections: Int?,
    val maxConnections: Int?
)

// --- Domain models (what the UI consumes) -----------------------------------

data class XtreamChannel(
    val streamId: Int,
    val name: String,
    val logo: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val hasArchive: Boolean,
    val streamUrl: String
)

data class XtreamMovie(
    val streamId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val rating: String?,
    val streamUrl: String,
    val tmdb: Int? = null,
    val containerExtension: String? = null
)

data class XtreamSeriesItem(
    val seriesId: Int,
    val name: String,
    val poster: String?,
    val categoryId: String?,
    val plot: String?,
    val rating: String?,
    val tmdb: Int? = null,
    val year: Int? = null
)

data class XtreamCategory(val id: String, val name: String)

data class XtreamEpisode(
    val episodeId: String,
    val season: Int,
    val episodeNum: Int,
    val title: String,
    val plot: String?,
    val still: String?,
    val streamUrl: String
)

data class XtreamSeriesDetail(
    val tmdbId: Int?,
    val plot: String?,
    val backdrop: String?,
    /** First-air date — the only verify signal old panels give for series (no tmdb there). */
    val releaseDate: String? = null,
    val episodes: List<XtreamEpisode> = emptyList()
)

data class XtreamProgram(
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
    val nowPlaying: Boolean
)

/** Verify signals from get_vod_info during TMDB->stream matching. */
data class XtreamVodSignal(val tmdbId: Int?, val year: Int?)

/**
 * Talks to one Xtream panel. Builds the `player_api.php` URLs and the live/vod/series
 * stream URLs, then maps the raw DTOs to domain models.
 *
 * ponytail: stream URLs reuse the account's entered baseUrl. Some panels send a
 * different host in server_info.url for load-balancing — switch to that if a panel
 * 302s the .ts requests away.
 */
@Singleton
class XtreamClient @Inject constructor(
    /** System-DNS API (shared client) — the fast path when a playlist uses no DoH. */
    private val api: XtreamApi,
    /** Shared OkHttp client whose connection pool a per-provider DoH client reuses. */
    private val baseClient: OkHttpClient,
    private val moshi: Moshi,
    private val playlistDns: PlaylistDns,
) : IptvClient {

    /** Per-provider [XtreamApi] cache. Built lazily off a DoH-derived client that shares [baseClient]'s pool. */
    private val apiByProvider = ConcurrentHashMap<String, XtreamApi>()

    /**
     * The [XtreamApi] to use for [acc]. The system provider (the common case) returns the injected
     * shared [api] untouched; a DoH provider returns a cached Retrofit built on a client whose DNS is
     * the playlist's resolver (all Xtream URLs are absolute @Url, so baseUrl is only a placeholder).
     */
    private fun apiFor(acc: XtreamAccount): XtreamApi {
        if (!playlistDns.usesDoh(acc.dnsProvider)) return api
        return apiByProvider.getOrPut(acc.dnsProvider) {
            Retrofit.Builder()
                .baseUrl("https://placeholder.nuvio.tv/")
                .client(playlistDns.clientFor(baseClient, acc.dnsProvider))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(XtreamApi::class.java)
        }
    }
    /** Verifies credentials. Success only when the panel reports auth=1 and an active status. */
    suspend fun verify(acc: XtreamAccount): Result<Unit> = call {
        val info = apiFor(acc).getAccount(playerApi(acc)).requireBody().userInfo
        check(info?.auth == 1) { "Authentication failed" }
        val status = info.status?.lowercase().orEmpty()
        check(status.isEmpty() || status == "active") { "Account status: ${info.status}" }
    }

    /** Account status (expiry/connections) for the settings row. Same endpoint as [verify]. */
    suspend fun accountInfo(acc: XtreamAccount): Result<XtreamAccountInfo> = call {
        val info = apiFor(acc).getAccount(playerApi(acc)).requireBody().userInfo
        XtreamAccountInfo(
            status = info?.status?.takeIf { it.isNotBlank() },
            expiresAtEpochSec = info?.expDate?.trim()?.toLongOrNull(),
            activeConnections = info?.activeConnections?.trim()?.toIntOrNull(),
            maxConnections = info?.maxConnections?.trim()?.toIntOrNull()
        )
    }

    override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "get_live_categories")

    override suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "get_vod_categories")

    override suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "get_series_categories")

    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = call {
        apiFor(acc).getLiveStreams(playerApi(acc, "get_live_streams", categoryId)).requireBody().mapNotNull { dto ->
            val id = dto.streamId ?: return@mapNotNull null
            XtreamChannel(
                streamId = id,
                name = dto.name.orEmpty(),
                logo = dto.streamIcon?.takeIf { it.isNotBlank() },
                epgChannelId = dto.epgChannelId?.takeIf { it.isNotBlank() },
                categoryId = dto.categoryId,
                hasArchive = (dto.tvArchive ?: 0) > 0,
                streamUrl = streamUrl(acc, "live", id, "ts")
            )
        }
    }

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = call {
        apiFor(acc).getVodStreams(playerApi(acc, "get_vod_streams", categoryId)).requireBody().mapNotNull { dto ->
            val id = dto.streamId ?: return@mapNotNull null
            XtreamMovie(
                streamId = id,
                name = dto.name.orEmpty(),
                poster = dto.streamIcon?.takeIf { it.isNotBlank() },
                categoryId = dto.categoryId,
                rating = dto.rating,
                streamUrl = streamUrl(acc, "movie", id, dto.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"),
                tmdb = dto.tmdb?.takeIf { it > 0 },
                containerExtension = dto.containerExtension?.takeIf { it.isNotBlank() }
            )
        }
    }

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = call {
        apiFor(acc).getSeries(playerApi(acc, "get_series", categoryId)).requireBody().mapNotNull { dto ->
            val id = dto.seriesId ?: return@mapNotNull null
            XtreamSeriesItem(
                id, dto.name.orEmpty(), dto.cover?.takeIf { it.isNotBlank() }, dto.categoryId, dto.plot, dto.rating,
                tmdb = dto.tmdb?.takeIf { it > 0 },
                year = (dto.releaseDate ?: dto.releaseDateAlt)?.trim()?.take(4)?.toIntOrNull()
            )
        }
    }

    /** Now + next few programs for a channel (cheap, one call). Full XMLTV grid is the upgrade path. */
    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = call {
        val url = playerApi(acc, "get_short_epg").toHttpUrl().newBuilder()
            .addQueryParameter("stream_id", streamId.toString())
            .addQueryParameter("limit", limit.toString())
            .build().toString()
        apiFor(acc).getShortEpg(url).requireBody().listings.orEmpty().map { it.toProgram() }
    }

    /** Full episode list (across seasons) for a series, each with its built stream URL. */
    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail> = call {
        val url = playerApi(acc, "get_series_info").toHttpUrl().newBuilder()
            .addQueryParameter("series_id", seriesId.toString())
            .build().toString()
        val resp = apiFor(acc).getSeriesInfo(url).requireBody()
        val episodes = resp.episodes.orEmpty().flatMap { (seasonKey, list) ->
            list.mapNotNull { e ->
                val epId = e.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val ext = e.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
                XtreamEpisode(
                    episodeId = epId,
                    season = e.season ?: seasonKey.toIntOrNull() ?: 1,
                    episodeNum = e.episodeNum ?: 0,
                    title = e.title.orEmpty().ifBlank { "Episode" },
                    plot = e.info?.plot,
                    still = e.info?.movieImage?.takeIf { it.isNotBlank() },
                    streamUrl = seriesEpisodeUrl(acc, epId, ext)
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.episodeNum }))
        XtreamSeriesDetail(
            tmdbId = resp.info?.tmdbId?.takeIf { it > 0 },
            plot = resp.info?.plot,
            backdrop = resp.info?.backdropPath?.firstOrNull(),
            releaseDate = resp.info?.releaseDate ?: resp.info?.releaseDateAlt,
            episodes = episodes
        )
    }

    private fun seriesEpisodeUrl(acc: XtreamAccount, episodeId: String, ext: String): String =
        (acc.baseUrl.toHttpUrlOrNull() ?: error("Invalid server URL"))
            .newBuilder()
            .addPathSegment("series").addPathSegment(acc.username).addPathSegment(acc.password)
            .addPathSegment("$episodeId.$ext")
            .build().toString()

    /** Fetches a VOD item's TMDB id (for native art/metadata enrichment). null if the panel doesn't provide one. */
    suspend fun vodTmdbId(acc: XtreamAccount, vodId: Int): Result<Int?> = call {
        val url = playerApi(acc, "get_vod_info").toHttpUrl().newBuilder()
            .addQueryParameter("vod_id", vodId.toString())
            .build().toString()
        apiFor(acc).getVodInfo(url).requireBody().info?.tmdbId?.takeIf { it > 0 }
    }

    /** What get_vod_info can confirm about a candidate during TMDB->stream matching. */
    suspend fun vodMatchSignal(acc: XtreamAccount, vodId: Int): Result<XtreamVodSignal> = call {
        val url = playerApi(acc, "get_vod_info").toHttpUrl().newBuilder()
            .addQueryParameter("vod_id", vodId.toString())
            .build().toString()
        val info = apiFor(acc).getVodInfo(url).requireBody().info
        XtreamVodSignal(
            tmdbId = info?.tmdbId?.takeIf { it > 0 },
            year = (info?.releaseDate ?: info?.releaseDateAlt)?.trim()?.take(4)?.toIntOrNull()
        )
    }

    // --- URL building --------------------------------------------------------

    private fun playerApi(acc: XtreamAccount, action: String? = null, categoryId: String? = null): String {
        val b = (acc.baseUrl.toHttpUrlOrNull() ?: error("Invalid server URL"))
            .newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", acc.username)
            .addQueryParameter("password", acc.password)
        if (action != null) b.addQueryParameter("action", action)
        if (categoryId != null) b.addQueryParameter("category_id", categoryId)
        return b.build().toString()
    }

    /**
     * Public stream-URL builder for rebuilding an item from a parsed content id on a
     * registry cache miss (deep link / saved library item). `kind` is "movie" or "live";
     * VOD container extension isn't known here, so it falls back to "mp4" like [vodMovies].
     */
    fun buildStreamUrl(acc: XtreamAccount, kind: String, id: Int, ext: String = "mp4"): String =
        streamUrl(acc, kind, id, if (kind == "live") "ts" else ext)

    /** [IptvClient] stream-URL resolution — Xtream derives it by formula (always succeeds). */
    override suspend fun resolveStreamUrl(acc: XtreamAccount, kind: String, streamId: Int): String =
        buildStreamUrl(acc, kind, streamId)

    /**
     * Catch-up (tv_archive) replay URL — XUI's standard timeshift path form.
     * ponytail: start is UTC-derived; panels technically interpret it in the SERVER's
     * timezone, so a mis-set panel replays offset — reading server_info.timezone and
     * shifting is the upgrade path if providers surface that in practice.
     */
    fun liveTimeshiftUrl(acc: XtreamAccount, streamId: Int, startEpochMs: Long, durationMinutes: Int): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val start = fmt.format(java.util.Date(startEpochMs))
        return (acc.baseUrl.toHttpUrlOrNull() ?: error("Invalid server URL"))
            .newBuilder()
            .addPathSegment("timeshift")
            .addPathSegment(acc.username)
            .addPathSegment(acc.password)
            .addPathSegment(durationMinutes.toString())
            .addPathSegment(start)
            .addPathSegment("$streamId.ts")
            .build().toString()
    }

    private fun streamUrl(acc: XtreamAccount, kind: String, id: Int, ext: String): String =
        (acc.baseUrl.toHttpUrlOrNull() ?: error("Invalid server URL"))
            .newBuilder()
            .addPathSegment(kind)
            .addPathSegment(acc.username)
            .addPathSegment(acc.password)
            .addPathSegment("$id.$ext")
            .build().toString()

    private suspend fun categories(acc: XtreamAccount, action: String): Result<List<XtreamCategory>> = call {
        apiFor(acc).getCategories(playerApi(acc, action)).requireBody().mapNotNull { dto ->
            val id = dto.categoryId ?: return@mapNotNull null
            XtreamCategory(id, dto.categoryName.orEmpty())
        }
    }

    private fun String.toHttpUrl(): HttpUrl = toHttpUrlOrNull() ?: error("Invalid URL")

    private inline fun <T> call(block: () -> T): Result<T> =
        runCatching { block() }

    private fun <T> Response<T>.requireBody(): T {
        if (!isSuccessful) error("HTTP ${code()}: ${message()}")
        return body() ?: error("Empty response")
    }
}

internal fun XtreamEpgEntryDto.toProgram(): XtreamProgram = XtreamProgram(
    title = decodeXtreamBase64(title),
    description = decodeXtreamBase64(description),
    startMs = (startTimestamp?.toLongOrNull() ?: 0L) * 1000,
    endMs = (stopTimestamp?.toLongOrNull() ?: 0L) * 1000,
    nowPlaying = nowPlaying == 1
)

/** Xtream base64-encodes EPG title/description. Returns "" on null/garbage rather than throwing. */
internal fun decodeXtreamBase64(s: String?): String {
    if (s.isNullOrBlank()) return ""
    return runCatching { String(Base64.getDecoder().decode(s.trim()), Charsets.UTF_8) }.getOrDefault(s)
}
