package com.nuvio.tv.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Row returned by `get_tracker_tokens(p_profile_id)` — one per tracker
 * (MAL/AniList/Kitsu) that the effective sync-owner has connected for this
 * profile. Used by [com.nuvio.tv.data.repository.TrackerTokenSyncService] to
 * hydrate the per-tracker [com.nuvio.tv.data.local.MalAuthDataStore] etc.
 * on startup.
 */
@Serializable
data class UserTrackerTokensRow(
    @SerialName("user_id") val userId: String,
    @SerialName("profile_id") val profileId: Int,
    val tracker: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    /** ISO-8601 — may be null when the tracker didn't supply an expiry (AniList). */
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("tracker_user_id") val trackerUserId: String? = null,
    @SerialName("tracker_username") val trackerUsername: String? = null,
    @SerialName("updated_at") val updatedAt: String
)

/**
 * Row returned by `get_profile_tracker_settings(p_profile_id)` — one per
 * tracker with the user's catalog-row preferences and progress-sync toggle.
 */
@Serializable
data class ProfileTrackerSettingsRow(
    @SerialName("user_id") val userId: String,
    @SerialName("profile_id") val profileId: Int,
    val tracker: String,
    @SerialName("enabled_statuses") val enabledStatuses: List<String> = emptyList(),
    @SerialName("row_order") val rowOrder: List<String> = emptyList(),
    @SerialName("send_progress") val sendProgress: Boolean = true,
    @SerialName("updated_at") val updatedAt: String
)
