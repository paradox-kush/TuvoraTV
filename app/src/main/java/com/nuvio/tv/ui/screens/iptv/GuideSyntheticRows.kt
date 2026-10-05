package com.nuvio.tv.ui.screens.iptv

/**
 * B120 / UX148: the guide's synthetic Favourites and Recent rows get the REAL channel's identity.
 *
 * Those rows are built from the Library ★ entry and the device-local recents ref, which know a
 * channel's name and logo and nothing else. Without the catalog's archive flag, archive window and
 * overlay identity, a past programme that replayed from the channel list was dead from Favourites,
 * time travel stopped short, and MENU-hide had no key to hide by. The catalog row for the same
 * content id carries all three; this fills them in and keeps what the viewer saw (name, logo).
 *
 * Pure — the ViewModel supplies the lists it already holds and decides when to fetch — so it tests
 * without Android or a panel (house pattern: GuideHideUndoPolicy, LiveFavoritesRowPolicy).
 */
internal object GuideSyntheticRows {

    /**
     * [rows] with the identity of their catalog twin from [catalog] (by content id). A row with no
     * twin, or a twin without identity, is returned unchanged. The category is deliberately not
     * copied: synthetic rows are never category-filtered.
     */
    fun withCatalogIdentity(rows: List<GuideChannel>, catalog: (String) -> GuideChannel?): List<GuideChannel> =
        rows.map { row ->
            val real = catalog(row.contentId)?.takeIf { it.entityId.isNotBlank() } ?: return@map row
            row.copy(
                hasArchive = real.hasArchive,
                catchUpDays = real.catchUpDays,
                entityId = real.entityId,
                // A favourite made on another device has no local ref and so no URL.
                streamUrl = row.streamUrl.ifBlank { real.streamUrl },
                logo = row.logo ?: real.logo,
                streamId = if (row.streamId > 0) row.streamId else real.streamId,
            )
        }

    /** Rows still without identity after [withCatalogIdentity]. */
    fun unresolved(rows: List<GuideChannel>): Int = rows.count { it.entityId.isBlank() }

    /**
     * The catalog twins of [wanted] from lists already in memory (the last channel list, cached
     * categories), first identity-bearing match wins. One pass, no index of the whole catalog —
     * the wanted set is a handful of favourites/recents, the lists can be tens of thousands.
     */
    fun catalogMatches(wanted: Set<String>, sources: Sequence<List<GuideChannel>>): Map<String, GuideChannel> {
        if (wanted.isEmpty()) return emptyMap()
        val found = HashMap<String, GuideChannel>(wanted.size)
        for (list in sources) {
            for (row in list) {
                if (row.entityId.isNotBlank() && row.contentId in wanted && row.contentId !in found) {
                    found[row.contentId] = row
                }
            }
            if (found.size == wanted.size) break
        }
        return found
    }

    /**
     * Whether to fetch the whole lineup to resolve the rest: only when a row is still unknown, and
     * once per guide session — a favourite the panel no longer lists must not refetch the catalog
     * on every visit to Favourites.
     */
    fun shouldFetchLineup(unresolved: Int, alreadyTried: Boolean): Boolean = unresolved > 0 && !alreadyTried
}
