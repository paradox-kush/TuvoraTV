package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the category-selection semantics: null = all (incl. future), empty = none, list = subset. */
class XtreamCategoryFilterTest {

    private fun account(selections: CategorySelections = CategorySelections(), types: Set<String> = XtreamAccount.DEFAULT_CONTENT_TYPES) =
        XtreamAccount(
            id = "http://h|u", name = "P", baseUrl = "http://h", username = "u", password = "p",
            contentTypes = types, categorySelections = selections
        )

    @Test
    fun `null selection allows every category including unknown future ids`() {
        val acc = account()
        assertTrue(acc.allowsCategory(XtreamAccount.TYPE_LIVE, "1"))
        assertTrue(acc.allowsCategory(XtreamAccount.TYPE_MOVIES, "brand-new-category"))
        assertTrue(acc.allowsCategory(XtreamAccount.TYPE_SERIES, null))
    }

    @Test
    fun `empty selection allows nothing`() {
        val acc = account(CategorySelections(live = emptyList()))
        assertFalse(acc.allowsCategory(XtreamAccount.TYPE_LIVE, "1"))
        assertFalse(acc.allowsCategory(XtreamAccount.TYPE_LIVE, null))
        // other types keep their null (= all) selection
        assertTrue(acc.allowsCategory(XtreamAccount.TYPE_MOVIES, "1"))
    }

    @Test
    fun `non-empty selection allows only its ids`() {
        val acc = account(CategorySelections(movies = listOf("10", "20")))
        assertTrue(acc.allowsCategory(XtreamAccount.TYPE_MOVIES, "10"))
        assertFalse(acc.allowsCategory(XtreamAccount.TYPE_MOVIES, "30"))
        // an item without a categoryId is excluded once a selection exists
        assertFalse(acc.allowsCategory(XtreamAccount.TYPE_MOVIES, null))
    }

    @Test
    fun `category list filtering keeps the selected subset`() {
        val categories = listOf(
            XtreamCategory("1", "News"),
            XtreamCategory("2", "Sports"),
            XtreamCategory("3", "Kids")
        )
        val acc = account(CategorySelections(live = listOf("2")))
        assertEquals(
            listOf("Sports"),
            categories.filter { acc.allowsCategory(XtreamAccount.TYPE_LIVE, it.id) }.map { it.name }
        )
    }

    @Test
    fun `withType updates only its own type`() {
        val base = CategorySelections(live = listOf("1"))
        assertEquals(CategorySelections(live = listOf("1"), movies = listOf("9")), base.withType("movies", listOf("9")))
        assertEquals(CategorySelections(), base.withType("live", null))               // Select All -> null
        assertEquals(CategorySelections(live = emptyList()), base.withType("live", emptyList()))  // Deselect All
        assertEquals(base, base.withType("bogus", listOf("9")))
        assertTrue(CategorySelections().allNull)
        assertFalse(CategorySelections(series = emptyList()).allNull)
    }

    @Test
    fun `content type toggles gate each section`() {
        val acc = account(types = setOf(XtreamAccount.TYPE_MOVIES))
        assertFalse(acc.typeEnabled(XtreamAccount.TYPE_LIVE))
        assertTrue(acc.typeEnabled(XtreamAccount.TYPE_MOVIES))
        assertFalse(acc.typeEnabled(XtreamAccount.TYPE_SERIES))
    }
}
