package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B19: the in-app category settings decide what a playlist may offer as a source or search hit. */
class IptvSourceCategoryPolicyTest {

    private data class Item(val name: String, val categoryId: String?)

    private fun account(
        selections: CategorySelections = CategorySelections(),
        types: Set<String> = XtreamAccount.DEFAULT_CONTENT_TYPES,
    ) = XtreamAccount(
        id = "http://h|u", name = "P", baseUrl = "http://h", username = "u", password = "p",
        contentTypes = types, categorySelections = selections,
    )

    private val movies = XtreamAccount.TYPE_MOVIES

    @Test
    fun `a title in a hidden category is not offered`() {
        val acc = account(CategorySelections(movies = listOf("10")))
        val kept = IptvSourceCategoryPolicy.keep(
            acc, movies, listOf(Item("Allowed", "10"), Item("Hidden", "30")),
        ) { it.categoryId }
        assertEquals("only the allowed category survives", listOf("Allowed"), kept.map { it.name })
    }

    @Test
    fun `a disabled content type offers nothing`() {
        val acc = account(types = setOf(XtreamAccount.TYPE_LIVE, XtreamAccount.TYPE_SERIES))
        assertFalse("movies switched off", IptvSourceCategoryPolicy.offers(acc, movies))
        val kept = IptvSourceCategoryPolicy.keep(acc, movies, listOf(Item("Any", "10"))) { it.categoryId }
        assertTrue("no movie is offered", kept.isEmpty())
    }

    @Test
    fun `an explicit empty selection offers nothing`() {
        val acc = account(CategorySelections(movies = emptyList()))
        assertFalse("'Deselect All' means none", IptvSourceCategoryPolicy.offers(acc, movies))
        assertTrue(
            "no movie is offered",
            IptvSourceCategoryPolicy.keep(acc, movies, listOf(Item("Any", "10"))) { it.categoryId }.isEmpty(),
        )
    }

    @Test
    fun `no selection offers everything including uncategorised items`() {
        val acc = account()
        val items = listOf(Item("A", "10"), Item("B", null), Item("C", "brand-new"))
        assertEquals("all kept, order preserved", items, IptvSourceCategoryPolicy.keep(acc, movies, items) { it.categoryId })
    }

    @Test
    fun `an uncategorised item is dropped under a partial selection`() {
        val acc = account(CategorySelections(movies = listOf("10")))
        val kept = IptvSourceCategoryPolicy.keep(acc, movies, listOf(Item("NoCat", null), Item("A", "10"))) { it.categoryId }
        assertEquals("same rule as browse and live search", listOf("A"), kept.map { it.name })
    }

    @Test
    fun `the cap applies after filtering`() {
        val acc = account(CategorySelections(movies = listOf("10")))
        // Hidden items first: capping before filtering would leave nothing.
        val items = List(5) { Item("hidden$it", "30") } + List(4) { Item("ok$it", "10") }
        val kept = IptvSourceCategoryPolicy.keepCapped(acc, movies, items, cap = 3) { it.categoryId }
        assertEquals("three allowed items fill the cap", listOf("ok0", "ok1", "ok2"), kept.map { it.name })
    }

    @Test
    fun `selections are per type`() {
        val acc = account(CategorySelections(series = listOf("5")))
        val kept = IptvSourceCategoryPolicy.keep(acc, movies, listOf(Item("A", "30"))) { it.categoryId }
        assertEquals("a series selection does not filter movies", listOf("A"), kept.map { it.name })
    }

    @Test
    fun `scan limit widens only under a partial selection`() {
        assertEquals("no selection reads the cap", 60, IptvSourceCategoryPolicy.scanLimit(account(), movies, 60))
        assertEquals(
            "partial selection reads the wider window",
            IptvSourceCategoryPolicy.FILTERED_SCAN_LIMIT,
            IptvSourceCategoryPolicy.scanLimit(account(CategorySelections(movies = listOf("1"))), movies, 60),
        )
    }

    @Test
    fun `kinds map to playlist content types`() {
        assertEquals("movie", XtreamAccount.TYPE_MOVIES, IptvSourceCategoryPolicy.typeOf(MatchKind.MOVIE))
        assertEquals("series", XtreamAccount.TYPE_SERIES, IptvSourceCategoryPolicy.typeOf(MatchKind.SERIES))
        assertEquals("live", XtreamAccount.TYPE_LIVE, IptvSourceCategoryPolicy.typeOf(MatchKind.LIVE))
    }
}
