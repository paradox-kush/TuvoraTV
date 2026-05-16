package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Jellyfin REST surface used by the local library subsystem. Per-server
 * instances are built at runtime since each user-configured source has its own
 * base URL and auth token.
 */
interface JellyfinApi {

    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(
        @Header("Authorization") authHeader: String,
        @Body body: JellyfinAuthRequest
    ): Response<JellyfinAuthResponse>

    @GET("System/Info/Public")
    suspend fun publicInfo(): Response<JellyfinPublicInfo>

    @POST("Items/{itemId}/PlaybackInfo")
    suspend fun getPlaybackInfo(
        @Header("Authorization") authHeader: String,
        @Path("itemId") itemId: String,
        @Query("UserId") userId: String,
        @Body body: JellyfinPlaybackInfoRequest
    ): Response<JellyfinPlaybackInfoResponse>

    @GET("Users/{userId}/Items")
    suspend fun getItems(
        @Header("Authorization") authHeader: String,
        @Path("userId") userId: String,
        @Query("Recursive") recursive: Boolean = true,
        @Query("IncludeItemTypes") includeItemTypes: String = "Movie,Episode",
        @Query("Fields") fields: String = "ProviderIds,Path,MediaSources,RunTimeTicks,ParentIndexNumber,IndexNumber,SeriesName,ProductionYear",
        @Query("EnableUserData") enableUserData: Boolean = false,
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 500
    ): Response<JellyfinItemsResponse>
}

@JsonClass(generateAdapter = true)
data class JellyfinAuthRequest(
    @Json(name = "Username") val username: String,
    @Json(name = "Pw") val password: String
)

