package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideCategoryEntryPolicyTest {
    private val search = XtreamLiveGuideViewModel.SEARCH_ID

    @Test
    fun `entry is the selected category`() {
        assertEquals("selected", 2, GuideCategoryEntryPolicy.entryIndex(listOf(search, "__fav", "7", "8"), "7"))
    }

    @Test
    fun `entry skips Search when nothing is selected`() {
        assertEquals("first non-search", 1, GuideCategoryEntryPolicy.entryIndex(listOf(search, "__fav", "7"), null))
    }

    @Test
    fun `entry skips Search when the selection is unknown`() {
        assertEquals("first non-search", 1, GuideCategoryEntryPolicy.entryIndex(listOf(search, "__fav"), "gone"))
    }

    @Test
    fun `entry is Search itself when it was explicitly selected`() {
        assertEquals("selected search", 0, GuideCategoryEntryPolicy.entryIndex(listOf(search, "__fav"), search))
    }

    @Test
    fun `entry is zero when only Search or nothing exists`() {
        assertEquals("only search", 0, GuideCategoryEntryPolicy.entryIndex(listOf(search), null))
        assertEquals("empty", 0, GuideCategoryEntryPolicy.entryIndex(emptyList(), null))
    }
}

class GuideEmptyMessagePolicyTest {
    private val search = XtreamLiveGuideViewModel.SEARCH_ID

    private fun kind(away: Boolean, id: String?, empty: Boolean = true) =
        GuideEmptyMessagePolicy.kind(awayFromNow = away, selectedCategoryId = id, channelsEmpty = empty)

    @Test
    fun `empty favorites teaches the gesture`() {
        assertEquals("fav", GuideEmptyMessagePolicy.Kind.FAVORITES_EMPTY, kind(false, "__fav"))
        assertEquals("allfav", GuideEmptyMessagePolicy.Kind.FAVORITES_EMPTY, kind(true, "__allfav"))
    }

    @Test
    fun `away from now says no data for this time`() {
        assertEquals("away", GuideEmptyMessagePolicy.Kind.EMPTY_WINDOW, kind(true, "7"))
    }

    @Test
    fun `search shows a search hint never the EPG text`() {
        assertEquals("search", GuideEmptyMessagePolicy.Kind.SEARCH_HINT, kind(false, search))
    }

    @Test
    fun `default is data unavailable`() {
        assertEquals("default", GuideEmptyMessagePolicy.Kind.DATA_UNAVAILABLE, kind(false, "7"))
        assertEquals("favorites with channels", GuideEmptyMessagePolicy.Kind.DATA_UNAVAILABLE, kind(false, "__fav", empty = false))
    }
}
