// Portions of this file are derived from Plezy (https://github.com/edde746/plezy), GPL-3.0,
// lib/services/jellyfin_auth_service.dart and lib/services/jellyfin_client/parts/*.dart at commit
// 05ef93c55b0a6e8a35683762c2803e1756aaed1c, ported to Kotlin. Tuvora is GPL-3.0 as well.
package com.nuvio.tv.core.mediaserver.client.mediabrowser

import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.client.AuthSession
import com.nuvio.tv.core.mediaserver.client.HomeShelves
import com.nuvio.tv.core.mediaserver.client.ItemsPage
import com.nuvio.tv.core.mediaserver.client.ItemsQuery
import com.nuvio.tv.core.mediaserver.client.MediaServerClient
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerHttp
import com.nuvio.tv.core.mediaserver.client.MediaServerRequest
import com.nuvio.tv.core.mediaserver.client.MediaServerResponse
import com.nuvio.tv.core.mediaserver.client.PlaybackInfoRequest
import com.nuvio.tv.core.mediaserver.client.PlaybackNegotiation
import com.nuvio.tv.core.mediaserver.client.PlaybackReport
import com.nuvio.tv.core.mediaserver.client.PlaybackReportKind
import com.nuvio.tv.core.mediaserver.client.PublicUser
import com.nuvio.tv.core.mediaserver.client.QuickConnectRequest
import com.nuvio.tv.core.mediaserver.client.ServerInfo
import com.nuvio.tv.core.mediaserver.policy.MatchLookupPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The unauthenticated half of a MediaBrowser-family server: probing, Quick Connect, login (design 5.3, D1).
 * Every call that precedes a session sends the TOKENLESS `MediaBrowser` header (Client/Device/DeviceId/
 * Version all required - Jellyfin throws without one). The password is used once and never kept.
 */
