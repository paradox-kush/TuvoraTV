package com.nuvio.tv.data.remote.dto.mal

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTOs for `GET /users/@me/animelist` and `PATCH /anime/{id}/my_list_status`.
 *
 * Only fields actually consumed by list catalog rendering and progress writes
 * are modelled — MAL returns a lot more under `fields=` expansion. Unknown
 * keys are ignored by Moshi.
 *
 * Status wire values (both read and write): "watching", "completed",
 * "on_hold", "dropped", "plan_to_watch". Movies use the same enum; MAL has
 * no separate movie status.
 */

@JsonClass(generateAdapter = true)
data class MalAnimeListPageDto(
    @Json(name = "data") val data: List<MalListEntryDto> = emptyList(),
    @Json(name = "paging") val paging: MalPagingDto? = null
)

@JsonClass(generateAdapter = true)
data class MalPagingDto(
    @Json(name = "previous") val previous: String? = null,
    @Json(name = "next") val next: String? = null
)

@JsonClass(generateAdapter = true)
data class MalListEntryDto(
    @Json(name = "node") val node: MalAnimeNodeDto,
    @Json(name = "list_status") val listStatus: MalListStatusDto? = null
)

@JsonClass(generateAdapter = true)
data class MalAnimeNodeDto(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String,
    @Json(name = "num_episodes") val numEpisodes: Int? = null,
    @Json(name = "media_type") val mediaType: String? = null, // tv, movie, ova, ona, special, music
    @Json(name = "main_picture") val mainPicture: MalPictureDto? = null,
    @Json(name = "alternative_titles") val alternativeTitles: MalAlternativeTitlesDto? = null,
    @Json(name = "start_season") val startSeason: MalStartSeasonDto? = null
)

@JsonClass(generateAdapter = true)
data class MalPictureDto(
    @Json(name = "medium") val medium: String? = null,
    @Json(name = "large") val large: String? = null
)

@JsonClass(generateAdapter = true)
data class MalAlternativeTitlesDto(
    @Json(name = "en") val english: String? = null,
    @Json(name = "ja") val japanese: String? = null
)

@JsonClass(generateAdapter = true)
data class MalStartSeasonDto(
    @Json(name = "year") val year: Int? = null,
    @Json(name = "season") val season: String? = null
)

@JsonClass(generateAdapter = true)
data class MalListStatusDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "score") val score: Int = 0,
    @Json(name = "num_episodes_watched") val numEpisodesWatched: Int = 0,
    @Json(name = "is_rewatching") val isRewatching: Boolean = false,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "finish_date") val finishDate: String? = null
)

/**
 * Shape returned by `GET /anime/{id}?fields=my_list_status,num_episodes,media_type`.
 * `my_list_status` is null when the user has not added this anime.
 */
@JsonClass(generateAdapter = true)
data class MalAnimeWithStatusDto(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String,
    @Json(name = "num_episodes") val numEpisodes: Int? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "my_list_status") val myListStatus: MalListStatusDto? = null
)

@JsonClass(generateAdapter = true)
data class MalUserDto(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "picture") val picture: String? = null,
    @Json(name = "location") val location: String? = null,
    @Json(name = "joined_at") val joinedAt: String? = null
)
