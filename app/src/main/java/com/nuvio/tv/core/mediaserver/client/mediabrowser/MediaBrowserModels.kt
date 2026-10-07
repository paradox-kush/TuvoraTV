package com.nuvio.tv.core.mediaserver.client.mediabrowser

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One lenient decoder for every MediaBrowser payload: unknown members are ignored (servers add fields freely), defaults fill gaps. */
@OptIn(ExperimentalSerializationApi::class)
internal val MediaBrowserJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

/** `GET /System/Info/Public` - anonymous. Jellyfin reports `ProductName`; Emby 4.9 omits it but is the one that returns `RemoteAddresses`. */
@Serializable
internal data class PublicSystemInfoDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("ProductName") val productName: String? = null,
    @SerialName("LocalAddress") val localAddress: String? = null,
    @SerialName("StartupWizardCompleted") val startupWizardCompleted: Boolean? = null,
    @SerialName("RemoteAddresses") val remoteAddresses: List<String>? = null,
)

@Serializable
internal data class UserPolicyDto(
    @SerialName("IsAdministrator") val isAdministrator: Boolean = false,
)

@Serializable
internal data class UserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("HasPassword") val hasPassword: Boolean? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
    @SerialName("Policy") val policy: UserPolicyDto? = null,
)

/** `POST /Users/AuthenticateByName` / `/Users/AuthenticateWithQuickConnect` result. */
@Serializable
internal data class AuthenticationResultDto(
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("User") val user: UserDto? = null,
)

/** `POST /QuickConnect/Initiate` and `GET /QuickConnect/Connect?secret=`. */
@Serializable
internal data class QuickConnectResultDto(
    @SerialName("Authenticated") val authenticated: Boolean = false,
    @SerialName("Secret") val secret: String? = null,
    @SerialName("Code") val code: String? = null,
    @SerialName("DeviceId") val deviceId: String? = null,
    @SerialName("DeviceName") val deviceName: String? = null,
)

@Serializable
internal data class UserDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)

@Serializable
internal data class MediaStreamDto(
    @SerialName("Index") val index: Int = 0,
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("BitRate") val bitRate: Long? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("VideoRange") val videoRange: String? = null,
)

@Serializable
internal data class MediaSourceDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Container") val container: String? = null,
    /** The server-side path; only read to recognise a server's own placeholder (never shown, never fetched). */
    @SerialName("Path") val path: String? = null,
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("IsRemote") val isRemote: Boolean = false,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)

@Serializable
internal data class PersonDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

/** The `BaseItemDto` subset Tuvora reads. Field names are the wire's (PascalCase) on both Jellyfin and Emby. */
@Serializable
internal data class ItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("OriginalTitle") val originalTitle: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("People") val people: List<PersonDto> = emptyList(),
    @SerialName("ProviderIds") val providerIds: Map<String, String?> = emptyMap(),
    @SerialName("ImageTags") val imageTags: Map<String, String?> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("PrimaryImageAspectRatio") val primaryImageAspectRatio: Double? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("RecursiveItemCount") val recursiveItemCount: Int? = null,
    @SerialName("UserData") val userData: UserDataDto? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
)

/** The `{Items, TotalRecordCount}` envelope of every list route. */
@Serializable
internal data class ItemsEnvelopeDto(
    @SerialName("Items") val items: List<ItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
    @SerialName("StartIndex") val startIndex: Int? = null,
)

/** `POST /Items/{id}/PlaybackInfo` result. */
@Serializable
internal data class PlaybackInfoDto(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

/** `GET /Search/Hints` (Jellyfin) result row; Emby searches through `/Items?SearchTerm=` instead. */
@Serializable
internal data class SearchHintsEnvelopeDto(
    @SerialName("SearchHints") val searchHints: List<SearchHintDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

@Serializable
internal data class SearchHintDto(
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
)
