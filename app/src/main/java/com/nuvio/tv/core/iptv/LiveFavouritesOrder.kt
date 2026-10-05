package com.nuvio.tv.core.iptv

/**
 * F03 — the order of live-channel favourites, synced with no schema change (owner decision 2026-10-04,
 * option A): a favourite is a library item, and its synced "date added" (`library_items.added_at` =
 * [com.nuvio.tv.domain.model.SavedLibraryItem.addedAt]) IS its position — newest first, so a
 * new favourite lands on top. Reordering rewrites that value:
 *
 *  - [move] gives the moved favourite a value strictly between its new neighbours (one write);
 *  - only when they leave no room (equal or adjacent values) is the whole list renumbered, [STEP] apart
 *    and ending at the current top value, preserving the requested order.
 *
 * The cross-playlist "All favourites" row and each playlist's own row read the same order ([ordered]),
 * so a move on either shows up on both, and on every device. Pure; twin: NuvioMobile/
 * NuvioDesktop `features/iptv/LiveFavouritesOrder.kt` (same tests) and nuvio-web `src/lib/iptv/favouritesOrder.ts`.
 */
object LiveFavouritesOrder {

    data class Fav(val id: String, val savedAt: Long)

    const val STEP: Long = 1_000L

    fun ordered(favs: Collection<Fav>): List<Fav> =
        favs.sortedWith(compareByDescending<Fav> { it.savedAt }.thenBy { it.id })

    /**
     * Moves [id] to index [toIndex] of [ordered] (an [ordered] list). Returns only the favourites whose
     * value changes (empty = nothing to write).
     */
    fun move(ordered: List<Fav>, id: String, toIndex: Int): Map<String, Long> {
        val from = ordered.indexOfFirst { it.id == id }
        if (from < 0 || ordered.size < 2) return emptyMap()
        val to = toIndex.coerceIn(0, ordered.lastIndex)
        if (from == to) return emptyMap()
        val rest = ordered.toMutableList().apply { removeAt(from) }
        val above = rest.getOrNull(to - 1)
        val below = rest.getOrNull(to)
        val single: Long? = when {
            above == null && below != null -> below.savedAt + STEP
            below == null && above != null -> above.savedAt - STEP
            above != null && below != null && above.savedAt - below.savedAt >= 2 ->
                below.savedAt + (above.savedAt - below.savedAt) / 2
            else -> null
        }
        if (single != null) return mapOf(id to single)
        val reordered = rest.apply { add(to, ordered[from]) }
        // Counting DOWN to the current top keeps every value positive and at or above it.
        val top = ordered.maxOf { it.savedAt }
        val last = reordered.lastIndex
        return reordered.withIndex()
            .mapNotNull { (i, f) -> (top + (last - i) * STEP).takeIf { it != f.savedAt }?.let { f.id to it } }
            .toMap()
    }

    /** One step towards the top (-1) or the bottom (+1); empty when already at that end. */
    fun nudge(ordered: List<Fav>, id: String, delta: Int): Map<String, Long> {
        val from = ordered.indexOfFirst { it.id == id }
        if (from < 0) return emptyMap()
        return move(ordered, id, from + delta)
    }
}

/**
 * F03 — reordering PINNED channels within a group: the overlay already floats pinned channels to the
 * top ordered by their synced `position` ([com.nuvio.tv.core.iptv.overlay.IptvChannelOverlayPolicy]), so
 * a move assigns positions 0..n-1 to the group's pinned channels in the new order (a handful of rows,
 * all pushed as one overlay edit). Pure; twins: NuvioTV and the website editor.
 */
object PinnedChannelOrder {
    /** The (entityId, position) writes that put [entityId] at [toIndex] of [pinnedInOrder]. */
    fun move(pinnedInOrder: List<String>, entityId: String, toIndex: Int): List<Pair<String, Int>> {
        val from = pinnedInOrder.indexOf(entityId)
        if (from < 0) return emptyList()
        val to = toIndex.coerceIn(0, pinnedInOrder.lastIndex)
        if (from == to) return emptyList()
        val reordered = pinnedInOrder.toMutableList().apply { removeAt(from); add(to, entityId) }
        return reordered.mapIndexed { i, id -> id to i }
    }
}

