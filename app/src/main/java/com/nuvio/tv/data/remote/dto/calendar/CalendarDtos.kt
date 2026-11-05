// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.data.remote.dto.calendar

import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ---- TVmaze (https://api.tvmaze.com, no key) ----

@JsonClass(generateAdapter = true)
data class TvMazeShowDto(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "network") val network: TvMazeNetworkDto? = null,
    @Json(name = "webChannel") val webChannel: TvMazeNetworkDto? = null,
    @Json(name = "externals") val externals: TvMazeExternalsDto? = null,
    @Json(name = "image") val image: TvMazeImageDto? = null,
    @Json(name = "_embedded") val embedded: TvMazeShowEmbeddedDto? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeImageDto(
    @Json(name = "medium") val medium: String? = null,
    @Json(name = "original") val original: String? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeNetworkDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "country") val country: TvMazeCountryDto? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeCountryDto(
    @Json(name = "timezone") val timezone: String? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeExternalsDto(
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "thetvdb") val thetvdb: Long? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeShowEmbeddedDto(
    @Json(name = "nextepisode") val nextEpisode: TvMazeEpisodeDto? = null,
    @Json(name = "previousepisode") val previousEpisode: TvMazeEpisodeDto? = null,
    @Json(name = "seasons") val seasons: List<TvMazeSeasonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeSeasonDto(
    @Json(name = "id") val id: Int,
    @Json(name = "number") val number: Int? = null
)

@JsonClass(generateAdapter = true)
data class TvMazeEpisodeDto(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "airdate") val airdate: String? = null,
    @Json(name = "airtime") val airtime: String? = null,
    @Json(name = "airstamp") val airstamp: String? = null
)

// ---- Trakt calendar + next episode ----

@JsonClass(generateAdapter = true)
data class TraktCalendarShowDto(
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "episode") val episode: TraktCalendarEpisodeDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCalendarEpisodeDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "episode_type") val episodeType: String? = null
)

// ---- TMDB fields the shared TmdbApi DTOs do not carry ----

@JsonClass(generateAdapter = true)
data class CalendarTmdbFindResponse(
    @Json(name = "movie_results") val movieResults: List<CalendarTmdbIdOnly>? = null,
    @Json(name = "tv_results") val tvResults: List<CalendarTmdbIdOnly>? = null
)

@JsonClass(generateAdapter = true)
data class CalendarTmdbIdOnly(@Json(name = "id") val id: Int)

@JsonClass(generateAdapter = true)
data class CalendarTmdbShowDto(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "networks") val networks: List<CalendarTmdbNetworkDto>? = null,
    @Json(name = "last_episode_to_air") val lastEpisodeToAir: CalendarTmdbEpisodeDto? = null,
    @Json(name = "next_episode_to_air") val nextEpisodeToAir: CalendarTmdbEpisodeDto? = null
)

@JsonClass(generateAdapter = true)
data class CalendarTmdbNetworkDto(@Json(name = "name") val name: String? = null)

@JsonClass(generateAdapter = true)
data class CalendarTmdbEpisodeDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "episode_type") val episodeType: String? = null
)

@JsonClass(generateAdapter = true)
data class CalendarTmdbSeasonDto(
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episodes") val episodes: List<CalendarTmdbEpisodeDto>? = null
)

@JsonClass(generateAdapter = true)
data class CalendarTmdbReleaseDatesDto(
    @Json(name = "results") val results: List<CalendarTmdbReleaseCountryDto>? = null
)

@JsonClass(generateAdapter = true)
data class CalendarTmdbReleaseCountryDto(
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "release_dates") val releaseDates: List<CalendarTmdbReleaseDateDto>? = null
)

@JsonClass(generateAdapter = true)
data class CalendarTmdbReleaseDateDto(
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "type") val type: Int? = null
)
