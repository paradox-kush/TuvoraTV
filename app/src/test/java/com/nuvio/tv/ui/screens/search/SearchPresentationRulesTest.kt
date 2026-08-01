package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.DiscoverLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresentationRulesTest {

    @Test
    fun `one character remains in discover without becoming a submitted search`() {
        assertEquals("", submittedSearchQuery("a"))
        assertTrue(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "a",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `minimum query leaves discover while live search is pending`() {
        assertEquals("ab", submittedSearchQuery(" ab "))
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.IN_SEARCH,
                query = "ab",
                submittedQuery = ""
            )
        )
    }

    @Test
    fun `discover location off never enters discover mode`() {
        assertFalse(
            shouldShowDiscoverInSearch(
                discoverLocation = DiscoverLocation.OFF,
                query = "",
                submittedQuery = ""
            )
        )
    }
}
