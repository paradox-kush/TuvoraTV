package com.nuvio.tv.data.remote.dto.kitsu

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from `POST https://kitsu.io/api/oauth/token`. Kitsu uses Doorkeeper
 * and supports both `password` and `refresh_token` grants with no client id
 * required. Access tokens live ~30 days; refresh tokens persist until revoked.
 *
 * `created_at` is seconds-since-epoch, not ISO-8601 — matches Trakt's wire
 * format, hence the Long type.
 */
@JsonClass(generateAdapter = true)
data class KitsuTokenResponseDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "token_type") val tokenType: String = "Bearer",
    @Json(name = "expires_in") val expiresIn: Long = 2_629_743,
    @Json(name = "scope") val scope: String? = null,
    @Json(name = "created_at") val createdAt: Long = 0L
)
