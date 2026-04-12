package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryCreateDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryEntryResponseDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryPageDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryPatchDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuUserPageDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Kitsu JSON:API client.
 *
 * Base URL: `https://kitsu.io/api/edge/`
 * Docs:    https://kitsu.docs.apiary.io/
 *
 * Bracket-notation filters like `filter[user_id]` are sent encoded already;
 * [encoded] is true so Retrofit doesn't double-escape the brackets — most
 * JSON:API servers tolerate either form but percent-encoded brackets caused
 * occasional 400s during development.
 *
 * Auth is a Bearer token attached by the `@Named("kitsu")` OkHttp interceptor
 * (Doorkeeper OAuth); unauthenticated reads of public data also work but all
 * list endpoints here require auth.
 */
interface KitsuApi {

    object Fields {
        const val LIBRARY_ENTRIES =
            "status,progress,ratingTwenty,reconsuming,reconsumeCount,progressedAt,startedAt,finishedAt,updatedAt,anime,user"
        const val ANIME =
            "canonicalTitle,titles,posterImage,coverImage,episodeCount,episodeLength,slug,subtype,showType,startDate,mappings"
        const val MAPPINGS = "externalSite,externalId"
    }

    @GET("library-entries")
    suspend fun getLibrary(
        @Query("filter[user_id]", encoded = true) userId: String,
        @Query("filter[kind]", encoded = true) kind: String = "anime",
        @Query("filter[status]", encoded = true) status: String? = null,
        @Query("include") include: String = "anime,anime.mappings",
        @Query("fields[libraryEntries]", encoded = true) libraryFields: String = Fields.LIBRARY_ENTRIES,
        @Query("fields[anime]", encoded = true) animeFields: String = Fields.ANIME,
        @Query("fields[mappings]", encoded = true) mappingFields: String = Fields.MAPPINGS,
        @Query("page[limit]", encoded = true) limit: Int = 500,
        @Query("page[offset]", encoded = true) offset: Int = 0,
        @Query("sort") sort: String = "-progressedAt"
    ): Response<KitsuLibraryPageDto>

    /** Follow JSON:API `links.next` absolute URL. */
    @GET
    suspend fun getLibraryPage(@Url url: String): Response<KitsuLibraryPageDto>

    @PATCH("library-entries/{id}")
    suspend fun updateEntry(
        @Path("id") id: String,
        @Body body: KitsuLibraryPatchDto
    ): Response<KitsuLibraryEntryResponseDto>

    @POST("library-entries")
    suspend fun createEntry(
        @Body body: KitsuLibraryCreateDto
    ): Response<KitsuLibraryEntryResponseDto>

    @DELETE("library-entries/{id}")
    suspend fun deleteEntry(@Path("id") id: String): Response<Unit>

    @GET("users")
    suspend fun getSelf(
        @Query("filter[self]", encoded = true) self: Boolean = true
    ): Response<KitsuUserPageDto>
}
