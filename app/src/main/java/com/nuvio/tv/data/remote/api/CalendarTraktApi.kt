// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.calendar.TraktCalendarEpisodeDto
import com.nuvio.tv.data.remote.dto.calendar.TraktCalendarShowDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/** The two Trakt endpoints the Calendar reads, on the app's Trakt Retrofit (kept apart from [TraktApi]). */
interface CalendarTraktApi {
    @GET("calendars/my/shows/{start_date}/{days}")
    suspend fun getMyShowsCalendar(
        @Header("Authorization") authorization: String,
        @Path("start_date") startDate: String,
        @Path("days") days: Int,
        @Query("extended") extended: String = "full"
    ): Response<List<TraktCalendarShowDto>>

    @GET("shows/{id}/next_episode")
    suspend fun getShowNextEpisode(
        @Path("id") id: String,
        @Query("extended") extended: String = "full"
    ): Response<TraktCalendarEpisodeDto>
}
