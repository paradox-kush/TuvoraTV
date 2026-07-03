package com.nuvio.tv.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class SupabasePlugin(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("repo_type") val repoType: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SupabaseAddon(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Row of `public.iptv_playlists` (playlist-manager v2 sync table). Only the columns this
 * client reads are modelled; unknown columns are ignored by the supabase-kt serializer.
 * `category_selections` is jsonb — kept as a raw [JsonElement] and decoded leniently in
 * XtreamAccountSyncService (a malformed object degrades to "all categories", never throws).
 */
@Serializable
data class SupabaseIptvPlaylist(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("source_type") val sourceType: String = "xtream",
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("base_url") val baseUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("epg_url") val epgUrl: String? = null,
    @SerialName("dns_provider") val dnsProvider: String = "system",
    @SerialName("auto_refresh_hours") val autoRefreshHours: Int = 0,
    @SerialName("content_types") val contentTypes: List<String> = listOf("live", "movies", "series"),
    @SerialName("category_selections") val categorySelections: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SupabaseXtreamAccount(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("base_url") val baseUrl: String,
    val username: String,
    val password: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SyncCodeResult(
    val code: String
)

@Serializable
data class ClaimSyncResult(
    @SerialName("result_owner_id") val ownerId: String? = null,
    val success: Boolean,
    val message: String
)

@Serializable
data class SupabaseLinkedDevice(
    val id: String? = null,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_user_id") val deviceUserId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("linked_at") val linkedAt: String? = null
)

@Serializable
data class TvLoginStartResult(
    val code: String,
    @SerialName("web_url") val webUrl: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3
)

@Serializable
data class TvLoginPollResult(
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int? = null
)

@Serializable
data class TvLoginExchangeResult(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
)

/**
 * Result of `create_iptv_pairing(p_code, p_code_hash)` — the P5 IPTV playlist pairing RPC
 * (anon-callable; the not-signed-in TV shows this code + a QR that deep-links the web form).
 * `expiresAt` is a UTC ISO-8601 string (the RPC returns text, not a timestamptz).
 */
@Serializable
data class IptvPairingStartResult(
    val code: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3
)

/**
 * Result of `poll_iptv_pairing(p_code, p_code_hash)`. `status` is pending | consumed | expired.
 * `payload` is the submitted playlist row (shaped like a `sync_push_iptv_playlists` row) and is
 * present EXACTLY ONCE — when the status first flips to consumed — then withheld (null) on later
 * polls. Kept as a raw [JsonElement] so the pairing payload -> XtreamAccount mapping stays lenient.
 */
@Serializable
data class IptvPairingPollResult(
    val status: String,
    val payload: JsonElement? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int? = null
)

@Serializable
data class SupabaseWatchProgress(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long,
    val duration: Long,
    @SerialName("last_watched") val lastWatched: Long,
    @SerialName("progress_key") val progressKey: String,
    @SerialName("profile_id") val profileId: Int = 1
)

@Serializable
data class SupabaseWatchProgressEvent(
    @SerialName("event_id") val eventId: Long,
    val operation: String,
    @SerialName("progress_key") val progressKey: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long,
    val duration: Long,
    @SerialName("last_watched") val lastWatched: Long
)

@Serializable
data class SupabaseLibraryItem(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val name: String = "",
    val poster: String? = null,
    @SerialName("poster_shape") val posterShape: String = "POSTER",
    val background: String? = null,
    val description: String? = null,
    @SerialName("release_info") val releaseInfo: String? = null,
    @SerialName("imdb_rating") val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    @SerialName("addon_base_url") val addonBaseUrl: String? = null,
    @SerialName("added_at") val addedAt: Long = 0,
    @SerialName("profile_id") val profileId: Int = 1
)

@Serializable
data class SupabaseWatchedItem(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("watched_at") val watchedAt: Long,
    @SerialName("profile_id") val profileId: Int = 1
)

@Serializable
data class SupabaseWatchedItemEvent(
    @SerialName("event_id") val eventId: Long,
    val operation: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("watched_at") val watchedAt: Long
)

@Serializable
data class SupabaseProfile(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("profile_index") val profileIndex: Int,
    val name: String = "",
    @SerialName("avatar_color_hex") val avatarColorHex: String = "#1E88E5",
    @SerialName("uses_primary_addons") val usesPrimaryAddons: Boolean = false,
    @SerialName("uses_primary_plugins") val usesPrimaryPlugins: Boolean = false,
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SupabaseProfileLockState(
    @SerialName("profile_index") val profileIndex: Int,
    @SerialName("pin_enabled") val pinEnabled: Boolean = false,
    @SerialName("pin_locked_until") val pinLockedUntil: String? = null
)

@Serializable
data class SupabaseProfilePinVerifyResult(
    val unlocked: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int = 0
)

@Serializable
data class SupabaseAvatarCatalogItem(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("storage_path") val storagePath: String,
    val category: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("bg_color") val bgColor: String? = null
)

@Serializable
data class SupabaseProfileSettingsBlob(
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("settings_json") val settingsJson: JsonObject = buildJsonObject { },
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SupabaseCollectionBlob(
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("collections_json") val collectionsJson: JsonElement = JsonArray(emptyList()),
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SupabaseHomeCatalogSettingsBlob(
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("settings_json") val settingsJson: JsonObject = buildJsonObject { },
    @SerialName("updated_at") val updatedAt: String? = null
)
