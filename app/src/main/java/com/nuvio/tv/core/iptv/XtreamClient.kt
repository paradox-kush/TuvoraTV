package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.dns.PlaylistDns
import com.nuvio.tv.core.iptv.match.IndexedItem
import com.nuvio.tv.core.iptv.match.XtreamCatalogIndexParser
import com.nuvio.tv.data.remote.api.XtreamApi
import com.nuvio.tv.data.remote.dto.XtreamEpgEntryDto
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Request
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okio.buffer
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
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
    /**
     * Optional per-playlist stream User-Agent for Xtream/Stalker sources (M3U URL/file playlists
     * keep theirs in [username]). Null = send the engine default. Resolved for playback by
     * [StreamUserAgentPolicy]; a shared option (not part of [sameConnectionAs]) so it can be edited
     * to un-block a provider whose WAF is refusing the default UA without a re-verify. Additive +
     * defaulted so previously-persisted JSON loads unchanged (see XtreamAccountStore's normalizer).
     */
    val userAgent: String? = null,
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
    val fileName: String? = null,
    // --- Stalker portal (MAG/Ministra) source fields (sourceType = SOURCE_STALKER). ------------
    // All additive + defaulted so previously-persisted JSON loads unchanged (see the Gson decode
    // defaults in XtreamAccountStore). For Stalker: [baseUrl]/[username]/[password] stay empty and
    // these carry the config instead — [portalUrl] is the base portal (e.g. http://host:port),
    // [macAddress] is the virtual STB MAC. Serial/Device ID override the MAC-derived values.
    val portalUrl: String = "",
    val macAddress: String = "",
    val stalkerUsername: String = "",
    val stalkerPassword: String = "",
    val serialNumber: String = "",
    val deviceId: String = "",
    /** Send the derived (or overridden) device signature on get_profile. Default on. */
    val sendDeviceId: Boolean = true,
    // F46 — the rest of a MAG box's identity, for portals provisioned against a real box or another
    // STB app. Blank = today's behaviour (device_id2 == device_id, derived signature, preset model/hw).
    val deviceId2: String = "",
    val signature: String = "",
    /** STB model sent as stb_type + in X-User-Agent, e.g. MAG254. */
    val stbModel: String = "",
    val hwVersion: String = "",
    // --- Catch-up (tv_archive replay) preferences, per playlist. -------------------------------
    /**
     * Ask the panel for `.m3u8` catch-up first instead of `.ts`.
     *
     * TS is the default because it is the more dependable answer in the field (iptvnator's order,
     * arrived at the hard way), but only m3u8 carries per-segment durations, so only m3u8 scrubs.
     * The preference is the escape hatch rather than a guess: flipping it reorders the dialect
     * ladder and a failing m3u8 still walks back to TS.
     */
    val preferM3u8CatchUp: Boolean = false,
    /**
     * Manual catch-up time correction in minutes, −720..+840 (−12 h..+14 h).
     *
     * Panels interpret a replay's `start` in THEIR OWN timezone and some of them lie about which
     * that is; every mature player ships this escape hatch (iptvsimple's `catchup-correction`,
     * TiviMate's per-playlist EPG offset, XUI's server-side `epg_shift`). 0 = send UTC, which is
     * what Tuvora has always sent, so the default path is byte-identical to today.
     */
    val catchUpCorrectionMinutes: Int = 0,
    /**
     * Manual GUIDE EPG offset in minutes, −720..+840, added to every EPG epoch at the parse
     * boundary. 0 = auto: [XtreamEpochSkew] detects wall-clock-epoch liar panels per response and
     * subtracts the measured clock-pair offset; a non-zero value overrides that vote entirely.
     * Deliberately separate from [catchUpCorrectionMinutes] — that one shifts the `start` STRING
     * sent to the panel for a replay (a different lie with a different fix); this one shifts what
     * the guide believes about when programmes air.
     */
    val guideEpgCorrectionMinutes: Int = 0,
    /**
     * F10 — show channel names cleaned of country prefixes, quality tags and decorations
     * ([com.nuvio.tv.core.epg.ChannelNameCleaner]) on this device. Display only and opt-in; the
     * guide matcher always cleans. Device-local like the catch-up prefs (not on the wire).
     */
    val cleanChannelNames: Boolean = false,
    /** F10 — extra tags the user wants stripped (comma / newline separated). Matching + display. */
    val channelNameTags: String? = null,
    /**
     * Step 0 — the playlist's alternate server addresses in failover order (max 5); null = none.
     * Client-owned and synced (`iptv_playlists.backup_urls`); this build only round-trips it (no UI
     * yet). Nullable rather than an empty-list default because Gson (Unsafe, no constructor) decodes
     * every JSON written before this field existed — accounts and pending-op logs alike — with null.
     */
    val backupUrls: List<String>? = null
) {
    /**
     * The manual correction alone, in ms; null = unset. NOT what a replay sends any more: since B117
     * the start is [XtreamCatchUp.replayOffsetMs] = the panel's measured clock + this correction.
     */
    val catchUpOffsetMs: Long? get() = catchUpCorrectionMinutes.takeIf { it != 0 }?.let { it * 60_000L }

    /** F10 — the name clean-up rules this playlist's matcher uses: the defaults plus the user's tags. */
    fun channelNameRules(): com.nuvio.tv.core.epg.ChannelNameCleaner.Rules =
        com.nuvio.tv.core.epg.ChannelNameCleaner.Rules(
            userTags = com.nuvio.tv.core.epg.ChannelNameCleaner.parseTags(channelNameTags),
        )

    /** F10 — the name to SHOW for a channel of this playlist: cleaned when the user opted in. */
    fun displayChannelName(raw: String): String =
        if (cleanChannelNames) com.nuvio.tv.core.epg.ChannelNameCleaner.clean(raw, channelNameRules()) else raw

    /** The manual guide offset in milliseconds; null = auto-detect (the default). */
    val guideEpgOffsetMs: Long? get() = guideEpgCorrectionMinutes.takeIf { it != 0 }?.let { it * 60_000L }

    fun typeEnabled(type: String): Boolean = type in contentTypes

    /**
     * True when [other] is reached over exactly the same connection as this account: same source
     * type, same host/URL, same credentials, same STB identity.
     *
     * An edit that leaves this true changed only the shared options (name, EPG url, DNS resolver,
     * refresh interval), so it must NOT be gated behind a live verify. Gating it created a
     * catch-22: a playlist whose provider is currently unreachable could not be edited at all —
     * including to change the DNS resolver, which is one of the few settings that can *fix* an
     * unreachable provider (a filtered/broken system resolver on the device).
     */
    fun sameConnectionAs(other: XtreamAccount): Boolean =
        sourceType == other.sourceType &&
            baseUrl == other.baseUrl &&
            username == other.username &&
            password == other.password &&
            portalUrl == other.portalUrl &&
            macAddress == other.macAddress &&
            stalkerUsername == other.stalkerUsername &&
            stalkerPassword == other.stalkerPassword &&
            serialNumber == other.serialNumber &&
            deviceId == other.deviceId &&
            sendDeviceId == other.sendDeviceId &&
            deviceId2 == other.deviceId2 &&
            signature == other.signature &&
            stbModel == other.stbModel &&
            hwVersion == other.hwVersion

    /**
     * Category filter: null selection = all (incl. future); empty = none; list = only those ids. B64
     * transition: a selection written before B64 names M3U categories by their raw group NAME (TV's old
     * category id), so where the caller knows the category's [categoryName] it matches too.
     */
    fun allowsCategory(type: String, categoryId: String?, categoryName: String? = null): Boolean {
        val selection = categorySelections.forType(type) ?: return true
        return (categoryId != null && categoryId in selection) || (categoryName != null && categoryName in selection)
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

        // Catch-up time correction: iptvsimple's range, and the one every mature player ships.
        const val CATCHUP_CORRECTION_MIN_MINUTES = -12 * 60
        const val CATCHUP_CORRECTION_MAX_MINUTES = 14 * 60

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
    val maxConnections: Int?,
    /** Free-text expiry when the source gives one instead of an epoch (Stalker's `phone` field). */
    val expiresText: String? = null
)

// --- Domain models (what the UI consumes) -----------------------------------

data class XtreamChannel(
    val streamId: Int,
    val name: String,
    val logo: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val hasArchive: Boolean,
    /** Days of catch-up available; 0 = the panel did not say (see [XtreamCatchUp.isWithinWindow]). */
    val catchUpDays: Int = 0,
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
    val nowPlaying: Boolean,
    /**
     * Per-programme `has_archive`: true/false = the panel spoke, null = it said nothing (every
     * get_short_epg row). Feeds [XtreamCatchUp.actionFor]'s positive override.
     */
    val hasArchive: Boolean? = null
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
    /**
     * Base client the per-provider DoH clients are derived from. Must be the permissive addon
     * client: Xtream panels routinely have self-signed certs, and the non-DoH [api] (built on the
     * placeholder Retrofit) is permissive too — keep both Xtream lanes on the same TLS policy.
     */
    @param:Named("addonPermissive") private val baseClient: OkHttpClient,
    private val moshi: Moshi,
    private val playlistDns: PlaylistDns,
    /** Step 0.3: walks the playlist's backup servers for fail-over-able calls; knows the ACTIVE one. */
    private val failover: PlaylistServerFailover,
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
    /** The OkHttp client [acc]'s panel requests ride: [baseClient], through the playlist's DoH resolver if it uses one. */
    private fun clientFor(acc: XtreamAccount): OkHttpClient = playlistDns.clientFor(baseClient, acc.dnsProvider)

    /**
     * The [XtreamApi] for one request. Outside a failover race this is [apiFor] unchanged. Inside one
     * (Step 0.3b) it is a Retrofit on [failoverCallFactory] — the 8 s failover connect timeout, OkHttp's
     * dispatcher queue reported as a local wait, and, for the racing real request, the response-headers
     * signal — so a loser is parked at its
     * headers and cancelled before Retrofit reads a byte of its body. Retrofit's suspend call cancels
     * the OkHttp call when the coroutine is cancelled (`awaitResponse` → `invokeOnCancellation { cancel() }`).
     */
    private suspend fun apiForRequest(acc: XtreamAccount): XtreamApi {
        val signal = coroutineContext[HttpAttemptSignal] ?: return apiFor(acc)
        return Retrofit.Builder()
            .baseUrl("https://placeholder.nuvio.tv/")
            .callFactory(clientFor(acc).failoverCallFactory(signal))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(XtreamApi::class.java)
    }

    /**
     * The failover race's validation probe (Step 0.3b): the no-action `player_api.php` login on server [a].
     * Returns normally only for a VALID answer — JSON with `user_info` and `server_info` and auth=1 on a
     * live account. A 200 that is not the panel (parked domain, CDN error page, blank body) throws
     * [FailoverInvalidResponseException] (fails over); auth=0 / Expired / Banned / Disabled throws
     * [FailoverAuthRejectedException] (the same on every server: surfaced, never failed over). Goes
     * through the same client (UA, DoH, breaker) as every panel request; read on IO, at most
     * [PROBE_MAX_BYTES], and the call is cancelled with the attempt.
     */
    internal suspend fun failoverProbe(a: XtreamAccount) {
        val body = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(playerApi(a)).build()
            clientFor(a).forFailoverAttempt().newCall(request).executeCancellable { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code}: ${resp.message}")
                readAtMost(resp.body?.source(), PROBE_MAX_BYTES)
            }
        }
        FailoverProbePolicy.toFailure(FailoverProbePolicy.xtreamLogin(body), "Xtream login probe")?.let { throw it }
    }

    /** Verifies credentials. Success only when the panel reports auth=1 and an active status. */
    suspend fun verify(acc: XtreamAccount): Result<Unit> = call {
        // The login call fails over (Step 0.3); auth=0 is an ANSWER, checked below on whichever
        // server gave it — the same on every server, so it never walks.
        val body = loginBody(acc)
        rememberClockPair(acc.id, body.serverInfo)
        val info = body.userInfo
        // Typed (UX20) so the form can tell a refused login from an unreachable server — see
        // PlaylistSaveErrorPolicy. Messages unchanged for any log/catch site.
        if (info == null || info.auth != 1) throw XtreamAuthRejectedException()
        val status = info.status?.lowercase().orEmpty()
        if (!(status.isEmpty() || status == "active")) throw XtreamAccountInactiveException(info.status)
    }

    /** Account status (expiry/connections) for the settings row. Same endpoint as [verify]. */
    override suspend fun accountInfo(acc: XtreamAccount): Result<XtreamAccountInfo> = call {
        val body = loginBody(acc)
        rememberClockPair(acc.id, body.serverInfo)
        val info = body.userInfo
        XtreamAccountInfo(
            status = info?.status?.takeIf { it.isNotBlank() },
            expiresAtEpochSec = info?.expDate?.trim()?.toLongOrNull(),
            activeConnections = info?.activeConnections?.trim()?.toIntOrNull(),
            maxConnections = info?.maxConnections?.trim()?.toIntOrNull()
        )
    }

    // --- the panel's measured clock offset (session-scoped) ------------------------------------

    /**
     * Per-account clock-pair offset for this SESSION, keyed by account id. An immutable snapshot
     * swapped whole (the [CatchUpEpgRepository]-style looseness mobile already ships): readers
     * never lock, and the guide's parallel short-EPG calls at most duplicate one tiny fetch.
     * Attempted-but-junk is remembered as null so a panel with no usable pair is asked once, not
     * once per channel.
     */
    @Volatile
    private var measuredClockOffsets: Map<String, Long?> = emptyMap()
    private val clockFetchGate = Mutex()

    /** Folds one `server_info` sighting into the session memo — verify/accountInfo ride free. */
    private fun rememberClockPair(accountId: String, serverInfo: com.nuvio.tv.data.remote.dto.XtreamServerInfoDto?) {
        val offset = ServerClockOffset.offsetMs(serverInfo?.timeNow, serverInfo?.timestampNow ?: 0L)
        measuredClockOffsets = measuredClockOffsets + (accountId to offset)
    }

    /**
     * The panel's measured clock-pair offset ([ServerClockOffset]), fetched AT MOST once per
     * account per session and usually never: the add/edit verify and the settings account row both
     * seed the memo from responses they already make, and the guide only asks at all once a
     * response has voted LIAR. Null = the panel's own clocks are junk (nothing to subtract).
     */
    suspend fun measuredClockOffsetMs(acc: XtreamAccount): Long? {
        measuredClockOffsets[acc.id]?.let { return it }
        if (acc.id in measuredClockOffsets) return null   // attempted, junk
        clockFetchGate.withLock {
            if (acc.id in measuredClockOffsets) return measuredClockOffsets[acc.id]
            val serverInfo = runCatching { loginBody(acc).serverInfo }.getOrNull()
            rememberClockPair(acc.id, serverInfo)
            return measuredClockOffsets[acc.id]
        }
    }

    override suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "get_live_categories")

    override suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "get_vod_categories")

    override suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>> =
        categories(acc, "get_series_categories")

    override suspend fun liveChannels(acc: XtreamAccount, categoryId: String?): Result<List<XtreamChannel>> = call {
        // Fails over (Step 0.3); the stream URLs are built on the server that served THIS list.
        panel(acc, { api, a -> api.getLiveStreams(playerApi(a, "get_live_streams", categoryId)) }) { a, body -> body.mapNotNull { dto ->
            val id = dto.streamId ?: return@mapNotNull null
            XtreamChannel(
                streamId = id,
                name = dto.name.orEmpty(),
                logo = dto.streamIcon?.takeIf { it.isNotBlank() },
                epgChannelId = dto.epgChannelId?.takeIf { it.isNotBlank() },
                categoryId = dto.categoryId,
                hasArchive = (dto.tvArchive ?: 0) > 0,
                catchUpDays = (dto.tvArchiveDuration ?: 0).coerceAtLeast(0),
                streamUrl = streamUrl(a, "live", id, "ts")
            )
        } }
    }

    override suspend fun vodMovies(acc: XtreamAccount, categoryId: String?): Result<List<XtreamMovie>> = call {
        panel(acc, { api, a -> api.getVodStreams(playerApi(a, "get_vod_streams", categoryId)) }) { a, body -> body.mapNotNull { dto ->
            val id = dto.streamId ?: return@mapNotNull null
            XtreamMovie(
                streamId = id,
                name = dto.name.orEmpty(),
                poster = dto.streamIcon?.takeIf { it.isNotBlank() },
                categoryId = dto.categoryId,
                rating = dto.rating,
                streamUrl = streamUrl(a, "movie", id, dto.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"),
                tmdb = dto.tmdb?.takeIf { it > 0 },
                containerExtension = dto.containerExtension?.takeIf { it.isNotBlank() }
            )
        } }
    }

    override suspend fun series(acc: XtreamAccount, categoryId: String?): Result<List<XtreamSeriesItem>> = call {
        panel(acc, { api, a -> api.getSeries(playerApi(a, "get_series", categoryId)) }) { _, body -> body.mapNotNull { dto ->
            val id = dto.seriesId ?: return@mapNotNull null
            XtreamSeriesItem(
                id, dto.name.orEmpty(), dto.cover?.takeIf { it.isNotBlank() }, dto.categoryId, dto.plot, dto.rating,
                tmdb = dto.tmdb?.takeIf { it > 0 },
                year = (dto.releaseDate ?: dto.releaseDateAlt)?.trim()?.take(4)?.toIntOrNull()
            )
        } }
    }

    /**
     * Catalog reduced to match-index rows, decoded a title at a time.
     *
     * [vodMovies]/[series] stay as they are for the browse screens, which need the full model.
     * The index only needs six fields, and going through the full model cost two extra
     * whole-catalog copies in heap — including a stream URL built per item that the index
     * doesn't even store. That peak is what was getting the app lowmemorykilled on TV sticks
     * right after a playlist was added.
     */
    suspend fun vodIndexItems(acc: XtreamAccount): Result<List<IndexedItem>> = call {
        // A fresh parse per server, so a body that died part-way is safely fetched whole elsewhere.
        panel(acc, { api, a -> api.getRawCatalog(playerApi(a, "get_vod_streams")) }) { _, body ->
            body.use { XtreamCatalogIndexParser.parseVod(it.source()) }
        }
    }

    /** Series half of [vodIndexItems]. */
    suspend fun seriesIndexItems(acc: XtreamAccount): Result<List<IndexedItem>> = call {
        panel(acc, { api, a -> api.getRawCatalog(playerApi(a, "get_series")) }) { _, body ->
            body.use { XtreamCatalogIndexParser.parseSeries(it.source()) }
        }
    }

    /**
     * [vodIndexItems], streamed: rows go to [onItem] as they parse and are then garbage — the
     * catalog never exists in heap as one list. Returns the delivered count; throws (like the
     * list variant) on a truncated body, so a partial catalog can't finalize a sync.
     */
    suspend fun vodIndexItemsInto(acc: XtreamAccount, onItem: (IndexedItem) -> Unit): Result<Int> = call {
        streamedPanel(acc, { api, a -> api.getRawCatalog(playerApi(a, "get_vod_streams")) }) { source ->
            XtreamCatalogIndexParser.parseVodInto(source, onItem)
        }
    }

    /** Series half of [vodIndexItemsInto]. */
    suspend fun seriesIndexItemsInto(acc: XtreamAccount, onItem: (IndexedItem) -> Unit): Result<Int> = call {
        streamedPanel(acc, { api, a -> api.getRawCatalog(playerApi(a, "get_series")) }) { source ->
            XtreamCatalogIndexParser.parseSeriesInto(source, onItem)
        }
    }

    /** Live half of [vodIndexItemsInto] (P7: the index doubles as the browse catalog). */
    suspend fun liveIndexItemsInto(acc: XtreamAccount, onItem: (IndexedItem) -> Unit): Result<Int> = call {
        streamedPanel(acc, { api, a -> api.getRawCatalog(playerApi(a, "get_live_streams")) }) { source ->
            XtreamCatalogIndexParser.parseLiveInto(source, onItem)
        }
    }

    /** Now + next few programs for a channel (cheap, one call). Full XMLTV grid is the upgrade path. */
    override suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int): Result<List<XtreamProgram>> = call {
        val listings = panel(acc, { api, a ->
            api.getShortEpg(
                playerApi(a, "get_short_epg").toHttpUrl().newBuilder()
                    .addQueryParameter("stream_id", streamId.toString())
                    .addQueryParameter("limit", limit.toString())
                    .build().toString()
            )
        }) { _, body -> body.listings.orEmpty() }
        correctShortEpgListings(
            listings,
            manualOffsetMs = acc.guideEpgOffsetMs,
        ) { measuredClockOffsetMs(acc) }
    }

    /**
     * A channel's FULL guide — past programmes included, each with the panel's own `has_archive`
     * mark. `get_short_epg` answers now+next and cannot power a replay strip, so this is the call
     * that makes catch-up's "days back" real.
     *
     * Streamed into [sink] a row at a time and filtered to the parse window as it goes, so a panel
     * that keeps a month costs the heap a week and no more. Returns how many rows were kept.
     */
    suspend fun historicalEpgInto(
        acc: XtreamAccount,
        streamId: Int,
        channelId: String,
        nowMs: Long,
        catchUpDays: Int,
        sink: (com.nuvio.tv.core.iptv.content.EpgProgramme) -> Unit,
    ): Result<Int> = call {
        // The stream parse can't suspend mid-body, so the clock pair is resolved up front when
        // auto-detection could need it (manual unset). Session-memoized — usually already seeded
        // by verify/accountInfo or a liar short-EPG response, so this is normally free.
        val manualOffsetMs = acc.guideEpgOffsetMs
        val clockPairOffsetMs = if (manualOffsetMs == null) measuredClockOffsetMs(acc) else null
        // Fails over (Step 0.3) only until the first body bytes reached the parser — rows already
        // handed to [sink] must never be spliced with another server's.
        streamedPanel(acc, { api, a ->
            api.getRawEpgTable(
                playerApi(a, "get_simple_data_table").toHttpUrl().newBuilder()
                    .addQueryParameter("stream_id", streamId.toString())
                    .build().toString()
            )
        }) { source ->
            XtreamSimpleDataTable.parseInto(
                source, channelId, nowMs, catchUpDays, manualOffsetMs, clockPairOffsetMs, sink,
            )
        }
    }

    /** Full episode list (across seasons) for a series, each with its built stream URL. */
    override suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail> = call {
        // Fails over (Step 0.3); episode URLs are built on the server that served the list.
        val (served, resp) = panel(acc, { api, a -> api.getSeriesInfo(seriesInfoUrl(a, seriesId)) }) { a, body -> a to body }
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
                    streamUrl = seriesEpisodeUrl(served, epId, ext)
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
        vodInfo(acc, vodId).info?.tmdbId?.takeIf { it > 0 }
    }

    /**
     * Artwork for one VOD item from get_vod_info — the lazy fallback for panels whose bulk
     * list ships empty stream_icons. null = the panel has no art for it either.
     */
    suspend fun vodArtwork(acc: XtreamAccount, vodId: Int): Result<String?> = call {
        val info = vodInfo(acc, vodId).info
        info?.movieImage?.takeIf { it.isNotBlank() } ?: info?.coverBig?.takeIf { it.isNotBlank() }
    }

    /** Series half of [vodArtwork] (get_series_info `info.cover`). */
    suspend fun seriesArtwork(acc: XtreamAccount, seriesId: Int): Result<String?> = call {
        panel(acc, { api, a -> api.getSeriesInfo(seriesInfoUrl(a, seriesId)) }) { _, body -> body }
            .info?.cover?.takeIf { it.isNotBlank() }
    }

    /** What get_vod_info can confirm about a candidate during TMDB->stream matching. */
    suspend fun vodMatchSignal(acc: XtreamAccount, vodId: Int): Result<XtreamVodSignal> = call {
        val info = vodInfo(acc, vodId).info
        XtreamVodSignal(
            tmdbId = info?.tmdbId?.takeIf { it > 0 },
            year = (info?.releaseDate ?: info?.releaseDateAlt)?.trim()?.take(4)?.toIntOrNull()
        )
    }

    // --- Step 0.3: fail-over-able panel requests ---------------------------------

    /** The no-action player_api call (the login/account call) — fails over to a backup server. */
    private suspend fun loginBody(acc: XtreamAccount) =
        panel(acc, { api, a -> api.getAccount(playerApi(a)) }) { _, body -> body }

    private suspend fun vodInfo(acc: XtreamAccount, vodId: Int) =
        panel(acc, { api, a ->
            api.getVodInfo(
                playerApi(a, "get_vod_info").toHttpUrl().newBuilder()
                    .addQueryParameter("vod_id", vodId.toString())
                    .build().toString()
            )
        }) { _, body -> body }

    private fun seriesInfoUrl(a: XtreamAccount, seriesId: Int): String =
        playerApi(a, "get_series_info").toHttpUrl().newBuilder()
            .addQueryParameter("series_id", seriesId.toString())
            .build().toString()

    /**
     * One whole-body fail-over-able panel request (Step 0.3): [fetch] rebuilds the URL for each server
     * the [PlaylistServerFailover] walk tries (same path on that server), and [map] gets the server
     * that answered, so URLs built from the body point at it. A body that dies part-way is simply
     * fetched whole from the next server — nothing has been handed to anyone yet.
     */
    private suspend fun <R, T> panel(
        acc: XtreamAccount,
        fetch: suspend (XtreamApi, XtreamAccount) -> Response<R>,
        map: (XtreamAccount, R) -> T,
    ): T = failover.run(acc, evidence = { it.second }, probe = ::failoverProbe) { a ->
        val response = fetch(apiForRequest(a), a)
        map(a, response.requireBody()) to response.servedFrom()
    }.first

    /**
     * [panel] for a body streamed into a caller's sink: fails over only until the first body bytes
     * reached [parse] — rows already in the sink can't be taken back, so a body that dies part-way
     * surfaces as the failure it is instead of splicing two servers' rows.
     *
     * A stale stand-in for a dead host ([ServedFrom.STALE_FALLBACK]) is held UNPARSED while the walk
     * tries the other servers: parsing it first would put its rows in the sink and end the walk, so
     * last week's lineup would win over a backup that answers live. It is parsed only if it ends up
     * the last resort; every held copy is closed either way.
     */
    private suspend fun <T> streamedPanel(
        acc: XtreamAccount,
        fetch: suspend (XtreamApi, XtreamAccount) -> Response<okhttp3.ResponseBody>,
        parse: (okio.BufferedSource) -> T,
    ): T {
        var delivered = false
        val held = mutableListOf<okhttp3.ResponseBody>()
        try {
            val outcome = failover.run(acc, canRetry = { !delivered }, evidence = { it.second }, probe = ::failoverProbe) { a ->
                val response = fetch(apiForRequest(a), a)
                val servedFrom = response.servedFrom()
                if (servedFrom == ServedFrom.STALE_FALLBACK) {
                    StreamedAttempt.Stale(response.requireBody().also { held += it }) to servedFrom
                } else {
                    val parsed = response.requireBody().use { body ->
                        val flagged = object : okio.ForwardingSource(body.source()) {
                            override fun read(sink: okio.Buffer, byteCount: Long): Long =
                                super.read(sink, byteCount).also { if (it > 0) delivered = true }
                        }
                        parse(flagged.buffer())
                    }
                    StreamedAttempt.Parsed(parsed) to servedFrom
                }
            }.first
            return when (outcome) {
                is StreamedAttempt.Parsed -> outcome.value
                is StreamedAttempt.Stale -> outcome.body.use { parse(it.source()) }
            }
        } finally {
            held.forEach { runCatching { it.close() } }
        }
    }

    /** One [streamedPanel] attempt: rows already parsed into the sink, or a stale body not yet read. */
    private sealed interface StreamedAttempt<out T> {
        class Parsed<T>(val value: T) : StreamedAttempt<T>
        class Stale(val body: okhttp3.ResponseBody) : StreamedAttempt<Nothing>
    }

    /** Where OkHttp got this answer from (see [ServedFrom]): stale fallback, plain cache hit, or the host. */
    private fun Response<*>.servedFrom(): ServedFrom {
        val raw = raw()
        return when {
            raw.header(STALE_FALLBACK_HEADER) != null -> ServedFrom.STALE_FALLBACK
            raw.networkResponse == null && raw.cacheResponse != null -> ServedFrom.CACHE
            else -> ServedFrom.NETWORK
        }
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
        // Built on the playlist's ACTIVE server (Step 0.3) and never failing over itself: a playback
        // failure says nothing about which server should serve the catalog.
        streamUrl(failover.activeAccount(acc), kind, id, if (kind == "live") "ts" else ext)

    /** [IptvClient] stream-URL resolution — Xtream derives it by formula (always succeeds;
     *  [forceFresh] is meaningless for a stable formula URL). */
    override suspend fun resolveStreamUrl(acc: XtreamAccount, kind: String, streamId: Int, forceFresh: Boolean): String =
        buildStreamUrl(acc, kind, streamId)

    /**
     * Catch-up (tv_archive) replay URL — XUI's standard timeshift path form.
     * ponytail: start is UTC-derived; panels technically interpret it in the SERVER's
     * timezone, so a mis-set panel replays offset — reading server_info.timezone and
     * shifting is the upgrade path if providers surface that in practice.
     */
    fun liveTimeshiftUrl(acc: XtreamAccount, streamId: Int, startEpochMs: Long, durationMinutes: Int): String =
        // Empty only for blank credentials, which candidateUrls refuses to build garbage for —
        // callers are Xtream-gated so it doesn't happen, but a crash would be the worst answer.
        liveTimeshiftUrls(acc, streamId, startEpochMs, durationMinutes).firstOrNull().orEmpty()

    /**
     * Every catch-up URL worth trying for this channel, best-known first — panels disagree about
     * the shape and none advertise which they speak, so the caller walks the list until one plays.
     * The first entry is the form Tuvora has always sent.
     */
    fun liveTimeshiftUrls(
        acc: XtreamAccount,
        streamId: Int,
        startEpochMs: Long,
        durationMinutes: Int,
        containerExtension: String? = null,
    ): List<String> = XtreamCatchUp.candidateUrls(
        // Catch-up never fails over itself — it replays from the playlist's ACTIVE server (Step 0.3).
        baseUrl = failover.activeAccount(acc).baseUrl,
        username = acc.username,
        password = acc.password,
        streamId = streamId,
        startMs = startEpochMs,
        endMs = startEpochMs + durationMinutes * 60_000L,
        containerExtension = containerExtension,
    )

    private fun streamUrl(acc: XtreamAccount, kind: String, id: Int, ext: String): String =
        (acc.baseUrl.toHttpUrlOrNull() ?: error("Invalid server URL"))
            .newBuilder()
            .addPathSegment(kind)
            .addPathSegment(acc.username)
            .addPathSegment(acc.password)
            .addPathSegment("$id.$ext")
            .build().toString()

    private suspend fun categories(acc: XtreamAccount, action: String): Result<List<XtreamCategory>> = call {
        panel(acc, { api, a -> api.getCategories(playerApi(a, action)) }) { _, body ->
            body.mapNotNull { dto ->
                val id = dto.categoryId ?: return@mapNotNull null
                XtreamCategory(id, dto.categoryName.orEmpty())
            }
        }
    }

    private fun String.toHttpUrl(): HttpUrl = toHttpUrlOrNull() ?: error("Invalid URL")

    private inline fun <T> call(block: () -> T): Result<T> =
        runCatching { block() }

    private fun <T> Response<T>.requireBody(): T {
        // Typed so a provider WAF (403/429) is distinguishable from a sick panel — see
        // IptvLoadFailurePolicy. Message unchanged, so every existing catch site behaves the same.
        if (!isSuccessful) throw HttpStatusException(code(), "HTTP ${code()}: ${message()}")
        return body() ?: error("Empty response")
    }
}

