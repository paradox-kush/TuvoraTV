// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.calendar.CalendarTmdbFindResponse
import com.nuvio.tv.data.remote.dto.calendar.CalendarTmdbReleaseDatesDto
import com.nuvio.tv.data.remote.dto.calendar.CalendarTmdbSeasonDto
import com.nuvio.tv.data.remote.dto.calendar.CalendarTmdbShowDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The TMDB reads the Calendar needs, on the app's TMDB Retrofit. Separate from [TmdbApi] because the
 * shared DTOs do not carry next/last episode to air, episode types or release-date types.
 */
interface CalendarTmdbApi {
    @GET("find/{external_id}")
    suspend fun findByImdb(
        @Path("external_id") imdbId: String,
        @Query("api_key") apiKey: String,
        @Query("external_source") externalSource: String = "imdb_id"
    ): Response<CalendarTmdbFindResponse>

    @GET("tv/{tv_id}")
    suspend fun getShow(@Path("tv_id") tvId: Long, @Query("api_key") apiKey: String): Response<CalendarTmdbShowDto>

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getSeason(
        @Path("tv_id") tvId: Long,
        @Path("season_number") season: Int,
        @Query("api_key") apiKey: String
    ): Response<CalendarTmdbSeasonDto>

    @GET("movie/{movie_id}/release_dates")
    suspend fun getReleaseDates(@Path("movie_id") movieId: Long, @Query("api_key") apiKey: String): Response<CalendarTmdbReleaseDatesDto>
}
