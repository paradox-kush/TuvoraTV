package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.XtreamAccountDto
import com.nuvio.tv.data.remote.dto.XtreamCategoryDto
import com.nuvio.tv.data.remote.dto.XtreamLiveStreamDto
import com.nuvio.tv.data.remote.dto.XtreamSeriesDto
import com.nuvio.tv.data.remote.dto.XtreamSeriesInfoResponseDto
import com.nuvio.tv.data.remote.dto.XtreamShortEpgResponseDto
import com.nuvio.tv.data.remote.dto.XtreamVodInfoResponseDto
import com.nuvio.tv.data.remote.dto.XtreamVodStreamDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Xtream Codes API. Every call hits `player_api.php` on the user's own panel,
 * so URLs are built by [com.nuvio.tv.core.iptv.XtreamClient] and passed via @Url
 * (same pattern as [AddonApi]).
 */
interface XtreamApi {

    @GET
    suspend fun getAccount(@Url url: String): Response<XtreamAccountDto>

    @GET
    suspend fun getCategories(@Url url: String): Response<List<XtreamCategoryDto>>

    @GET
    suspend fun getLiveStreams(@Url url: String): Response<List<XtreamLiveStreamDto>>

    @GET
    suspend fun getVodStreams(@Url url: String): Response<List<XtreamVodStreamDto>>

    @GET
    suspend fun getSeries(@Url url: String): Response<List<XtreamSeriesDto>>

    @GET
    suspend fun getShortEpg(@Url url: String): Response<XtreamShortEpgResponseDto>

    @GET
    suspend fun getVodInfo(@Url url: String): Response<XtreamVodInfoResponseDto>

    @GET
    suspend fun getSeriesInfo(@Url url: String): Response<XtreamSeriesInfoResponseDto>
}
