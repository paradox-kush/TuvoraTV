package com.nuvio.tv.core.mediaserver.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The two server products this client speaks (one MediaBrowser-family client, two dialects). Plex is parked. */
@Serializable
enum class MediaServerType(val wire: String, val productName: String) {
    @SerialName("jellyfin") JELLYFIN("jellyfin", "Jellyfin"),
    @SerialName("emby") EMBY("emby", "Emby");

    companion object {
        /** The lowercase wire value (`iptv_playlists.source_type`), or null for anything else (incl. "plex", "xtream"). */
        fun fromWire(value: String?): MediaServerType? = entries.firstOrNull { it.wire == value }

        val wireValues: List<String> = entries.map { it.wire }
    }
}

/**
 * The server rows a user can opt into on Home, per server (design D2). Tuvora's own rows are always on;
 * these are the server's own shelves and are OFF until enabled in Settings. Device-local - not synced.
 */
@Serializable
enum class MediaServerHomeRow {
    @SerialName("continue_watching") CONTINUE_WATCHING,
    @SerialName("next_up") NEXT_UP,
    @SerialName("recently_added") RECENTLY_ADDED,
}

/**
 * One media-server entry of one Tuvora profile - the thing that syncs (design 5.3). It is a server
 * ADDRESS + a user id, never a credential: the session token lives per device in the secure store and
 * never leaves it (D1).
 *
 * Synced columns (`iptv_playlists`): [key] (`playlist_key`), [type] (`source_type`), `url` =
 * `{type}://{machineId}`, [userId] (`username`), [name], [enabled], sort order = list position, and
 * [address] (`base_url`) only while [syncAddress] is on (D7). Everything else is device-local.
 */
@Serializable
data class MediaServerEntry(
    /** The permanent sync identity `{type}|{machineId}|{userId}`, frozen at creation (a re-login as another user keeps it). */
    val key: String,
    val type: MediaServerType,
    /** The server's own `Id` from `GET /System/Info/Public`. */
    val machineId: String,
    /** The server user this entry signs in as (the CURRENT user - differs from the key's after a re-login as another user). */
    val userId: String,
    val name: String,
    /** The address the user typed (reverse-proxy base path included), or null when this device does not know one (not synced from another device). */
    val address: String? = null,
    /** D7: send [address] to the Tuvora account so other devices can prefill it. Per server, on by default. */
    val syncAddress: Boolean = true,
    val enabled: Boolean = true,
    /** Device-local display name of the signed-in user (never synced - the wire carries the user id only). */
    val userName: String? = null,
    /** D2: the server's own shelves the user enabled on Home. Device-local. */
    val homeRows: Set<MediaServerHomeRow> = emptySet(),
    /** Libraries (server view id -> its name) the user put on Home as a row of their own. Device-local, like [homeRows]. */
    val homeLibraries: Map<String, String> = emptyMap(),
) {
    /** `{type}:{machineId}:{userId}` (design 4) - what content ids and deferred URLs embed. */
    val serverKey: String get() = "${type.wire}:$machineId:$userId"

    /** `{type}:{machineId}` - the source identity that is stable across a re-login (Home section keys, "see all" targets). */
    val sourceKey: String get() = "${type.wire}:$machineId"
}
