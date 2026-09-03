package com.nuvio.tv.core.iptv.overlay

/**
 * Pure decisions that turn "raw provider list + overlay" into "what the user sees" — hidden dropped,
 * pinned/reordered, renamed, custom groups injected. No DB, no network, no identity call (the caller
 * tags each row with its entity id), so it unit-tests in isolation (house pattern:
 * [com.nuvio.app.features.iptv.RadarLiveRefreshPolicy], LivePlaybackFreezePolicy).
 */
object IptvChannelOverlayPolicy {

    /** A provider row tagged with its durable identity and its index in the provider's own order. */
    data class Tagged<T>(val entityId: String, val providerIndex: Int, val row: T)

    /**
     * Hidden rows dropped; ordered by (pinned first, then manual position ?? provider order, then
     * provider order as tiebreak); rename applied via [withName].
     *
     * [honorOrder] = false keeps pure provider order (used on paged surfaces where a pin/position
     * could live on an unfetched page) but STILL hides and renames.
     */
    fun <T> displayed(
        rows: List<Tagged<T>>,
        overlay: Map<String, ChannelOverlay>,
        honorOrder: Boolean = true,
        withName: (T, newName: String) -> T = { r, _ -> r },
    ): List<T> {
        val kept = rows.filter { overlay[it.entityId]?.hidden != true }
        val ordered = if (!honorOrder) {
            kept
        } else {
            kept.sortedWith(
                compareBy(
                    { if (overlay[it.entityId]?.pinned == true) 0 else 1 },
                    { overlay[it.entityId]?.position ?: it.providerIndex },
                    // an explicitly-positioned row wins a tie against a provider-order row at the same slot
                    { if (overlay[it.entityId]?.position != null) 0 else 1 },
                    { it.providerIndex },
                ),
            )
        }
        return ordered.map { t ->
            val rename = overlay[t.entityId]?.rename?.takeIf { it.isNotBlank() }
            if (rename != null) withName(t.row, rename) else t.row
        }
    }
}

object IptvCategoryOverlayPolicy {

    data class TaggedCategory(val key: String, val providerIndex: Int, val id: String, val name: String)

    /** A row for the browse UI: a provider category, or a user-defined custom group (with its members). */
    data class DisplayCategory(
        val id: String,               // provider category id, or the custom group id
        val name: String,
        val custom: Boolean = false,
        val memberEntityIds: List<String> = emptyList(),
    )

    /**
     * Provider categories with hidden removed, renamed, and ordered (pinned first, then manual
     * position ?? provider order); custom groups injected ABOVE the provider categories, ordered by
     * their own position. An empty custom group is suppressed. Pure.
     */
    fun displayed(
        provider: List<TaggedCategory>,
        overlay: Map<String, CategoryOverlay>,
        customGroups: List<CustomGroup>,
    ): List<DisplayCategory> {
        val groupRows = customGroups
            .filter { it.memberEntityIds.isNotEmpty() }
            .sortedBy { it.position }
            .map { DisplayCategory(id = it.id, name = it.name, custom = true, memberEntityIds = it.memberEntityIds) }

        val categoryRows = provider
            .filter { overlay[it.key]?.hidden != true }
            .sortedWith(
                compareBy(
                    { if (overlay[it.key]?.pinned == true) 0 else 1 },
                    { overlay[it.key]?.position ?: it.providerIndex },
                    { if (overlay[it.key]?.position != null) 0 else 1 },
                    { it.providerIndex },
                ),
            )
            .map {
                val rename = overlay[it.key]?.rename?.takeIf { r -> r.isNotBlank() }
                DisplayCategory(id = it.id, name = rename ?: it.name)
            }

        return groupRows + categoryRows
    }
}