internal class MediaBrowserAuthApi(
    private val http: MediaServerHttp,
    baseUrl: String,
    private val dialect: MediaBrowserDialect,
    private val identity: MediaBrowserClientIdentity,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val tokenless: Map<String, String> get() = mapOf("Authorization" to MediaBrowserAuth.authorization(identity))

    /** `GET /System/Info/Public` - anonymous; also proves the address is a media server at all. */
    suspend fun publicInfo(timeoutMs: Long = MediaServerRequest.DEFAULT_TIMEOUT_MS): ServerInfo {
        val response = send("GET", "/System/Info/Public", timeoutMs = timeoutMs)
        val dto = decode<PublicSystemInfoDto>(response)
        val id = dto.id?.trim()?.takeIf { it.isNotEmpty() } ?: throw MediaServerException.Malformed("no server id in /System/Info/Public")
        return ServerInfo(
            machineId = id,
            name = dto.serverName?.takeIf { it.isNotBlank() } ?: dialect.productName,
            version = dto.version,
            detectedType = MediaBrowserDialect.detect(dto.productName, hasRemoteAddresses = dto.remoteAddresses != null)?.type,
        )
    }

    /** `GET /QuickConnect/Enabled` - anonymous, a bare JSON boolean. False for Emby without a request, and for any failure (offline looks like disabled). */
    suspend fun quickConnectEnabled(): Boolean {
        if (!dialect.supportsQuickConnect) return false
        return try {
            val response = send("GET", "/QuickConnect/Enabled", timeoutMs = 8_000)
            response.body.trim().equals("true", ignoreCase = true)
        } catch (e: MediaServerException) {
            false
        }
    }

    /** `POST /QuickConnect/Initiate` (12.x removed the GET form; a 405 from an older build retries as GET). */
    suspend fun quickConnectInitiate(): QuickConnectRequest {
        requireQuickConnect()
        val result = try {
            decode<QuickConnectResultDto>(send("POST", "/QuickConnect/Initiate"))
        } catch (e: MediaServerException.Http) {
            if (e.status != 405) throw e
            decode<QuickConnectResultDto>(send("GET", "/QuickConnect/Initiate"))
        }
        val code = result.code?.takeIf { it.isNotBlank() }
        val secret = result.secret?.takeIf { it.isNotBlank() }
        if (code == null || secret == null) throw MediaServerException.Malformed("Quick Connect answer lacks Code/Secret")
        return QuickConnectRequest(code, secret)
    }

    /**
     * One poll of `GET /QuickConnect/Connect?secret=`: true once someone approved the code. A 404 means the
     * request expired (10 minutes) or was revoked - [QuickConnectExpired], terminal. Other failures propagate
     * so the caller's loop can back off.
     */
    suspend fun quickConnectApproved(secret: String): Boolean {
        requireQuickConnect()
        val response = try {
            send("GET", "/QuickConnect/Connect", query = listOf("secret" to secret), timeoutMs = 10_000)
        } catch (e: MediaServerException.Http) {
            if (e.isNotFound) throw QuickConnectExpired()
            throw e
        }
        return decode<QuickConnectResultDto>(response).authenticated
    }

    /** `POST /Users/AuthenticateWithQuickConnect` {Secret} - exchanges an approved secret for this device's own token. */
    suspend fun authenticateWithQuickConnect(secret: String): AuthSession {
        requireQuickConnect()
        val body = buildJsonObject { put("Secret", secret) }.toString()
        return decodeAuth(send("POST", "/Users/AuthenticateWithQuickConnect", body = body))
    }

    /** `POST /Users/AuthenticateByName` {Username, Pw}. A 401/403 is a wrong name or password ([MediaServerException.Http.isUnauthorized]). */
    suspend fun authenticateByName(username: String, password: String): AuthSession {
        val body = buildJsonObject { put("Username", username); put("Pw", password) }.toString()
        return decodeAuth(send("POST", "/Users/AuthenticateByName", body = body))
    }

    /** `GET /Users/Public` - the visible users for a picker (a hidden user is typed by name). Empty on any failure. */
    suspend fun publicUsers(): List<PublicUser> = try {
        val response = send("GET", "/Users/Public", timeoutMs = 8_000)
        MediaBrowserJson.decodeFromString<List<UserDto>>(response.body)
            .mapNotNull { u -> if (u.id.isNullOrBlank() || u.name.isNullOrBlank()) null else PublicUser(u.id, u.name) }
    } catch (e: MediaServerException) {
        emptyList()
    } catch (e: SerializationException) {
        emptyList()
    }

    class QuickConnectExpired : Exception("Quick Connect request expired")

    private fun requireQuickConnect() {
        if (!dialect.supportsQuickConnect) throw MediaServerException.Http(404)
    }

    private fun decodeAuth(response: MediaServerResponse): AuthSession {
        val dto = decode<AuthenticationResultDto>(response)
        val token = dto.accessToken?.takeIf { it.isNotBlank() }
        val user = dto.user
        val userId = user?.id?.takeIf { it.isNotBlank() }
        if (token == null || userId == null) throw MediaServerException.Malformed("sign-in answer lacks AccessToken/User.Id")
        return AuthSession(token, userId, user.name, dto.serverId ?: user.serverId, user.policy?.isAdministrator == true)
    }

    private suspend fun send(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        timeoutMs: Long = MediaServerRequest.DEFAULT_TIMEOUT_MS,
    ): MediaServerResponse = sendChecked(http, base, method, path, query, tokenless, body, timeoutMs)

    private inline fun <reified T> decode(response: MediaServerResponse): T = decodeBody(response)
}

/** Shared by both halves: build the URL, run the request, turn a non-2xx into [MediaServerException.Http]. */
internal suspend fun sendChecked(
    http: MediaServerHttp,
    base: String,
    method: String,
    path: String,
    query: List<Pair<String, String>>,
    headers: Map<String, String>,
    body: String?,
    timeoutMs: Long,
): MediaServerResponse {
    val queryString = if (query.isEmpty()) "" else "?" + query.joinToString("&") { (k, v) -> "${percentEncode(k)}=${percentEncode(v)}" }
    val response = http.execute(MediaServerRequest(method, "$base$path$queryString", headers, body, timeoutMs))
    if (!response.isSuccess) throw MediaServerException.Http(response.status, response.retryAfterSeconds)
    return response
}

internal inline fun <reified T> decodeBody(response: MediaServerResponse): T = try {
    MediaBrowserJson.decodeFromString<T>(response.body)
} catch (e: SerializationException) {
    throw MediaServerException.Malformed("not a media-server answer", e)
} catch (e: IllegalArgumentException) {
    throw MediaServerException.Malformed("not a media-server answer", e)
}

/**
 * The authenticated MediaBrowser client (Jellyfin + Emby; the [dialect] table decides routes and which
 * shelf strategy applies). [token] is read per request, so a token rotated by a re-login is picked up
 * without rebuilding the client, and a signed-out client simply has none (-> a 401 from the server).
 */
