package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UX389: entry focus lands in the channel list (last-watched row, else row 1), never on the tab row, and never tunes. */
class GuideEntryFocusPolicyTest {
    private val rows = listOf("a", "b", "c", "d")

    @Test
    fun `the most recently watched channel in the list wins`() {
        assertEquals("c", GuideEntryFocusPolicy.target(recentIds = listOf("zzz", "c", "a"), visibleIds = rows))
    }

    @Test
    fun `no watched channel in this list lands on row one`() {
        assertEquals("a", GuideEntryFocusPolicy.target(recentIds = listOf("zzz"), visibleIds = rows))
        assertEquals("a", GuideEntryFocusPolicy.target(recentIds = emptyList(), visibleIds = rows))
    }

    @Test
    fun `an empty list has nothing to focus`() {
        assertNull(GuideEntryFocusPolicy.target(recentIds = listOf("a"), visibleIds = emptyList()))
    }

    @Test
    fun `entry focus is asked once and only while the viewer has not moved`() {
        assertEquals(true, GuideEntryFocusPolicy.due(pending = true, listLanded = true, focusAlreadyInGuide = false))
        assertEquals("waits for the list", false, GuideEntryFocusPolicy.due(pending = true, listLanded = false, focusAlreadyInGuide = false))
        assertEquals("never steals from a viewer already inside", false, GuideEntryFocusPolicy.due(pending = true, listLanded = true, focusAlreadyInGuide = true))
        assertEquals("one shot", false, GuideEntryFocusPolicy.due(pending = false, listLanded = true, focusAlreadyInGuide = false))
    }
}
