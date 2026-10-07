package com.nuvio.tv.core.mediaserver.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The second row mapper of the one playlist-sync engine (design 5.3 / 5.10): how a media-server ENTRY maps
 * to and from an `iptv_playlists` row. Pure - the engine ([PlaylistV2SyncEngine]) owns the transport, the
 * revision counter and the reconcile loop; this file owns only the shape of a server row. Twins: nuvio-web
 * `src/lib/iptv/mediaServer.ts` (key + display) and the SQL `playlist_key_media_server()` - all three pin
 * the same golden vectors.
 *
 * Contract (lane JW):
 *  - the row ALWAYS carries an explicit `playlist_key` = `{type}|{machineId}|{userId}` (never the
 *    address-derived fallback, which would bake the typed address into the permanent key);
 *  - `url` = `{type}://{machineId}`, `username` = the server user id, `name`, `enabled`, `sort_order`;
 *  - `base_url` (the typed address) only while the per-server toggle is on (D7);
 *  - NEVER a password, token, MAC or device id - the table has no token column and the backend scrubs the
 *    legacy credential columns on these rows, but the client must not send them in the first place (D1).
 */
object MediaServerSyncCodec {
    /** The `source_type`s a media-server row can have (Plex is parked: no key, no code). */
    val SOURCE_TYPES: List<String> = MediaServerType.wireValues

    /**
     * `{type}|{machineId}|{userId}`, ids exactly as the server reported them (trimmed, case kept). Null for
     * any other type, or when a segment is blank or carries `|`, `/`, `:` or whitespace (an address is not
     * an identity). Golden vectors: nuvio-web `mediaServer.test.ts`.
     */
    fun playlistKey(type: String, machineId: String, userId: String): String? {
        if (MediaServerType.fromWire(type) == null) return null
        val machine = machineId.trim()
        val user = userId.trim()
        if (!isSegment(machine) || !isSegment(user)) return null
        return "$type|$machine|$user"
    }

    fun playlistKey(type: MediaServerType, machineId: String, userId: String): String? =
        playlistKey(type.wire, machineId, userId)

    private fun isSegment(value: String): Boolean =
        value.isNotEmpty() && value.none { it == '|' || it == '/' || it == ':' || it.isWhitespace() }

    /** The `url` column: the machine id as an identity URL (never reachable, never the typed address). */
    fun identityUrl(type: MediaServerType, machineId: String): String = "${type.wire}://$machineId"

    /** The columns of a pulled row this mapper reads (kept free of the iptv row type so the mapper stays pure). */
    data class RowColumns(
        val playlistKey: String?,
        val sourceType: String?,
        val name: String?,
        val enabled: Boolean,
        val baseUrl: String?,
        val url: String?,
        val username: String?,
    )

    /**
     * A pulled row as an entry, or null when it is not a media-server row (any other source type) or is
     * unusable (no recoverable identity). The key stays the server's `playlist_key` (frozen); machine id
     * comes from it, the user id from the `username` column (the CURRENT user - a re-login as another user
     * keeps the key but changes the column).
     */
    fun entryFromRow(row: RowColumns): MediaServerEntry? {
        val type = MediaServerType.fromWire(row.sourceType) ?: return null
        val parts = row.playlistKey?.trim()?.split('|').orEmpty()
        val keyParts = parts.takeIf { it.size == 3 && it[0] == type.wire && isSegment(it[1]) && isSegment(it[2]) }
        val urlPrefix = "${type.wire}://"
        val urlMachine = row.url?.trim()?.takeIf { it.startsWith(urlPrefix) }?.removePrefix(urlPrefix)?.takeIf { isSegment(it) }
        // A key that is present but not this type's shape (or another type's) is a corrupt row, never "fixed" from the other columns:
        // the server's CHECK makes it impossible, and a recovered key would differ from the stored one on the next push.
        if (!row.playlistKey.isNullOrBlank() && keyParts == null) return null
        val machineId = keyParts?.get(1) ?: urlMachine ?: return null
        val userId = row.username?.trim()?.takeIf { isSegment(it) } ?: keyParts?.get(2) ?: return null
        val key = keyParts?.let { row.playlistKey!!.trim() } ?: playlistKey(type, machineId, userId) ?: return null
        return MediaServerEntry(
            key = key,
            type = type,
            machineId = machineId,
            userId = userId,
            name = row.name?.takeIf { it.isNotBlank() } ?: type.productName,
            address = row.baseUrl?.trim()?.takeIf { it.isNotEmpty() },
            enabled = row.enabled,
        )
    }

    /** The address as the server row carries it: the typed address while the D7 toggle is on, else nothing. */
    fun syncedAddress(entry: MediaServerEntry): String? = if (entry.syncAddress) entry.address else null

    /**
     * The push row of [entry] at [sortOrder]. Omissions are contract: with the toggle off `base_url` is absent
     * (the server stores NULL - the address is cleared for every device); no credential field exists here.
     */
    fun pushRow(entry: MediaServerEntry, sortOrder: Int): JsonObject = buildJsonObject {
        put("playlist_key", entry.key)
        put("source_type", entry.type.wire)
        put("name", entry.name)
        put("enabled", entry.enabled)
        put("sort_order", sortOrder)
        put("url", identityUrl(entry.type, entry.machineId))
        put("username", entry.userId)
        syncedAddress(entry)?.let { put("base_url", it) }
    }

    /** Everything the server stores for [entry] - the value two rows must share to count as "in sync". */
    fun syncedFingerprint(entry: MediaServerEntry, sortOrder: Int): String = pushRow(entry, sortOrder).toString()
}
