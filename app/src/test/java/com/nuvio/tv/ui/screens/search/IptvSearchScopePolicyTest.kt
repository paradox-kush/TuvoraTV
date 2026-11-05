package com.nuvio.tv.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F85: Live / Movies / Series scope chips over the IPTV rows of a search (ported from Mobile). */
class IptvSearchScopePolicyTest {
    private val rows = listOf("addon_movies", "xtream_channels", "xtream_movies", "xtream_series", "addon_series")
    private fun filter(sel: Set<IptvSearchScope>) = IptvSearchScopePolicy.filter(rows, sel) { it }

    @Test
    fun `default selection is all and keeps every row`() {
        assertEquals("all selected is a no-op", rows, filter(IptvSearchScopePolicy.ALL))
    }

    @Test
    fun `one scope keeps only that IPTV row and leaves add-on rows alone`() {
        assertEquals(
            "add-on rows are never filtered",
            listOf("addon_movies", "xtream_movies", "addon_series"),
            filter(setOf(IptvSearchScope.MOVIES)),
        )
    }

    @Test
    fun `multi-select keeps the union in original order`() {
        assertEquals(
            "union, original order",
            listOf("addon_movies", "xtream_channels", "xtream_series", "addon_series"),
            filter(setOf(IptvSearchScope.SERIES, IptvSearchScope.LIVE)),
        )
    }

    @Test
    fun `toggling off one of all narrows and toggling the last off goes back to all`() {
        val two = IptvSearchScopePolicy.toggle(IptvSearchScopePolicy.ALL, IptvSearchScope.LIVE)
        assertEquals("un-pick Live", setOf(IptvSearchScope.MOVIES, IptvSearchScope.SERIES), two)
        val one = IptvSearchScopePolicy.toggle(two, IptvSearchScope.MOVIES)
        assertEquals("un-pick Movies", setOf(IptvSearchScope.SERIES), one)
        assertEquals("un-picking the last returns to all", IptvSearchScopePolicy.ALL,
            IptvSearchScopePolicy.toggle(one, IptvSearchScope.SERIES))
        assertEquals("picking another adds it", setOf(IptvSearchScope.SERIES, IptvSearchScope.LIVE),
            IptvSearchScopePolicy.toggle(one, IptvSearchScope.LIVE))
    }

    @Test
    fun `chips only matter when IPTV rows exist`() {
        assertTrue("IPTV rows present", IptvSearchScopePolicy.hasIptvRows(rows) { it })
        assertFalse("add-on only", IptvSearchScopePolicy.hasIptvRows(listOf("addon_movies")) { it })
    }

    @Test
    fun `scope keys match the rows the Xtream search emits`() {
        assertEquals("catalog ids from XtreamIptvSearchProvider.rowsOf",
            listOf("xtream_channels", "xtream_movies", "xtream_series"), IptvSearchScope.entries.map { it.catalogId })
        assertNull("unknown catalog", IptvSearchScopePolicy.scopeOf("addon_movies"))
    }

    @Test
    fun `chips stay up when the IPTV lane answered even with zero rows`() {
        assertTrue("rows on screen", IptvSearchScopePolicy.showChips(hasIptvRows = true, iptvAnsweredForQuery = false))
        assertTrue("answered with zero hits keeps the filters", IptvSearchScopePolicy.showChips(hasIptvRows = false, iptvAnsweredForQuery = true))
        assertFalse("no IPTV at all (no sources, or not answered yet)", IptvSearchScopePolicy.showChips(hasIptvRows = false, iptvAnsweredForQuery = false))
    }

    @Test
    fun `empty note shows only when the selection hides every IPTV row after loading`() {
        val live = setOf(IptvSearchScope.LIVE)
        val scoped = IptvSearchScopePolicy.filter(rows.filter { it != "xtream_channels" }, live) { it }
        assertTrue("Live only, no channel hits, add-on rows remain", IptvSearchScopePolicy.showEmptyNote(live, scoped, true, false) { it })
        assertFalse("still loading", IptvSearchScopePolicy.showEmptyNote(live, scoped, true, true) { it })
        assertFalse("lane has not answered", IptvSearchScopePolicy.showEmptyNote(live, scoped, false, false) { it })
        assertFalse("selection is all, nothing hidden", IptvSearchScopePolicy.showEmptyNote(IptvSearchScopePolicy.ALL, scoped, true, false) { it })
        assertFalse("a surviving IPTV row", IptvSearchScopePolicy.showEmptyNote(live, rows, true, false) { it })
        assertTrue("zero rows at all", IptvSearchScopePolicy.showEmptyNote(live, emptyList<String>(), true, false) { it })
    }
}