internal class MediaBrowserClient(
    private val http: MediaServerHttp,
    baseUrl: String,
    private val dialect: MediaBrowserDialect,
    private val identity: MediaBrowserClientIdentity,
    private val userId: String,
    private val token: () -> String?,
    private val nowMs: () -> Long,
) : MediaServerClient {
    private val base = baseUrl.trim().trimEnd('/')
    private val paths = MediaBrowserPaths(dialect, userId)

    private fun headers(): Map<String, String> {
        val t = token()
        return if (t.isNullOrBlank()) mapOf("Authorization" to MediaBrowserAuth.authorization(identity))
        else MediaBrowserAuth.authenticatedHeaders(dialect, identity, t)
    }

    private suspend fun get(path: String, query: List<Pair<String, String>> = emptyList(), timeoutMs: Long = MediaServerRequest.DEFAULT_TIMEOUT_MS) =
        sendChecked(http, base, "GET", path, query, headers(), null, timeoutMs)

    private suspend fun post(path: String, query: List<Pair<String, String>> = emptyList(), body: JsonElement? = null, timeoutMs: Long = MediaServerRequest.DEFAULT_TIMEOUT_MS) =
        sendChecked(http, base, "POST", path, query, headers(), body?.toString(), timeoutMs)

    private suspend fun delete(path: String, query: List<Pair<String, String>> = emptyList()) =
        sendChecked(http, base, "DELETE", path, query, headers(), null, MediaServerRequest.DEFAULT_TIMEOUT_MS)

    override suspend fun me(): UserDto = decodeBody(get(paths.currentUser, timeoutMs = 10_000))

    override suspend fun views(): List<ItemDto> {
        val response = get(paths.views, listOf("userId" to userId))
        return decodeBody<ItemsEnvelopeDto>(response).items
    }

    override suspend fun items(query: ItemsQuery): ItemsPage {
        val params = buildList {
            add("userId" to userId)
            query.parentId?.let { add("ParentId" to it) }
            if (query.includeItemTypes.isNotEmpty()) add("IncludeItemTypes" to query.includeItemTypes.joinToString(","))
            add("Recursive" to query.recursive.toString())
            query.searchTerm?.let { add("SearchTerm" to it) }
            if (query.years.isNotEmpty()) add("Years" to query.years.joinToString(","))
            query.sortBy?.let { add("SortBy" to it) }
            query.sortOrder?.let { add("SortOrder" to it) }
            query.startIndex?.let { add("StartIndex" to it.toString()) }
            query.limit?.let { add("Limit" to it.toString()) }
            query.fields?.let { add("Fields" to dialect.withRowFields(it)) }
            if (query.anyProviderIdEquals.isNotEmpty()) add("AnyProviderIdEquals" to query.anyProviderIdEquals.joinToString(","))
            query.collapseBoxSetItems?.let { add("CollapseBoxSetItems" to it.toString()) }
            add("EnableTotalRecordCount" to query.enableTotalRecordCount.toString())
            add("ImageTypeLimit" to "1")
            add("EnableImageTypes" to "Primary,Backdrop,Thumb")
        }
        val envelope = decodeBody<ItemsEnvelopeDto>(get("/Items", params))
        return ItemsPage(envelope.items, envelope.totalRecordCount, envelope.startIndex ?: query.startIndex ?: 0)
    }

    override suspend fun item(itemId: String, fields: String?): ItemDto? {
        val params = buildList {
            add("userId" to userId) // Jellyfin's /Items/{id} needs it to return this user's UserData; Emby's route already carries the user
            fields?.let { add("Fields" to dialect.withRowFields(it)) }
        }
        return try {
            decodeBody<ItemDto>(get(paths.item(itemId), params, timeoutMs = if (fields.orEmpty().contains("MediaSources", ignoreCase = true)) MediaServerRequest.SLOW_RESOLVE_TIMEOUT_MS else MediaServerRequest.DEFAULT_TIMEOUT_MS)).takeIf { !it.id.isNullOrBlank() }
        } catch (e: MediaServerException.Http) {
            if (e.isNotFound) null else throw e
        }
    }

    override suspend fun seasons(seriesId: String): List<ItemDto> {
        val params = listOf("userId" to userId, "Fields" to dialect.withRowFields("Overview"))
        return decodeBody<ItemsEnvelopeDto>(get("/Shows/${MediaBrowserPaths.segment(seriesId)}/Seasons", params)).items
    }

    override suspend fun episodes(seriesId: String, seasonId: String?, fields: String?): List<ItemDto> {
        val params = buildList {
            add("userId" to userId)
            seasonId?.let { add("SeasonId" to it) }
            add("Fields" to dialect.withRowFields(fields ?: "Overview"))
        }
        return decodeBody<ItemsEnvelopeDto>(get("/Shows/${MediaBrowserPaths.segment(seriesId)}/Episodes", params)).items
    }

    override suspend fun search(term: String, limit: Int): List<ItemDto> = items(
        ItemsQuery(
            searchTerm = term, includeItemTypes = listOf("Movie", "Series"), limit = limit,
            fields = "Overview,ProviderIds", collapseBoxSetItems = false,
        ),
    ).items

    override suspend fun lookup(query: MatchLookupPolicy.Query, limit: Int): List<ItemDto> = when (query) {
        is MatchLookupPolicy.Query.ByProviderId -> items(
            ItemsQuery(includeItemTypes = listOf(query.includeItemType), anyProviderIdEquals = query.anyProviderIdEquals, limit = limit, fields = "ProviderIds,Overview"),
        ).items
        is MatchLookupPolicy.Query.ByTitle -> items(
            ItemsQuery(includeItemTypes = listOf(query.includeItemType), searchTerm = query.searchTerm, years = query.years, limit = limit, fields = "ProviderIds,Overview"),
        ).items
    }

    override suspend fun homeShelves(rows: Set<MediaServerHomeRow>, limit: Int, fields: String): HomeShelves = coroutineScope {
        val rowFields = dialect.withRowFields(fields)
        val resumeWanted = MediaServerHomeRow.CONTINUE_WATCHING in rows || (MediaServerHomeRow.NEXT_UP in rows && !dialect.supportsGlobalNextUp)
        val resume = if (resumeWanted) async {
            val params = listOf(
                "userId" to userId, "Limit" to limit.toString(), "Fields" to rowFields,
                "MediaTypes" to "Video", "Recursive" to "true", "EnableTotalRecordCount" to "false",
            )
            optionalShelf { decodeBody<ItemsEnvelopeDto>(get(paths.resumeItems, params)).items }
        } else null
        val nextUp = if (MediaServerHomeRow.NEXT_UP in rows && dialect.supportsGlobalNextUp) async {
            val params = listOf(
                "userId" to userId, "Limit" to limit.toString(), "Fields" to rowFields,
                "EnableResumable" to "false", "EnableTotalRecordCount" to "false",
                "NextUpDateCutoff" to IsoTime.format(nowMs() - NEXT_UP_WINDOW_MS),
            )
            optionalShelf { decodeBody<ItemsEnvelopeDto>(get("/Shows/NextUp", params)).items }
        } else null
        val latest = if (MediaServerHomeRow.RECENTLY_ADDED in rows) async {
            val params = listOf(
                "userId" to userId, "Limit" to limit.toString(), "Fields" to rowFields, "IncludeItemTypes" to "Movie,Series",
                "GroupItems" to "true", "ImageTypeLimit" to "1", "EnableImageTypes" to "Primary,Backdrop,Thumb",
            )
            optionalShelf { decodeBody<List<ItemDto>>(get(paths.latest, params)) }
        } else null

        val resumeItems = resume?.await().orEmpty()
        // Emby mixes one zero-position next episode per started series into the resume route: split by position.
        val (started, zeroPosition) = if (dialect.resumeReturnsOnlyStartedItems) resumeItems to emptyList()
        else resumeItems.partition { (it.userData?.playbackPositionTicks ?: 0L) > 0L }
        HomeShelves(
            continueWatching = if (MediaServerHomeRow.CONTINUE_WATCHING in rows) started else emptyList(),
            nextUp = if (MediaServerHomeRow.NEXT_UP in rows) {
                if (dialect.supportsGlobalNextUp) nextUp?.await().orEmpty() else zeroPosition.filter { it.type == "Episode" }
            } else emptyList(),
            recentlyAdded = latest?.await().orEmpty(),
        )
    }

    /**
     * A shelf is optional: a server that implements only part of the Jellyfin API (early builds, other compatible servers)
     * may answer a route with 400/404/405/501 or an HTML page. That empties THAT shelf; the others still show. A revoked token
     * (401/403) and an unreachable server are not "missing route" and still surface so the session state is right.
     */
    private suspend fun <T> optionalShelf(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: MediaServerException.Http) {
        if (e.isUnauthorized || e.status >= 500 && e.status != 501) throw e else emptyList()
    } catch (e: MediaServerException.Malformed) {
        emptyList()
    }

    override suspend fun playbackInfo(itemId: String, request: PlaybackInfoRequest): PlaybackNegotiation {
        val query = buildList {
            add("userId" to userId)
            request.mediaSourceId?.let { add("MediaSourceId" to it) }
            request.maxStreamingBitrate?.let { add("MaxStreamingBitrate" to it.toString()) }
            request.startTimeTicks?.let { add("StartTimeTicks" to it.toString()) }
            request.audioStreamIndex?.let { add("AudioStreamIndex" to it.toString()) }
            request.subtitleStreamIndex?.let { add("SubtitleStreamIndex" to it.toString()) }
            if (request.forceTranscode) { add("EnableDirectPlay" to "false"); add("EnableDirectStream" to "false") }
        }
        val body = buildJsonObject {
            put("UserId", userId)
            request.mediaSourceId?.let { put("MediaSourceId", it) }
            request.maxStreamingBitrate?.let { put("MaxStreamingBitrate", it) }
            request.startTimeTicks?.let { put("StartTimeTicks", it) }
            request.audioStreamIndex?.let { put("AudioStreamIndex", it) }
            request.subtitleStreamIndex?.let { put("SubtitleStreamIndex", it) }
            if (request.forceTranscode) { put("EnableDirectPlay", false); put("EnableDirectStream", false) }
            put("DeviceProfile", MediaBrowserDeviceProfile.build(request.maxStreamingBitrate, request.burnSubtitles))
        }
        val dto = decodeBody<PlaybackInfoDto>(post("/Items/${MediaBrowserPaths.segment(itemId)}/PlaybackInfo", query, body, MediaServerRequest.SLOW_RESOLVE_TIMEOUT_MS))
        if (dto.errorCode != null && dto.mediaSources.isEmpty()) throw MediaServerException.Malformed("playback refused: ${dto.errorCode}")
        return PlaybackNegotiation(dto.mediaSources, dto.playSessionId)
    }

    override suspend fun report(report: PlaybackReport) {
        // Emby answers 400 to start/progress without a PlaySessionId (Stopped tolerates it); the id only has to be stable per session.
        val sessionId = report.playSessionId ?: if (dialect.requiresPlaySessionId) "tuvora-${report.itemId}" else null
        val body = buildJsonObject {
            put("ItemId", report.itemId)
            report.mediaSourceId?.let { put("MediaSourceId", it) }
            put("PositionTicks", report.positionTicks)
            sessionId?.let { put("PlaySessionId", it) }
            if (report.kind == PlaybackReportKind.STOPPED) {
                put("Failed", false)
            } else {
                report.audioStreamIndex?.let { put("AudioStreamIndex", it) }
                report.subtitleStreamIndex?.let { put("SubtitleStreamIndex", it) }
                put("CanSeek", true)
                put("IsPaused", report.isPaused)
                put("IsMuted", false)
                put("PlayMethod", report.playMethod)
                put("RepeatMode", "RepeatNone")
                put("PlaybackOrder", "Default")
            }
        }
        val path = when (report.kind) {
            PlaybackReportKind.START -> "/Sessions/Playing"
            PlaybackReportKind.PROGRESS -> "/Sessions/Playing/Progress"
            PlaybackReportKind.STOPPED -> "/Sessions/Playing/Stopped"
        }
        post(path, body = body)
    }

    override suspend fun setPlayed(itemId: String, played: Boolean) {
        val query = listOf("userId" to userId)
        if (played) post(paths.playedItem(itemId), query) else delete(paths.playedItem(itemId), query)
    }

    override suspend fun authorizeQuickConnect(code: String) {
        if (!dialect.supportsQuickConnect) throw MediaServerException.Http(404)
        post("/QuickConnect/Authorize", listOf("code" to code.trim()))
    }

    override suspend fun logout() {
        post("/Sessions/Logout")
    }

    override suspend fun stopEncoding(playSessionId: String) {
        delete("/Videos/ActiveEncodings", listOf("deviceId" to identity.deviceId, "playSessionId" to playSessionId))
    }

    private companion object {
        const val NEXT_UP_WINDOW_MS = 365L * 24 * 3600 * 1000
    }
}
