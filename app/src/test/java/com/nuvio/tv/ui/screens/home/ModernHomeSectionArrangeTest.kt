package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.home.HomeSectionOrderPolicy
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Test

class ModernHomeSectionArrangeTest {
    private fun row(key: String) = HeroCarouselRow(
        key = key,
        title = key,
        globalRowIndex = -1,
        items = emptyList<ModernCarouselItem>().asStable()
    )

    private val built = listOf(
        row(MODERN_CONTINUE_WATCHING_ROW_KEY),
        row(MODERN_UPCOMING_ROW_KEY),
        row(MODERN_LIVE_RECENTS_ROW_KEY),
        row("catalog_a"),
        row("collection_b"),
        row("catalog_c"),
    )

    private fun keys(order: List<String>) = arrangeModernRowsBySection(built, order).map { it.key }

    @Test
    fun defaultOrderPutsRecentChannelsUnderContinueWatching() {
        assertEquals(
            listOf("continue_watching", "live_recents", "upcoming_section", "catalog_a", "collection_b", "catalog_c"),
            keys(HomeSectionOrderPolicy.renderOrder(null))
        )
    }

    @Test
    fun customOrderMovesWholeSectionsAndKeepsCatalogRowOrder() {
        val order = listOf("catalogs", "live_recents", "continue_watching", "upcoming")
        assertEquals(
            listOf("catalog_a", "collection_b", "catalog_c", "live_recents", "continue_watching", "upcoming_section"),
            keys(order)
        )
    }

    @Test
    fun aSectionNotInTheOrderIsLeftOut() {
        assertEquals(
            listOf("continue_watching", "catalog_a", "collection_b", "catalog_c"),
            keys(listOf("continue_watching", "catalogs"))
        )
        assertEquals(emptyList<String>(), keys(emptyList()))
    }

    @Test
    fun aRepeatedIdDoesNotDuplicateRows() {
        assertEquals(
            listOf("continue_watching"),
            keys(listOf("continue_watching", "continue_watching"))
        )
    }
}
