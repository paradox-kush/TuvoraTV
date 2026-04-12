package com.nuvio.tv.core.anime

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Fetches the consolidated mappings file from the PlexAniBridge-Mappings repo.
 * This is the best community source for per-episode absolute-numbering offsets
 * (long-runners like One Piece, Naruto, Detective Conan, Dragon Ball).
 *
 * The file is a JSON object keyed by AniList id. Each entry contains the
 * cross-reference IDs plus a `tvdb_mappings` sub-object whose keys are TVDB
 * season+episode ranges and whose values are the corresponding AniList episode
 * ranges. See https://github.com/eliasbenb/PlexAniBridge-Mappings for the
 * authoritative spec. Schema assumptions in [AnimeMappingEntryDto] below are
 * deliberately lenient; unknown keys are ignored by Moshi.
 *
 * The file is downloaded lazily on first use and cached on disk for 24 h.
 */
interface AnimeMappingsApi {
    @GET
    suspend fun getMappings(@Url url: String): Response<Map<String, AnimeMappingEntryDto>>
}

@JsonClass(generateAdapter = true)
data class AnimeMappingEntryDto(
    @Json(name = "anidb_id") val anidbId: Int? = null,
    @Json(name = "anilist_id") val anilistId: Int? = null,
    @Json(name = "mal_id") val malId: Int? = null,
    @Json(name = "kitsu_id") val kitsuId: Int? = null,
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "tmdb_movie_id") val tmdbMovieId: Int? = null,
    @Json(name = "tmdb_show_id") val tmdbShowId: Int? = null,
    @Json(name = "tvdb_id") val tvdbId: Int? = null,
    /**
     * Keys like "s1", "s2e1-e12", "s1e1-e12|s2e1-e12". Values are empty string
     * (entire TVDB range maps 1:1 to this AniList entry) or a range expression
     * for the AniList side. Parsing lives in [EpisodeOffsetMapper].
     */
    @Json(name = "tvdb_mappings") val tvdbMappings: Map<String, String>? = null,
    @Json(name = "tmdb_mappings") val tmdbMappings: Map<String, String>? = null,
    @Json(name = "length") val length: Int? = null
)

object AnimeMappingsSource {
    /**
     * Mirror of the PlexAniBridge-Mappings consolidated file. Single JSON blob,
     * ~2 MB, updated frequently. Raw GitHub is fine for reads with no auth.
     */
    const val PLEX_ANI_BRIDGE_URL =
        "https://raw.githubusercontent.com/eliasbenb/PlexAniBridge-Mappings/main/mappings.json"
}
