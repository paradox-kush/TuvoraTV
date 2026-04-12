package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.anilist.AniListGraphQLRequest
import com.nuvio.tv.data.remote.dto.anilist.AniListMediaListCollectionResponse
import com.nuvio.tv.data.remote.dto.anilist.AniListMediaListEntryResponse
import com.nuvio.tv.data.remote.dto.anilist.AniListSaveMediaListEntryResponse
import com.nuvio.tv.data.remote.dto.anilist.AniListViewerResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * AniList GraphQL endpoint. Base URL is the full graphql endpoint so every
 * call is a `POST ""`. Auth is a Bearer token attached by the `@Named("anilist")`
 * OkHttp interceptor.
 *
 * Separate typed methods are provided per query shape to avoid paying the
 * cost of generic response parsing — Moshi with code-gen adapters is faster
 * than reflective deserialization.
 */
interface AniListApi {
    @POST(".")
    suspend fun viewer(@Body request: AniListGraphQLRequest): Response<AniListViewerResponse>

    @POST(".")
    suspend fun mediaListCollection(
        @Body request: AniListGraphQLRequest
    ): Response<AniListMediaListCollectionResponse>

    @POST(".")
    suspend fun mediaListEntry(
        @Body request: AniListGraphQLRequest
    ): Response<AniListMediaListEntryResponse>

    @POST(".")
    suspend fun saveMediaListEntry(
        @Body request: AniListGraphQLRequest
    ): Response<AniListSaveMediaListEntryResponse>
}
