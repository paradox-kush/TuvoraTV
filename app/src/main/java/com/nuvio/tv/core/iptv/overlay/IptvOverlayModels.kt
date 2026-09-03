package com.nuvio.tv.core.iptv.overlay

/**
 * Neutral value types for the IPTV personalization overlay — the durable, user-editable layer that
 * sits over the disposable provider catalog. Everything here is keyed on the FROZEN canon-v1 identity
 * (channel `entity_id` "fp:v1:…" / category key "c:v1:…"), so an edit survives a provider stream_id
 * renumber and a catalog rebuild, and matches the id the website wrote it under. Consumer-neutral so
 * they can appear in the public [com.nuvio.app.core.contracts] port.
 */

/** Per-channel edit, stored under the channel's entity id. */
data class ChannelOverlay(
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val position: Int? = null,
    val rename: String? = null,
) {
    val isNoop: Boolean get() = !hidden && !pinned && position == null && rename == null
}

/** Per-category edit, stored under the category key. */
data class CategoryOverlay(
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val position: Int? = null,
    val rename: String? = null,
) {
    val isNoop: Boolean get() = !hidden && !pinned && position == null && rename == null
}

/**
 * A user-defined group (an "app-minted" opaque id, NOT identity-derived). Appears as its own row,
 * pinned above the provider categories, listing [memberEntityIds] in order.
 */
data class CustomGroup(
    val id: String,
    val contentType: String,      // "live" | "movies" | "series"
    val playlistId: String?,      // null = spans playlists
    val name: String,
    val position: Int,
    val memberEntityIds: List<String> = emptyList(),
)

/**
 * The active profile's overlay, holding only the EDITED entities (sparse — a user personalizes dozens,
 * not the 100k-channel catalog). The read layer consults it cheaply while composing.
 */
data class OverlaySnapshot(
    val channels: Map<String, ChannelOverlay> = emptyMap(),      // entityId -> edit
    val categories: Map<String, CategoryOverlay> = emptyMap(),   // categoryKey -> edit
    val groups: List<CustomGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && categories.isEmpty() && groups.isEmpty()

    companion object {
        val EMPTY = OverlaySnapshot()
    }
}

/** A local overlay row shaped for the delta-sync push (the backend's unified kind/okey/value form). */
data class OverlayPushRow(
    val kind: String,
    val okey: String,
    val playlistId: String?,
    val valueJson: String,
    val updatedAt: Long,
    val deleted: Boolean,
)
