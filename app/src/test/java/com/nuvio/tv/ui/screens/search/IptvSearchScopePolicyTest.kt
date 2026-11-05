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
        val results = IptvSearchScopePolicy.Body.RESULTS
        val noResults = IptvSearchScopePolicy.Body.NO_RESULTS
        assertTrue("rows on screen", IptvSearchScopePolicy.chipsVisible(results, hasIptvRows = true, iptvAnsweredForQuery = false))
        assertTrue("answered with zero hits keeps the filters on the No Results screen",
            IptvSearchScopePolicy.chipsVisible(noResults, hasIptvRows = false, iptvAnsweredForQuery = true))
        assertTrue("answered with zero hits but add-on rows", IptvSearchScopePolicy.chipsVisible(results, false, true))
        assertFalse("no IPTV at all (no sources, or not answered yet)", IptvSearchScopePolicy.chipsVisible(noResults, false, false))
    }

    @Test
    fun `chips never show over skeletons or an error`() {
        assertFalse("skeleton", IptvSearchScopePolicy.chipsVisible(IptvSearchScopePolicy.Body.SKELETON, true, true))
        assertFalse("error", IptvSearchScopePolicy.chipsVisible(IptvSearchScopePolicy.Body.ERROR, true, true))
    }

    @Test
    fun `body mirrors the screen's when order`() {
        val b = IptvSearchScopePolicy.Body
        assertEquals("loading with nothing yet", b.SKELETON, IptvSearchScopePolicy.bodyOf(true, true, false))
        assertEquals("loading wins over a stale error", b.SKELETON, IptvSearchScopePolicy.bodyOf(true, true, true))
        assertEquals("error with nothing", b.ERROR, IptvSearchScopePolicy.bodyOf(false, true, true))
        assertEquals("idle and empty", b.NO_RESULTS, IptvSearchScopePolicy.bodyOf(false, true, false))
        assertEquals("rows, even while more load", b.RESULTS, IptvSearchScopePolicy.bodyOf(true, false, false))
        assertEquals("rows, idle", b.RESULTS, IptvSearchScopePolicy.bodyOf(false, false, false))
    }

    @Test
    fun `empty note shows only when the selection hides IPTV rows that came back`() {
        val live = setOf(IptvSearchScope.LIVE)
        val noChannels = rows.filter { it != "xtream_channels" }
        assertTrue("Live only, movies+series came back", IptvSearchScopePolicy.showEmptyNote(live, noChannels, true, false) { it })
        assertFalse("still loading", IptvSearchScopePolicy.showEmptyNote(live, noChannels, true, true) { it })
        assertFalse("lane has not answered", IptvSearchScopePolicy.showEmptyNote(live, noChannels, false, false) { it })
        assertFalse("selection is all, nothing hidden", IptvSearchScopePolicy.showEmptyNote(IptvSearchScopePolicy.ALL, noChannels, true, false) { it })
        assertFalse("a surviving IPTV row", IptvSearchScopePolicy.showEmptyNote(live, rows, true, false) { it })
        assertFalse("zero IPTV hits at all: the filter is not to blame", IptvSearchScopePolicy.showEmptyNote(live, emptyList<String>(), true, false) { it })
        assertFalse("add-on rows only: the filter is not to blame", IptvSearchScopePolicy.showEmptyNote(live, listOf("addon_movies"), true, false) { it })
    }

    @Test
    fun `saved state round-trips and junk or empty reads as all`() {
        val two = setOf(IptvSearchScope.LIVE, IptvSearchScope.SERIES)
        assertEquals("round trip", two, IptvSearchScopePolicy.decode(IptvSearchScopePolicy.encode(two)))
        assertEquals("empty", IptvSearchScopePolicy.ALL, IptvSearchScopePolicy.decode(emptyList()))
        assertEquals("junk", IptvSearchScopePolicy.ALL, IptvSearchScopePolicy.decode(listOf("BOGUS")))
    }
}
