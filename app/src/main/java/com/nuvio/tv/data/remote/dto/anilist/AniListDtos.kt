package com.nuvio.tv.data.remote.dto.anilist

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Everything the app consumes from the AniList GraphQL endpoint. AniList
 * speaks a single POST at `https://graphql.anilist.co/`; the query string is
 * embedded in the request body.
 *
 * Status wire values (MediaListStatus enum): CURRENT | PLANNING | COMPLETED |
 * DROPPED | PAUSED | REPEATING.
 */

@JsonClass(generateAdapter = true)
data class AniListGraphQLRequest(
    @Json(name = "query") val query: String,
    @Json(name = "variables") val variables: Map<String, Any?> = emptyMap()
)

// --- Response envelopes — one per query we actually run --- //

@JsonClass(generateAdapter = true)
data class AniListViewerResponse(
    @Json(name = "data") val data: AniListViewerDataDto? = null,
    @Json(name = "errors") val errors: List<AniListErrorDto>? = null
)

@JsonClass(generateAdapter = true)
data class AniListViewerDataDto(
    @Json(name = "Viewer") val viewer: AniListUserDto? = null
)

@JsonClass(generateAdapter = true)
data class AniListUserDto(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "avatar") val avatar: AniListAvatarDto? = null
)

@JsonClass(generateAdapter = true)
data class AniListAvatarDto(
    @Json(name = "large") val large: String? = null,
    @Json(name = "medium") val medium: String? = null
)

@JsonClass(generateAdapter = true)
data class AniListMediaListCollectionResponse(
    @Json(name = "data") val data: AniListMediaListCollectionDataDto? = null,
    @Json(name = "errors") val errors: List<AniListErrorDto>? = null
)

@JsonClass(generateAdapter = true)
data class AniListMediaListCollectionDataDto(
    @Json(name = "MediaListCollection") val collection: AniListMediaListCollectionDto? = null
)

@JsonClass(generateAdapter = true)
data class AniListMediaListCollectionDto(
    @Json(name = "lists") val lists: List<AniListMediaListGroupDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AniListMediaListGroupDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "isCustomList") val isCustomList: Boolean = false,
    @Json(name = "entries") val entries: List<AniListMediaListEntryDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AniListMediaListEntryDto(
    @Json(name = "id") val id: Int,
    @Json(name = "mediaId") val mediaId: Int,
    @Json(name = "status") val status: String? = null,
    @Json(name = "progress") val progress: Int = 0,
    @Json(name = "score") val score: Double = 0.0,
    @Json(name = "media") val media: AniListMediaDto? = null
)

@JsonClass(generateAdapter = true)
data class AniListMediaDto(
    @Json(name = "id") val id: Int,
    @Json(name = "idMal") val idMal: Int? = null,
    @Json(name = "type") val type: String? = null, // ANIME | MANGA
    @Json(name = "format") val format: String? = null, // TV, TV_SHORT, MOVIE, OVA, ONA, SPECIAL, MUSIC
    @Json(name = "title") val title: AniListTitleDto? = null,
    @Json(name = "coverImage") val coverImage: AniListCoverImageDto? = null,
    @Json(name = "bannerImage") val bannerImage: String? = null,
    @Json(name = "episodes") val episodes: Int? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "startDate") val startDate: AniListFuzzyDateDto? = null
)

@JsonClass(generateAdapter = true)
data class AniListTitleDto(
    @Json(name = "romaji") val romaji: String? = null,
    @Json(name = "english") val english: String? = null,
    @Json(name = "native") val native: String? = null,
    @Json(name = "userPreferred") val userPreferred: String? = null
)

@JsonClass(generateAdapter = true)
data class AniListCoverImageDto(
    @Json(name = "large") val large: String? = null,
    @Json(name = "extraLarge") val extraLarge: String? = null,
    @Json(name = "medium") val medium: String? = null,
    @Json(name = "color") val color: String? = null
)

@JsonClass(generateAdapter = true)
data class AniListFuzzyDateDto(
    @Json(name = "year") val year: Int? = null,
    @Json(name = "month") val month: Int? = null,
    @Json(name = "day") val day: Int? = null
)

@JsonClass(generateAdapter = true)
data class AniListMediaListEntryResponse(
    @Json(name = "data") val data: AniListMediaListEntryDataDto? = null,
    @Json(name = "errors") val errors: List<AniListErrorDto>? = null
)

@JsonClass(generateAdapter = true)
data class AniListMediaListEntryDataDto(
    @Json(name = "MediaList") val entry: AniListMediaListEntryDto? = null
)

@JsonClass(generateAdapter = true)
data class AniListSaveMediaListEntryResponse(
    @Json(name = "data") val data: AniListSaveMediaListEntryDataDto? = null,
    @Json(name = "errors") val errors: List<AniListErrorDto>? = null
)

@JsonClass(generateAdapter = true)
data class AniListSaveMediaListEntryDataDto(
    @Json(name = "SaveMediaListEntry") val entry: AniListMediaListEntryDto? = null
)

@JsonClass(generateAdapter = true)
data class AniListErrorDto(
    @Json(name = "message") val message: String? = null,
    @Json(name = "status") val status: Int? = null
)

/**
 * All GraphQL operation strings live here so the API interface stays clean.
 * These are exact strings POSTed to `https://graphql.anilist.co/` inside an
 * [AniListGraphQLRequest].
 */
object AniListQueries {
    const val VIEWER = """
        query { Viewer { id name avatar { large medium } } }
    """

    const val MEDIA_LIST_COLLECTION = """
        query(${'$'}userId: Int!, ${'$'}type: MediaType!) {
            MediaListCollection(userId: ${'$'}userId, type: ${'$'}type) {
                lists {
                    name
                    status
                    isCustomList
                    entries {
                        id
                        mediaId
                        status
                        progress
                        score
                        media {
                            id
                            idMal
                            type
                            format
                            title { romaji english native userPreferred }
                            coverImage { large extraLarge medium color }
                            bannerImage
                            episodes
                            status
                            startDate { year month day }
                        }
                    }
                }
            }
        }
    """

    /**
     * Fetch a single list entry for `(userId, mediaId)`. Used by the fanout
     * service to read current progress before writing. `MediaList` returns
     * 404 when the user has no entry; the fanout treats that as progress=0.
     */
    const val MEDIA_LIST_ENTRY = """
        query(${'$'}userId: Int!, ${'$'}mediaId: Int!) {
            MediaList(userId: ${'$'}userId, mediaId: ${'$'}mediaId) {
                id
                mediaId
                status
                progress
                score
                media { id episodes }
            }
        }
    """

    const val SAVE_MEDIA_LIST_ENTRY = """
        mutation(
            ${'$'}mediaId: Int!,
            ${'$'}status: MediaListStatus,
            ${'$'}progress: Int
        ) {
            SaveMediaListEntry(
                mediaId: ${'$'}mediaId,
                status: ${'$'}status,
                progress: ${'$'}progress
            ) {
                id
                mediaId
                status
                progress
                score
            }
        }
    """
}