/**
 * Response header [com.nuvio.tv.core.di.XtreamCatalogFallbackInterceptor] stamps on a stale cached
 * copy it served because the panel FAILED — so the failover walk (Step 0.3) can tell "the host is
 * down, here is last week's list" from a live answer.
 */
internal const val STALE_FALLBACK_HEADER = "X-Tuvora-Stale-Fallback"

/** A login body is a few hundred bytes; anything past this is not one (and is never buffered whole). */
private const val PROBE_MAX_BYTES = 64L * 1024

internal fun XtreamEpgEntryDto.toProgram(offsetMs: Long = 0L): XtreamProgram = XtreamProgram(
    title = decodeXtreamBase64(title),
    description = decodeXtreamBase64(description),
    // The epoch-skew correction shifts only REAL epochs: an absent timestamp parses to 0, and a
    // "corrected" 0 would be negative garbage that actionFor could no longer recognise as absent.
    startMs = (startTimestamp?.toLongOrNull() ?: 0L).let { if (it > 0) it * 1000 + offsetMs else it * 1000 },
    endMs = (stopTimestamp?.toLongOrNull() ?: 0L).let { if (it > 0) it * 1000 + offsetMs else it * 1000 },
    nowPlaying = nowPlaying == 1,
    // FlexInt already coerced "1"/true; any positive count is a mark, junk decoded to null stays
    // null — silence, not "no".
    hasArchive = hasArchive?.let { it > 0 }
)

