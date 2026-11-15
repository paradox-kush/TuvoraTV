package com.nuvio.tv.ui.screens.iptv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B4 (IPTV redesign 2026-11-15). JUnit: (message, expected, actual). */
class GuideGroupMemoryPolicyTest {

    private val column = listOf(
        GuideCategory("__search", "Search", GuideSpecial.SEARCH),
        GuideCategory("__fav", "Favorites", GuideSpecial.FAVORITES),
        GuideCategory("__recent", "Recent", GuideSpecial.RECENT),
        GuideCategory("__all", "All channels", GuideSpecial.ALL),
        GuideCategory("custom:1", "My sports", GuideSpecial.GROUP),
        GuideCategory("10", "News"),
        GuideCategory("11", "Sport"),
        GuideCategory("12", "Kids"),
    )

    @After
    fun tearDown() = GuideGroupMemory.clearForTest()

    @Test
    fun `a newly opened group goes first, without duplicates, capped at two`() {
        var recent = emptyList<String>()
        recent = GuideGroupMemoryPolicy.remember(recent, "10")
        recent = GuideGroupMemoryPolicy.remember(recent, "11")
        recent = GuideGroupMemoryPolicy.remember(recent, "10")
        assertEquals("newest first, no duplicate", listOf("10", "11"), recent)
        recent = GuideGroupMemoryPolicy.remember(recent, "12")
        assertEquals("capped", listOf("12", "10"), recent)
    }

    @Test
    fun `recent groups sit right under the pinned rows as copies with their own ids`() {
        val shown = GuideGroupMemoryPolicy.withRecentGroups(column, listOf("11", "custom:1"))
        assertEquals(
            "Search + pinned, then the two recent copies, then every group",
            listOf("__search", "__fav", "__recent", "__all", "__rg:11", "__rg:custom:1", "custom:1", "10", "11", "12"),
            shown.map { it.id },
        )
        assertEquals("a copy points at its group", "11", shown[4].aliasOf)
        assertEquals("a copy keeps the name", "Sport", shown[4].name)
        assertEquals("keys stay unique (a duplicate LazyColumn key is a crash)", shown.size, shown.map { it.id }.toSet().size)
        assertEquals("a copy resolves to the real group", "11", GuideGroupMemoryPolicy.resolve(shown[4]))
    }

    @Test
    fun `a remembered group no longer listed is skipped and pinned rows never repeat`() {
        val shown = GuideGroupMemoryPolicy.withRecentGroups(column, listOf("99", "__fav"))
        assertEquals("nothing to add", column.map { it.id }, shown.map { it.id })
    }

    @Test
    fun `a fresh visit opens the remembered group, else Favorites when there are some, else All channels`() {
        assertEquals("remembered", "11", GuideGroupMemoryPolicy.initialCategory(column, "11", hasFavorites = true))
        assertEquals("gone", "__fav", GuideGroupMemoryPolicy.initialCategory(column, "99", hasFavorites = true))
        assertEquals("no favourites", "__all", GuideGroupMemoryPolicy.initialCategory(column, null, hasFavorites = false))
        assertEquals("Search is never reopened", "__all", GuideGroupMemoryPolicy.initialCategory(column, "__search", hasFavorites = false))
    }

    @Test
    fun `a group reopens on its remembered row while it is still listed`() {
        assertEquals("remembered", "b", GuideGroupMemoryPolicy.rowOnOpen("b", listOf("a", "b")))
        assertEquals("gone: row 1", "a", GuideGroupMemoryPolicy.rowOnOpen("z", listOf("a", "b")))
        assertNull("empty list", GuideGroupMemoryPolicy.rowOnOpen("z", emptyList()))
    }

    @Test
    fun `the session memory keeps group, rows and recent groups per playlist`() {
        assertEquals("first entry changes the list", true, GuideGroupMemory.onGroupEntered("p1", "10", null))
        assertEquals("entering it again does not", false, GuideGroupMemory.onGroupEntered("p1", "10", null))
        GuideGroupMemory.onGroupShown("p1", "11", null)
        assertEquals("a group merely shown (focus rested on it) is not a recent group", listOf("10"), GuideGroupMemory.recentGroups("p1"))
        GuideGroupMemory.onGroupEntered("p1", "__fav", GuideSpecial.FAVORITES)
        GuideGroupMemory.onGroupShown("p1", "__search", GuideSpecial.SEARCH)
        GuideGroupMemory.onRowFocused("p1", "10", "row-7")
        assertEquals("last group (Search is not a place to come back to)", "__fav", GuideGroupMemory.lastGroup("p1"))
        assertEquals("pinned rows are not recent groups", listOf("10"), GuideGroupMemory.recentGroups("p1"))
        assertEquals("row per group", "row-7", GuideGroupMemory.row("p1", "10"))
        assertNull("other playlist", GuideGroupMemory.lastGroup("p2"))
    }
}
