package com.nuvio.tv.core.mediaserver.client

import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaSourceDto
import com.nuvio.tv.core.mediaserver.client.mediabrowser.UserDto
import com.nuvio.tv.core.mediaserver.policy.MatchLookupPolicy

/** `GET /System/Info/Public`, reduced to what Tuvora keeps. */
internal data class ServerInfo(
    /** The server's own `Id` - the identity every key and content id embeds. */
    val machineId: String,
    val name: String,
    val version: String?,
    /** Detected from the body when it carries a signal; null = keep the product the user picked. */
    val detectedType: com.nuvio.tv.core.mediaserver.api.MediaServerType?,
)

/** A successful sign-in. [accessToken] goes to the secure store, never anywhere else. */
internal data class AuthSession(
    val accessToken: String,
    val userId: String,
    val userName: String?,
    val serverId: String?,
    val isAdministrator: Boolean,
)

/** A Quick Connect request: the [code] is shown (and approved elsewhere); the [secret] is the polling/exchange handle. */
internal data class QuickConnectRequest(val code: String, val secret: String)

internal data class PublicUser(val id: String, val name: String)

internal data class ItemsPage(val items: List<ItemDto>, val totalCount: Int?, val startIndex: Int)

/** The shelves a Home refresh asked for (Tuvora's own rows are never here - only the server's). */
internal data class HomeShelves(
    val continueWatching: List<ItemDto> = emptyList(),
    val nextUp: List<ItemDto> = emptyList(),
    val recentlyAdded: List<ItemDto> = emptyList(),
)

internal data class ItemsQuery(
    val parentId: String? = null,
    val includeItemTypes: List<String> = emptyList(),
    val recursive: Boolean = true,
    val searchTerm: String? = null,
    val years: List<Int> = emptyList(),
    val sortBy: String? = null,
    val sortOrder: String? = null,
    val startIndex: Int? = null,
    val limit: Int? = null,
    val fields: String? = null,
    val anyProviderIdEquals: List<String> = emptyList(),
    val collapseBoxSetItems: Boolean? = null,
    val enableTotalRecordCount: Boolean = false,
)

internal data class PlaybackInfoRequest(
    val mediaSourceId: String? = null,
    /** A user quality cap in bits/s; null = no cap (direct play uncapped). */
    val maxStreamingBitrate: Long? = null,
    val startTimeTicks: Long? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val burnSubtitles: Boolean = false,
    /**
     * Ask the server for a transcode: direct play and direct stream are disabled in the request. Needed because the
     * server only builds a `TranscodingUrl` when it decided NOT to direct play (verified live on Jellyfin 12.2 - a
     * source that direct-plays comes back with `TranscodingUrl: null` however many times it is asked).
     */
    val forceTranscode: Boolean = false,
)

internal data class PlaybackNegotiation(val sources: List<MediaSourceDto>, val playSessionId: String?)

internal enum class PlaybackReportKind { START, PROGRESS, STOPPED }

/** One `/Sessions/Playing*` report. [playMethod] is the wire value (`DirectPlay`/`DirectStream`/`Transcode`). */
internal data class PlaybackReport(
    val kind: PlaybackReportKind,
    val itemId: String,
    val mediaSourceId: String?,
    val positionTicks: Long,
    val isPaused: Boolean,
    val playMethod: String,
    val playSessionId: String?,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
)

/**
 * The authenticated half of a media server (design 5.2): everything Tuvora reads or reports, dialect
 * differences hidden behind one surface. Throws [MediaServerException]; an HTTP 401/403 anywhere means the
 * token was revoked (the signed-in state must drop to "sign in again" - an AUTHENTICATED call is the only way
 * to learn that: streams and images are anonymous).
 */
internal interface MediaServerClient {
    suspend fun me(): UserDto
    suspend fun views(): List<ItemDto>
    suspend fun items(query: ItemsQuery): ItemsPage
    suspend fun item(itemId: String, fields: String? = null): ItemDto?
    suspend fun seasons(seriesId: String): List<ItemDto>
    suspend fun episodes(seriesId: String, seasonId: String? = null, fields: String? = null): List<ItemDto>
    suspend fun search(term: String, limit: Int): List<ItemDto>
    suspend fun homeShelves(rows: Set<MediaServerHomeRow>, limit: Int, fields: String): HomeShelves
    suspend fun lookup(query: MatchLookupPolicy.Query, limit: Int = 20): List<ItemDto>
    suspend fun playbackInfo(itemId: String, request: PlaybackInfoRequest): PlaybackNegotiation
    suspend fun report(report: PlaybackReport)
    suspend fun setPlayed(itemId: String, played: Boolean)

    /**
     * "Approve from Tuvora on your phone": authorises another device's Quick Connect [code] with THIS session
     * (`POST /QuickConnect/Authorize?code=`). The other device then exchanges its own secret for its OWN token -
     * tokens are device-bound and never shared. Jellyfin only; Emby throws a 404 [MediaServerException.Http].
     */
    suspend fun authorizeQuickConnect(code: String)

    /**
     * Ends THIS device's session on the server (`POST /Sessions/Logout`): removing a server in Tuvora must not leave a
     * live token behind in the server's device list (owner decision 2026-10-06). Callers treat a failure as best-effort.
     */
    suspend fun logout()

    /**
     * Ends this device's transcode job for a finished / switched play (`DELETE /Videos/ActiveEncodings`), so no orphan
     * ffmpeg keeps running on the server (jellyfin-androidtv does the same when it changes a transcode). Best effort.
     */
    suspend fun stopEncoding(playSessionId: String) {}
}