/**
 * One short-EPG response through the epoch-skew gate ([XtreamEpochSkew]): vote the liar equality
 * across the rows, resolve the offset, map. The clock pair is fetched ONLY when a response has
 * actually voted LIAR and no manual offset preempts it — honest panels (the population) never pay
 * a request or a changed byte for the lie, which is what the onnipsite probe demands.
 */
internal suspend fun correctShortEpgListings(
    listings: List<XtreamEpgEntryDto>,
    manualOffsetMs: Long?,
    measuredClockOffsetMs: suspend () -> Long?,
): List<XtreamProgram> {
    val offsetMs = when {
        manualOffsetMs != null -> manualOffsetMs
        XtreamEpochSkew.verdictOf(
            listings.map { it.start to it.startTimestamp?.trim()?.toLongOrNull() }
        ) == XtreamEpochSkew.Verdict.LIAR ->
            XtreamEpochSkew.effectiveOffsetMs(null, XtreamEpochSkew.Verdict.LIAR, measuredClockOffsetMs())
        else -> 0L
    }
    return listings.map { it.toProgram(offsetMs) }
}

/** Xtream base64-encodes EPG title/description. Returns "" on null/garbage rather than throwing. */
internal fun decodeXtreamBase64(s: String?): String {
    // Delegates to the hardened decoder: a short plain title ("News") is valid base64 by accident
    // and a blind decode ships mojibake — accepted only when base64-shaped AND readable came out.
    if (s.isNullOrBlank()) return ""
    return XtreamSimpleDataTable.decodeText(s)
}
