package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.mal.MalAnimeListPageDto
import com.nuvio.tv.data.remote.dto.mal.MalListStatusDto
import com.nuvio.tv.data.remote.dto.mal.MalUserDto
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * MyAnimeList API v2. Authentication is a Bearer token attached by the
 * `@Named("mal")` OkHttp interceptor — callers do not supply it here.
 *
 * Base URL: `https://api.myanimelist.net/v2/`
 * Docs:    https://myanimelist.net/apiconfig/references/api/v2
 *
 * Status wire values: watching | completed | on_hold | dropped | plan_to_watch
 */
interface MalApi {

    /** Default field set returned for list entries. Callers may override. */
    object Fields {
        const val LIST_DEFAULT =
            "list_status," +
            "num_episodes," +
            "media_type," +
            "main_picture," +
            "alternative_titles{en,ja}," +
            "start_season"
    }

    @GET("users/@me/animelist")
    suspend fun getUserAnimeList(
        @Query("status") status: String? = null,
        @Query("sort") sort: String = "list_updated_at",
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("fields") fields: String = Fields.LIST_DEFAULT,
        @Query("nsfw") nsfw: Boolean = true
    ): Response<MalAnimeListPageDto>

    /** Follow the MAL `paging.next` absolute URL. */
    @GET
    suspend fun getUserAnimeListPage(@Url url: String): Response<MalAnimeListPageDto>

    /**
     * Update progress / status on one anime. All fields are optional; pass
     * null to leave untouched. Returns the updated list_status.
     */
    @FormUrlEncoded
    @PATCH("anime/{anime_id}/my_list_status")
    suspend fun updateListStatus(
        @Path("anime_id") animeId: Int,
        @Field("status") status: String? = null,
        @Field("num_watched_episodes") numWatchedEpisodes: Int? = null,
        @Field("score") score: Int? = null,
        @Field("is_rewatching") isRewatching: Boolean? = null
    ): Response<MalListStatusDto>

    @DELETE("anime/{anime_id}/my_list_status")
    suspend fun removeFromList(@Path("anime_id") animeId: Int): Response<Unit>

    @GET("users/@me")
    suspend fun getMe(
        @Query("fields") fields: String = ""
    ): Response<MalUserDto>

    /**
     * Fetch a single anime including the authenticated user's list_status.
     * Used by the fanout service to read current progress before writing so
     * the monotonic rule can no-op when the user is already further ahead.
     */
    @GET("anime/{anime_id}")
    suspend fun getAnimeWithMyStatus(
        @Path("anime_id") animeId: Int,
        @Query("fields") fields: String = "my_list_status,num_episodes,media_type"
    ): Response<com.nuvio.tv.data.remote.dto.mal.MalAnimeWithStatusDto>
}
