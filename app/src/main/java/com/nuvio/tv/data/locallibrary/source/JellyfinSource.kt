package com.nuvio.tv.data.locallibrary.source

import android.util.Log
import androidx.media3.common.MimeTypes
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore
import com.nuvio.tv.data.locallibrary.LocalLibraryCredentialStore.Field
import com.nuvio.tv.data.remote.api.JellyfinApi
import com.nuvio.tv.data.remote.api.JellyfinAuthRequest
import com.nuvio.tv.data.remote.api.JellyfinDeviceProfile
import com.nuvio.tv.data.remote.api.JellyfinDirectPlayProfile
import com.nuvio.tv.data.remote.api.JellyfinItem
import com.nuvio.tv.data.remote.api.JellyfinMediaStream
import com.nuvio.tv.data.remote.api.JellyfinPlaybackInfoRequest
import com.nuvio.tv.data.remote.api.JellyfinPlaybackMediaSource
import com.nuvio.tv.data.remote.api.JellyfinSubtitleProfile
import com.nuvio.tv.data.remote.api.JellyfinTranscodingProfile
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.locallibrary.ExternalSubtitleFile
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ResolvedStream
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import java.util.Locale
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.UUID

class JellyfinSource(
    override val config: LocalLibrarySourceConfig,
    private val credentialStore: LocalLibraryCredentialStore,
    private val httpClient: OkHttpClient,
    private val moshi: Moshi
) : LocalLibrarySource {

    private val api: JellyfinApi by lazy { buildApi() }

    override fun scan(): Flow<ScannedItem> = flow {
        val auth = ensureAuth() ?: error("Jellyfin authentication failed for ${config.displayName}")
        val pageSize = 500
        var startIndex = 0
        while (true) {
            val response = api.getItems(
                authHeader = authHeader(auth.token),
                userId = auth.userId,
                startIndex = startIndex,
                limit = pageSize
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "Jellyfin getItems failed: ${response.code()} (startIndex=$startIndex)")
                // Surface a first-page failure (auth/permission/bad request) as a
                // hard error so the scan reports "Failed" instead of masquerading
                // as an empty library. Later-page failures keep what we collected.
                if (startIndex == 0) error("Jellyfin returned HTTP ${response.code()} while listing items")
                break
            }
            val body = response.body() ?: break
            val items = body.items.orEmpty()
            if (items.isEmpty()) break
            items.forEach { jfItem ->
                toScannedItem(jfItem)?.let { emit(it) }
            }
            startIndex += items.size
            val total = body.totalRecordCount ?: 0
            if (startIndex >= total || items.size < pageSize) break
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun resolveStream(item: ScannedItem): ResolvedStream? {
        val auth = ensureAuth() ?: return null
        val jellyfinItemId = item.sourceItemId ?: item.relativePath
        val base = config.urlOrPath.trimEnd('/')
        val playSessionId = UUID.randomUUID().toString().replace("-", "")
        val negotiated = negotiatePlayback(auth, jellyfinItemId, base, playSessionId)
        val url = negotiated?.url ?: run {
            Log.w(TAG, "PlaybackInfo failed for $jellyfinItemId; falling back to legacy static stream")
            "$base/Videos/$jellyfinItemId/stream?api_key=${auth.token}&static=true&PlaySessionId=$playSessionId"
        }
        return ResolvedStream(
            url = url,
            headers = emptyMap(),
            scheme = if (url.startsWith("https")) "https" else "http",
            sizeBytes = item.sizeBytes,
            durationMs = item.durationMs,
            subtitles = negotiated?.subtitles.orEmpty()
        )
    }

    private data class Negotiated(val url: String, val subtitles: List<ExternalSubtitleFile>)

    private suspend fun negotiatePlayback(
        auth: Auth,
        itemId: String,
        base: String,
        playSessionId: String
    ): Negotiated? = runCatching {
        val resp = api.getPlaybackInfo(
            authHeader = authHeader(auth.token),
            itemId = itemId,
            userId = auth.userId,
            body = JellyfinPlaybackInfoRequest(
                userId = auth.userId,
                deviceProfile = buildDeviceProfile()
            )
        )
        if (!resp.isSuccessful) {
            Log.w(TAG, "PlaybackInfo HTTP ${resp.code()} for $itemId")
            return@runCatching null
        }
        val body = resp.body() ?: return@runCatching null
        val source = body.mediaSources?.firstOrNull() ?: return@runCatching null
        Log.d(
            TAG,
            "PlaybackInfo for $itemId: container=${source.container} " +
                "supportsDirectPlay=${source.supportsDirectPlay} " +
                "supportsDirectStream=${source.supportsDirectStream} " +
                "supportsTranscoding=${source.supportsTranscoding} " +
                "transcodingUrl=${source.transcodingUrl != null} " +
                "videoCodecs=${source.mediaStreams?.filter { it.type == "Video" }?.map { it.codec }} " +
                "audioCodecs=${source.mediaStreams?.filter { it.type == "Audio" }?.map { it.codec }}"
        )
        val sessionId = body.playSessionId?.takeIf { it.isNotBlank() } ?: playSessionId
        val (url, method) = chooseUrl(source, auth.token, base, itemId, sessionId)
            ?: return@runCatching null
        Log.d(TAG, "Jellyfin chose $method for $itemId: $url")
        val subtitles = extractExternalSubtitles(source, auth.token, base, itemId)
        if (subtitles.isNotEmpty()) {
            Log.i(TAG, "Jellyfin exposed ${subtitles.size} external subtitle(s) for $itemId")
        }
        Negotiated(url = url, subtitles = subtitles)
    }.onFailure { Log.w(TAG, "PlaybackInfo call failed for $itemId", it) }.getOrNull()

    private fun extractExternalSubtitles(
        source: JellyfinPlaybackMediaSource,
        token: String,
        base: String,
        itemId: String
    ): List<ExternalSubtitleFile> {
        val streams = source.mediaStreams.orEmpty()
        val mediaSourceId = source.id ?: itemId
        return streams.mapNotNull { stream ->
            if (!stream.type.equals("Subtitle", ignoreCase = true)) return@mapNotNull null
            val isExternal = stream.isExternal ||
                stream.deliveryMethod.equals("External", ignoreCase = true)
            if (!isExternal) return@mapNotNull null
            val codec = stream.codec?.lowercase(Locale.ROOT) ?: return@mapNotNull null
            val mime = subtitleMimeForCodec(codec) ?: return@mapNotNull null
            val index = stream.index ?: return@mapNotNull null

            val url = buildSubtitleUrl(stream, base, itemId, mediaSourceId, index, codec, token)
                ?: return@mapNotNull null

            val language = stream.language
                ?.takeIf { it.isNotBlank() }
                ?.let { PlayerSubtitleUtils.normalizeLanguage(it)?.tag ?: PlayerSubtitleUtils.normalizeLanguageCode(it) }
                ?.takeIf { it.isNotBlank() }

            val displayName = listOf(stream.displayTitle, stream.title)
                .firstOrNull { !it.isNullOrBlank() }
                ?: language
                ?: "Subtitle"

            ExternalSubtitleFile(
                url = url,
                displayName = displayName,
                language = language,
                mimeType = mime,
                isForced = stream.isForced,
                source = ExternalSubtitleFile.Source.JELLYFIN
            )
        }
    }

    private fun buildSubtitleUrl(
        stream: JellyfinMediaStream,
        base: String,
        itemId: String,
        mediaSourceId: String,
        index: Int,
        codec: String,
        token: String
    ): String? {
        val delivery = stream.deliveryUrl?.takeIf { it.isNotBlank() }
        if (delivery != null) {
            val absolute = if (delivery.startsWith("http")) delivery else base + (if (delivery.startsWith("/")) delivery else "/$delivery")
            return appendApiKey(absolute, token)
        }
        return "$base/Videos/$itemId/$mediaSourceId/Subtitles/$index/0/Stream.$codec?api_key=$token"
    }

    private fun appendApiKey(url: String, token: String): String {
        if (url.contains("api_key=")) return url
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}api_key=$token"
    }

    private fun subtitleMimeForCodec(codec: String): String? = when (codec) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> null
    }

    /**
     * Picks the playback URL based on what the server reports it can do.
     *
     * Direct Play (Static=true) is byte-range seekable. Transcoding via HLS is segment-seekable.
     * We deliberately do NOT use Direct Stream (Static=false) — Jellyfin's remux output isn't
     * byte-range seekable, which breaks ExoPlayer scrubbing. Containers that ExoPlayer can play
     * are restricted via the DeviceProfile so MKV/AVI/etc. land on HLS transcode instead.
     */
    private fun chooseUrl(
        source: JellyfinPlaybackMediaSource,
        token: String,
        base: String,
        itemId: String,
        playSessionId: String
    ): Pair<String, String>? {
        val mediaSourceId = source.id ?: itemId
        val container = source.container?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() } ?: "mp4"
        if (source.supportsDirectPlay) {
            val url = buildString {
                append("$base/Videos/$itemId/stream.$container")
                append("?api_key=$token")
                append("&Static=true")
                append("&MediaSourceId=$mediaSourceId")
                append("&PlaySessionId=$playSessionId")
            }
            return url to "DirectPlay"
        }
        if (source.transcodingUrl != null) {
            val trUrl = source.transcodingUrl
            val absolute = if (trUrl.startsWith("http")) trUrl else base + trUrl
            val withSession = if (absolute.contains("PlaySessionId=")) absolute
            else if (absolute.contains("?")) "$absolute&PlaySessionId=$playSessionId"
            else "$absolute?PlaySessionId=$playSessionId"
            val withAuth = if (withSession.contains("api_key=")) withSession
            else "$withSession&api_key=$token"
            return withAuth to "Transcode"
        }
        return null
    }

    private fun buildDeviceProfile(): JellyfinDeviceProfile = JellyfinDeviceProfile(
        // Advertise the containers/codecs ExoPlayer + bundled FFmpeg/AV1 decoders handle
        // so Jellyfin serves the raw file (Static=true) whenever possible. Real-time HLS
        // transcode causes video to lag behind audio; Direct Play avoids that and matches
        // what the official Jellyfin players do for compatible files.
        directPlayProfiles = listOf(
            JellyfinDirectPlayProfile(
                container = "mp4,m4v",
                videoCodec = "h264,hevc,mpeg4,av1",
                audioCodec = "aac,ac3,eac3,mp3,opus,flac,alac"
            ),
            JellyfinDirectPlayProfile(
                container = "mkv",
                videoCodec = "h264,hevc,vp8,vp9,av1,mpeg4",
                audioCodec = "aac,ac3,eac3,mp3,opus,vorbis,flac,dca,truehd,mlp,pcm"
            ),
            JellyfinDirectPlayProfile(
                container = "webm",
                videoCodec = "vp8,vp9,av1",
                audioCodec = "opus,vorbis"
            ),
            JellyfinDirectPlayProfile(
                container = "mov",
                videoCodec = "h264,hevc,mpeg4",
                audioCodec = "aac,ac3,eac3,mp3,alac,pcm"
            ),
            JellyfinDirectPlayProfile(
                container = "ts,m2ts",
                videoCodec = "h264,hevc",
                audioCodec = "aac,ac3,eac3,mp3,dca,truehd"
            )
        ),
        transcodingProfiles = listOf(
            JellyfinTranscodingProfile(
                container = "ts",
                videoCodec = "h264,hevc",
                audioCodec = "aac,ac3,eac3,mp3"
            ),
            JellyfinTranscodingProfile(
                container = "mp4",
                videoCodec = "h264,hevc",
                audioCodec = "aac,ac3,eac3,mp3"
            )
        ),
        subtitleProfiles = listOf(
            JellyfinSubtitleProfile(format = "srt", method = "External"),
            JellyfinSubtitleProfile(format = "vtt", method = "External"),
            JellyfinSubtitleProfile(format = "ass", method = "External"),
            JellyfinSubtitleProfile(format = "ssa", method = "External"),
            JellyfinSubtitleProfile(format = "pgssub", method = "Embed"),
            JellyfinSubtitleProfile(format = "subrip", method = "Embed")
        )
    )

    override suspend fun testConnection(): Result<Unit> = runCatching {
        val resp = api.publicInfo()
        require(resp.isSuccessful) { "Server returned HTTP ${resp.code()}" }
        val auth = ensureAuth() ?: error("Authentication failed")
        require(auth.token.isNotBlank()) { "Empty access token" }
    }

    private fun toScannedItem(item: JellyfinItem): ScannedItem? {
        val type = when (item.type?.lowercase()) {
            "movie" -> ContentType.MOVIE
            "episode" -> ContentType.SERIES
            else -> return null
        }
        val tmdbHint = item.providerIds?.get("Tmdb")?.toIntOrNull()
        val durationMs = item.runTimeTicks?.let { it / 10_000 }
        return ScannedItem(
            sourceId = config.id,
            relativePath = item.id,
            fileName = item.path?.substringAfterLast('/')
                ?: item.name
                ?: item.id,
            sizeBytes = item.mediaSources?.firstOrNull()?.size,
            durationMs = durationMs,
            sourceItemId = item.id,
            tmdbHintId = tmdbHint,
            parsedTitle = item.seriesName ?: item.name,
            parsedYear = item.productionYear,
            parsedSeason = item.parentIndexNumber,
            parsedEpisode = item.indexNumber,
            typeHint = type
        )
    }

    data class Auth(val token: String, val userId: String)

    private fun ensureAuth(): Auth? {
        val storedToken = credentialStore.getSecret(config.id, Field.JELLYFIN_TOKEN)
        val storedUserId = credentialStore.getSecret(config.id, Field.JELLYFIN_USER_ID)
        if (storedToken.isNullOrBlank() || storedUserId.isNullOrBlank()) return null
        return Auth(storedToken, storedUserId)
    }

    /**
     * Authenticates against the server but does NOT persist the resulting token.
     * Used by the Test Connection flow so a successful test doesn't leak
     * credentials into the credential store before the user has confirmed.
     */
    suspend fun verifyCredentials(username: String, password: String): Result<Auth> = runCatching {
        val resp = api.authenticateByName(
            authHeader = authHeader(null),
            body = JellyfinAuthRequest(username = username, password = password)
        )
        if (!resp.isSuccessful) error("HTTP ${resp.code()} ${resp.message()}")
        val body = resp.body() ?: error("Empty response from server")
        val token = body.accessToken ?: error("Server did not return an access token")
        val userId = body.user?.id ?: error("Server did not return a user id")
        Auth(token, userId)
    }

    /** Public so the Add Source flow can prime credentials before persisting them. */
    suspend fun authenticate(username: String, password: String): Auth? {
        val result = verifyCredentials(username, password)
        val auth = result.getOrNull() ?: run {
            Log.w(TAG, "Jellyfin auth failed: ${result.exceptionOrNull()?.message}")
            return null
        }
        credentialStore.putSecret(config.id, Field.JELLYFIN_TOKEN, auth.token)
        credentialStore.putSecret(config.id, Field.JELLYFIN_USER_ID, auth.userId)
        return auth
    }

    private fun authHeader(token: String?): String {
        val deviceId = config.params["deviceId"] ?: deriveDeviceId()
        val base = """MediaBrowser Client="Nuvio TV", Device="Android TV", DeviceId="$deviceId", Version="1.0""""
        return if (token.isNullOrBlank()) base else "$base, Token=\"$token\""
    }

    private fun deriveDeviceId(): String =
        UUID.nameUUIDFromBytes(("nuvio-${config.id}").toByteArray()).toString()

    private fun buildApi(): JellyfinApi {
        val base = config.urlOrPath.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(JellyfinApi::class.java)
    }

    companion object {
        private const val TAG = "JellyfinSource"
    }
}
