package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.StreamResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface DirectDebridStreamApi {
    @GET
    suspend fun getClientStreams(@Url url: String): Response<StreamResponseDto>
}
