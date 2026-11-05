// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.calendar.TvMazeEpisodeDto
import com.nuvio.tv.data.remote.dto.calendar.TvMazeSeasonDto
import com.nuvio.tv.data.remote.dto.calendar.TvMazeShowDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** TVmaze's public API (https://api.tvmaze.com). No key; the Calendar keeps under its 20 calls / 10 s. */
interface TvMazeApi {

    @GET("lookup/shows")
    suspend fun lookupByImdb(@Query("imdb") imdbId: String): Response<TvMazeShowDto>

    @GET("lookup/shows")
    suspend fun lookupByTvdb(@Query("thetvdb") tvdbId: Long): Response<TvMazeShowDto>

    @GET("shows/{id}")
    suspend fun getShow(
        @Path("id") id: Int,
        @Query("embed[]") embed: List<String> = listOf("nextepisode", "previousepisode", "seasons")
    ): Response<TvMazeShowDto>

    @GET("shows/{id}/seasons")
    suspend fun getSeasons(@Path("id") id: Int): Response<List<TvMazeSeasonDto>>

    @GET("seasons/{id}/episodes")
    suspend fun getSeasonEpisodes(@Path("id") seasonId: Int): Response<List<TvMazeEpisodeDto>>
}