@JsonClass(generateAdapter = true)
data class JellyfinAuthResponse(
    @Json(name = "User") val user: JellyfinUser? = null,
    @Json(name = "AccessToken") val accessToken: String? = null,
    @Json(name = "ServerId") val serverId: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinUser(
    @Json(name = "Id") val id: String? = null,
    @Json(name = "Name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinPublicInfo(
    @Json(name = "ServerName") val serverName: String? = null,
    @Json(name = "Version") val version: String? = null,
    @Json(name = "Id") val id: String? = null,
    @Json(name = "ProductName") val productName: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinItemsResponse(
    @Json(name = "Items") val items: List<JellyfinItem>? = null,
    @Json(name = "TotalRecordCount") val totalRecordCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinItem(
    @Json(name = "Id") val id: String,
    @Json(name = "Name") val name: String? = null,
    @Json(name = "Type") val type: String? = null,
    @Json(name = "Path") val path: String? = null,
    @Json(name = "ProviderIds") val providerIds: Map<String, String>? = null,
    @Json(name = "MediaSources") val mediaSources: List<JellyfinMediaSource>? = null,
    @Json(name = "RunTimeTicks") val runTimeTicks: Long? = null,
    @Json(name = "ProductionYear") val productionYear: Int? = null,
    @Json(name = "ParentIndexNumber") val parentIndexNumber: Int? = null,
    @Json(name = "IndexNumber") val indexNumber: Int? = null,
    @Json(name = "SeriesName") val seriesName: String? = null,
    @Json(name = "SeriesId") val seriesId: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinMediaSource(
    @Json(name = "Id") val id: String? = null,
    @Json(name = "Container") val container: String? = null,
    @Json(name = "Size") val size: Long? = null,
    @Json(name = "Path") val path: String? = null
)

// ---------- PlaybackInfo ----------

@JsonClass(generateAdapter = true)
data class JellyfinPlaybackInfoRequest(
    @Json(name = "UserId") val userId: String,
    @Json(name = "DeviceProfile") val deviceProfile: JellyfinDeviceProfile,
    @Json(name = "MaxStreamingBitrate") val maxStreamingBitrate: Long = 120_000_000L,
    @Json(name = "EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @Json(name = "EnableDirectStream") val enableDirectStream: Boolean = true,
    @Json(name = "EnableTranscoding") val enableTranscoding: Boolean = true,
    @Json(name = "AllowVideoStreamCopy") val allowVideoStreamCopy: Boolean = true,
    @Json(name = "AllowAudioStreamCopy") val allowAudioStreamCopy: Boolean = true,
    @Json(name = "AutoOpenLiveStream") val autoOpenLiveStream: Boolean = true
)

@JsonClass(generateAdapter = true)
data class JellyfinPlaybackInfoResponse(
    @Json(name = "MediaSources") val mediaSources: List<JellyfinPlaybackMediaSource>? = null,
    @Json(name = "PlaySessionId") val playSessionId: String? = null,
    @Json(name = "ErrorCode") val errorCode: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinPlaybackMediaSource(
    @Json(name = "Id") val id: String? = null,
    @Json(name = "Container") val container: String? = null,
    @Json(name = "Size") val size: Long? = null,
    @Json(name = "Path") val path: String? = null,
    @Json(name = "Protocol") val protocol: String? = null,
    @Json(name = "SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @Json(name = "SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @Json(name = "SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @Json(name = "TranscodingUrl") val transcodingUrl: String? = null,
    @Json(name = "TranscodingContainer") val transcodingContainer: String? = null,
    @Json(name = "TranscodingSubProtocol") val transcodingSubProtocol: String? = null,
    @Json(name = "Bitrate") val bitrate: Long? = null,
    @Json(name = "RunTimeTicks") val runTimeTicks: Long? = null,
    @Json(name = "MediaStreams") val mediaStreams: List<JellyfinMediaStream>? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinMediaStream(
    @Json(name = "Type") val type: String? = null,
    @Json(name = "Codec") val codec: String? = null,
    @Json(name = "Index") val index: Int? = null,
    @Json(name = "Language") val language: String? = null,
    @Json(name = "DisplayTitle") val displayTitle: String? = null,
    @Json(name = "Title") val title: String? = null,
    @Json(name = "IsDefault") val isDefault: Boolean = false,
    @Json(name = "IsForced") val isForced: Boolean = false,
    @Json(name = "IsExternal") val isExternal: Boolean = false,
    @Json(name = "Channels") val channels: Int? = null,
    @Json(name = "DeliveryUrl") val deliveryUrl: String? = null,
    @Json(name = "DeliveryMethod") val deliveryMethod: String? = null,
    @Json(name = "Path") val path: String? = null
)

// ---------- DeviceProfile ----------

@JsonClass(generateAdapter = true)
data class JellyfinDeviceProfile(
    @Json(name = "Name") val name: String = "Nuvio TV",
    @Json(name = "MaxStaticBitrate") val maxStaticBitrate: Long = 120_000_000L,
    @Json(name = "MaxStreamingBitrate") val maxStreamingBitrate: Long = 120_000_000L,
    @Json(name = "MusicStreamingTranscodingBitrate") val musicStreamingTranscodingBitrate: Long = 384_000L,
    @Json(name = "DirectPlayProfiles") val directPlayProfiles: List<JellyfinDirectPlayProfile>,
    @Json(name = "TranscodingProfiles") val transcodingProfiles: List<JellyfinTranscodingProfile>,
    @Json(name = "SubtitleProfiles") val subtitleProfiles: List<JellyfinSubtitleProfile>
)

@JsonClass(generateAdapter = true)
data class JellyfinDirectPlayProfile(
    @Json(name = "Container") val container: String,
    @Json(name = "Type") val type: String = "Video",
    @Json(name = "VideoCodec") val videoCodec: String? = null,
    @Json(name = "AudioCodec") val audioCodec: String? = null
)

@JsonClass(generateAdapter = true)
data class JellyfinTranscodingProfile(
    @Json(name = "Container") val container: String,
    @Json(name = "Type") val type: String = "Video",
    @Json(name = "VideoCodec") val videoCodec: String,
    @Json(name = "AudioCodec") val audioCodec: String,
    @Json(name = "Protocol") val protocol: String = "hls",
    @Json(name = "Context") val context: String = "Streaming",
    @Json(name = "MaxAudioChannels") val maxAudioChannels: String = "6",
    @Json(name = "MinSegments") val minSegments: Int = 2,
    @Json(name = "BreakOnNonKeyFrames") val breakOnNonKeyFrames: Boolean = true
)

@JsonClass(generateAdapter = true)
data class JellyfinSubtitleProfile(
    @Json(name = "Format") val format: String,
    @Json(name = "Method") val method: String
)
