package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.contracts.ContributedHomeRow
import com.nuvio.tv.core.contracts.ContributedRows
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join between three places that must agree on one key: the row a contributor puts on Home, the entry the Layout ->
 * Catalog order list shows for it, and the preference (order / hidden) saved under it. All three go through
 * [ContributedRows.homeKey]; this pins that the catalog row Home builds really answers to it, and that it never carries a user id.
 */
class ContributedHomeRowsTest {
    private fun preview(id: String) = MetaPreview(
        id = id, type = com.nuvio.tv.domain.model.ContentType.MOVIE, rawType = "movie", name = id, poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList(),
    )

    private val row = ContributedHomeRow(
        key = "ms:jellyfin:6f3c1a9e:recently_added", sourceKey = "jellyfin:6f3c1a9e", listId = "recently_added",
        title = "Recently Added · Den", subtitle = "Den", rawType = "movie",
        items = listOf(preview("ms:jellyfin:6f3c1a9e:u1:movie:a")), hasMore = true,
    )

    @Test
    fun theCatalogRowAnswersToTheSameKeyTheSettingsListAndTheSavedPreferencesUse() {
        val catalogRow = row.toCatalogRow()
        assertEquals(ContributedRows.homeKey(row.key), catalogRowLegacyKey(catalogRow.addonId, catalogRow.apiType, catalogRow.catalogId))
        assertTrue(ContributedRows.isContributedHomeKey(ContributedRows.homeKey(row.key)))
        assertFalse("an add-on catalog key is never mistaken for a contributed one", ContributedRows.isContributedHomeKey(catalogRowLegacyKey("com.addon", "movie", "top")))
        assertFalse("collections keep their own namespace", ContributedRows.isContributedHomeKey("collection_42"))
    }

    @Test
    fun theKeyCarriesNeitherAUserIdNorAnythingButTheSourceAndRow() {
        val key = ContributedRows.homeKey(row.key)
        assertFalse("a re-login as another user must not orphan the viewer's order / hide choices", key.contains("u1"))
        assertEquals("contrib_movie_ms:jellyfin:6f3c1a9e:recently_added", key)
    }

    @Test
    fun theRowKeepsItsItemsAndTitleAndNeverOffersSeeAllYet() {
        val catalogRow = row.toCatalogRow()
        assertEquals(listOf("ms:jellyfin:6f3c1a9e:u1:movie:a"), catalogRow.items.map { it.id })
        assertEquals("Recently Added · Den", catalogRow.catalogName)
        assertEquals("Den", catalogRow.addonName)
        assertFalse("see-all on TV is an open item: the row is its own page of cards", catalogRow.hasMore)
        assertEquals(ContributedRows.ADDON_ID, catalogRow.addonId)
    }

    @Test
    fun aContributedRowTitleNeverGetsTheAddOnTypeSuffix() {
        // "Recently Added - Den - Movie" read like an add-on catalog; the contributor already named the row.
        val catalogRow = row.toCatalogRow()
        assertEquals("Recently Added · Den", catalogRowTitle(catalogRow, showCatalogTypeSuffix = true, strTypeMovie = "Movie", strTypeSeries = "Series"))
        assertEquals("an add-on row keeps its suffix", "Top - Movie", catalogRowTitle(catalogRow.copy(addonId = "com.addon", catalogName = "top"), true, "Movie", "Series"))
    }
}
