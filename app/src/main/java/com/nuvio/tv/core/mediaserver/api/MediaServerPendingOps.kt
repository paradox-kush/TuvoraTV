package com.nuvio.tv.core.mediaserver.api

import kotlinx.serialization.Serializable

/**
 * A durable, per-profile pending intent on a media-server entry (add / update / delete), kept in the
 * playlist-sync state beside the Xtream ops so the ONE engine reconciles both by intent (design 5.3: one
 * revision counter per profile - a second sync loop would conflict with the first on every push).
 * Same collapse rules as the playlist ops, minus the id-changing replace (a server entry's key is frozen).
 */
@Serializable
data class MediaServerPendingOp(
    val kind: String, // "add" | "update" | "delete"
    val key: String,
    val entry: MediaServerEntry? = null,
    /** For "update": the entry as last synced, so only the fields this device edited override the server's (B60-style). */
    val base: MediaServerEntry? = null,
)

object MediaServerPendingOps {
    fun List<MediaServerPendingOp>.recordAdd(entry: MediaServerEntry): List<MediaServerPendingOp> =
        filterNot { it.key == entry.key } + MediaServerPendingOp("add", entry.key, entry)

    /** An edit: stays an add when the entry was created this session; keeps the FIRST base otherwise. */
    fun List<MediaServerPendingOp>.recordUpdate(entry: MediaServerEntry, base: MediaServerEntry? = null): List<MediaServerPendingOp> {
        val existing = firstOrNull { it.key == entry.key }
        val op = when (existing?.kind) {
            "add" -> MediaServerPendingOp("add", entry.key, entry)
            "update" -> MediaServerPendingOp("update", entry.key, entry, base = existing.base)
            else -> MediaServerPendingOp("update", entry.key, entry, base = base)
        }
        return filterNot { it.key == entry.key } + op
    }

    /** A removal: an entry only ever added locally this session collapses to nothing; otherwise a delete is recorded. */
    fun List<MediaServerPendingOp>.recordDelete(key: String): List<MediaServerPendingOp> {
        val existing = firstOrNull { it.key == key }
        val rest = filterNot { it.key == key }
        return if (existing?.kind == "add") rest else rest + MediaServerPendingOp("delete", key)
    }

    /**
     * The pending intent replayed onto the server's authoritative rows [baseline] (never a blind overwrite,
     * never a set-union that resurrects a deleted entry):
     *  - add    -> the entry, replacing a same-key row, appended when new;
     *  - update -> applied to the server's row when it still exists (dropped when another device deleted it);
     *    with a [MediaServerPendingOp.base] only the synced fields THIS device changed override the server's;
     *  - delete -> the row is removed.
     */
    fun reconcile(baseline: List<MediaServerEntry>, ops: List<MediaServerPendingOp>): List<MediaServerEntry> {
        var rows = baseline
        for (op in ops) {
            when (op.kind) {
                "add" -> op.entry?.let { e ->
                    rows = if (rows.any { it.key == e.key }) rows.map { if (it.key == e.key) e else it } else rows + e
                }
                "update" -> op.entry?.let { e ->
                    val server = rows.firstOrNull { it.key == e.key } ?: return@let
                    rows = rows.map { if (it.key == e.key) mergeEdited(server, e, op.base) else it }
                }
                "delete" -> rows = rows.filterNot { it.key == op.key }
            }
        }
        return rows
    }

    private fun mergeEdited(server: MediaServerEntry, edited: MediaServerEntry, base: MediaServerEntry?): MediaServerEntry {
        if (base == null) return edited
        return server.copy(
            name = if (edited.name != base.name) edited.name else server.name,
            enabled = if (edited.enabled != base.enabled) edited.enabled else server.enabled,
            userId = if (edited.userId != base.userId) edited.userId else server.userId,
            address = if (MediaServerSyncCodec.syncedAddress(edited) != MediaServerSyncCodec.syncedAddress(base)) edited.address else server.address,
            syncAddress = edited.syncAddress,
            userName = edited.userName,
            homeRows = edited.homeRows,
            homeLibraries = edited.homeLibraries,
        )
    }

    /**
     * Applies server rows [remote] as this device's new list while keeping what only this device knows:
     * the signed-in user's display name, the opted-in Home rows, the address toggle, and a typed address the
     * server row does not carry (another device may sync none). Server order wins.
     */
    fun applyRemote(remote: List<MediaServerEntry>, local: List<MediaServerEntry>): List<MediaServerEntry> =
        remote.map { r ->
            val l = local.firstOrNull { it.key == r.key } ?: return@map r
            r.copy(
                address = r.address ?: l.address,
                syncAddress = l.syncAddress,
                userName = if (r.userId == l.userId) l.userName else null,
                homeRows = l.homeRows,
                homeLibraries = l.homeLibraries,
            )
        }
}
