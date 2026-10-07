package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.contracts.ContributedHomeRow
import com.nuvio.tv.core.contracts.ContributedRows
import com.nuvio.tv.core.contracts.HomeSectionContributorRegistry
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Rows OTHER sources contribute to Home (a media server's own Continue Watching / Next Up / Recently added / a
 * library) - media-servers design 5.4, owner decisions 2026-10-06: they are off until the viewer turns them on in
 * the server's settings, they join the same order / hide preferences as add-on rows (keyed by the stable
 * [ContributedRows.homeKey]), and they never feed the hero.
 *
 * They are plain [CatalogRow]s in the same state the add-on rows live in, so ordering, hiding, focus and the
 * modern/classic/grid layouts need nothing special. The contributor gates its own fetches (TTL, backoff,
 * lifecycle): this file never polls, it asks when Home loads, when Home comes back (refreshHomeCatalogsIfStale)
 * and when a contributor says its configuration changed.
 */
internal fun HomeViewModel.contributedRowKeys(): List<String> =
    HomeSectionContributorRegistry.declaredRows().map { ContributedRows.homeKey(it.key) }

/** A contributor's configuration changed (a server added / edited / signed in): ask again. */
internal fun HomeViewModel.observeContributedRowsPipeline() {
    viewModelScope.launch {
        HomeSectionContributorRegistry.changes().collectLatest {
            refreshContributedRowsPipeline(force = true)
        }
    }
}

internal suspend fun HomeViewModel.refreshContributedRowsPipeline(force: Boolean) {
    if (HomeSectionContributorRegistry.isEmpty) return
    val rows = try {
        HomeSectionContributorRegistry.collectSections(force)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return
    }
    val catalogRows = rows.map { it.toCatalogRow() }
    val newKeys = catalogRows.map { it.legacyKeyForHome() }.toSet()
    var changed = false
    synchronized(catalogStateLock) {
        val stale = catalogsMap.keys.filter { ContributedRows.isContributedHomeKey(it) && it !in newKeys }
        stale.forEach { key -> removeCatalogRowLocked(key); truncatedRowCache.remove(key); changed = true }
        catalogRows.forEach { row ->
            val key = row.legacyKeyForHome()
            if (catalogsMap[key] != row) {
                replaceCatalogRow(key, row)
                truncatedRowCache.remove(key)
                changed = true
            }
        }
        // a newly declared row needs its place in the order (a removed one just stops matching)
        val order = contributedRowKeys()
        order.filter { it !in catalogOrder }.forEach { catalogOrder.add(it); changed = true }
        if (catalogOrder.removeAll { ContributedRows.isContributedHomeKey(it) && it !in order }) changed = true
    }
    if (changed) scheduleUpdateCatalogRows()
}

private fun CatalogRow.legacyKeyForHome(): String = com.nuvio.tv.domain.model.catalogRowLegacyKey(addonId, apiType, catalogId)

/** A contributed row as the catalog row Home already knows how to render. */
internal fun ContributedHomeRow.toCatalogRow(): CatalogRow = CatalogRow(
    addonId = ContributedRows.ADDON_ID,
    addonName = subtitle,
    addonBaseUrl = "",
    catalogId = key,
    catalogName = title,
    type = ContentType.fromString(ContributedRows.TYPE),
    rawType = ContributedRows.TYPE,
    items = items,
    isLoading = false,
    // "See all" for a contributed row is not offered on TV yet (open item): the row is its own page of cards.
    hasMore = false,
)
