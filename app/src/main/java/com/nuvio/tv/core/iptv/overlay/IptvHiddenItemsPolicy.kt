package com.nuvio.tv.core.iptv.overlay

import com.nuvio.tv.core.iptv.identity.IptvIdentity

/**
 * What "hidden" means outside the browse hub (F02 step 1, B65). Pure.
 *
 * - The live guide drops a channel whose category is hidden (website or device) or switched off in
 *   the playlist's in-app category settings, the same way the hub drops the category itself. Before
 *   this the Mobile/Desktop guide and TV's "All channels" still listed a hidden group's channels.
 * - The "Hidden channels & groups" list resolves the overlay's hidden keys back to names, so a hide
 *   made on a device can be undone on a device. The overlay stores only hashed keys, so an entry is
 *   listed when the playlist's current catalog still has it.
 *
 * TV twin of NuvioMobile's `features/iptv/overlay/IptvHiddenItemsPolicy`.
 */
object IptvHiddenItemsPolicy {

    data class NamedCategory(val id: String, val name: String)

    /** The ids in [categories] whose category key the overlay hides for [playlistId] and [contentType]. */
    fun hiddenCategoryIds(
        playlistId: String,
        contentType: String,
        categories: List<NamedCategory>,
        overlay: Map<String, CategoryOverlay>,
    ): Set<String> {
        if (overlay.values.none { it.hidden }) return emptySet()
        return categories
            .filter { overlay[IptvIdentity.categoryKey(playlistId, contentType, it.name)]?.hidden == true }
            .mapTo(mutableSetOf()) { it.id }
    }

    /**
     * The guide's channels: [items] without those in a hidden category or one the in-app selection
     * excludes ([allowedBySelection] carries that rule, including its handling of an unknown category).
     */
    fun <T> guideChannels(
        items: List<T>,
        hiddenCategoryIds: Set<String>,
        allowedBySelection: (String?) -> Boolean,
        categoryOf: (T) -> String?,
    ): List<T> = items.filter { item ->
        val category = categoryOf(item)
        allowedBySelection(category) && (category == null || category !in hiddenCategoryIds)
    }

    enum class HiddenKind { GROUP, CHANNEL }

    /** One row of the "Hidden channels & groups" list. [key] is what the unhide intent takes. */
    data class HiddenItem(val kind: HiddenKind, val contentType: String, val key: String, val name: String)

    data class CatalogChannel(val entityId: String, val name: String)
    data class CatalogCategory(val contentType: String, val key: String, val name: String)

    private val TYPE_ORDER = listOf("live", "movies", "series")

    /** Hidden groups (live, movies, series, then by name), then hidden channels by name. */
    fun hiddenItems(
        channels: List<CatalogChannel>,
        categories: List<CatalogCategory>,
        overlay: OverlaySnapshot,
    ): List<HiddenItem> {
        val groups = categories
            .filter { overlay.categories[it.key]?.hidden == true }
            .distinctBy { it.key }
            .map { HiddenItem(HiddenKind.GROUP, it.contentType, it.key, displayName(overlay.categories[it.key]?.rename, it.name)) }
            .sortedWith(compareBy({ TYPE_ORDER.indexOf(it.contentType).let { i -> if (i < 0) TYPE_ORDER.size else i } }, { it.name.lowercase() }))
        val hiddenChannels = channels
            .filter { overlay.channels[it.entityId]?.hidden == true }
            .distinctBy { it.entityId }
            .map { HiddenItem(HiddenKind.CHANNEL, "live", it.entityId, displayName(overlay.channels[it.entityId]?.rename, it.name)) }
            .sortedBy { it.name.lowercase() }
        return groups + hiddenChannels
    }

    private fun displayName(rename: String?, name: String): String = rename?.takeIf { it.isNotBlank() } ?: name
}
