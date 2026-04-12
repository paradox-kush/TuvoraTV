package com.nuvio.tv.data.remote.dto.mal

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from `POST https://myanimelist.net/v1/oauth2/token` (both
 * `authorization_code` and `refresh_token` grants). MAL access tokens are
 * short-lived (1 h); refresh tokens live ~30 days.
 */
@JsonClass(generateAdapter = true)
data class MalTokenResponseDto(
    @Json(name = "token_type") val tokenType: String = "Bearer",
    @Json(name = "expires_in") val expiresIn: Long = 3600,
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String
)
