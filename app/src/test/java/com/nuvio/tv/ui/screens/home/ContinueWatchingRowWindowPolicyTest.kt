package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX12: a title started from Home must be visible at the front of Continue Watching when the viewer
 * comes back from the player. The row is recency-ordered, so a new title lands at index 0; restoring
 * the row "by the card it started on" (or a LazyRow keeping its first card when items land in front)
 * pushed it just off-screen to the left until the app was relaunched.
 */
class ContinueWatchingRowWindowPolicyTest {

    private val cw = MODERN_CONTINUE_WATCHING_ROW_KEY
    private val before = listOf("a", "b", "c", "d")
    private val withNewTitle = listOf("new") + before

    @Test
    fun `continue watching comes back at the start when a new title took the front`() {
        // Left with the row at its start ("a" first visible, index 0); "new" was started meanwhile.
        assertEquals(
            "new title must be on screen",
            0,
            ContinueWatchingRowWindowPolicy.restoredFirstIndex(cw, withNewTitle, anchorKey = "a", savedIndex = 0)
        )
    }

    @Test
    fun `continue watching keeps a scrolled window when nothing changed in front of it`() {
        assertEquals(
            "unchanged row keeps its window",
            2,
            ContinueWatchingRowWindowPolicy.restoredFirstIndex(cw, before, anchorKey = "c", savedIndex = 2)
        )
    }

    @Test
    fun `continue watching scrolled away also returns to the start when items landed in front`() {
        assertEquals(
            "shifted anchor means the front changed",
            0,
            ContinueWatchingRowWindowPolicy.restoredFirstIndex(cw, withNewTitle, anchorKey = "c", savedIndex = 2)
        )
    }

    @Test
    fun `continue watching with a vanished anchor starts at the front`() {
        assertEquals(
            "anchor gone",
            0,
            ContinueWatchingRowWindowPolicy.restoredFirstIndex(cw, listOf("x", "y"), anchorKey = "c", savedIndex = 2)
        )
    }

    @Test
    fun `catalog rows still restore by the card they started on`() {
        // 0becbc42d behaviour is kept for every other row.
        assertEquals(
            "catalog anchor",
            3,
            ContinueWatchingRowWindowPolicy.restoredFirstIndex("catalog_x", withNewTitle, anchorKey = "c", savedIndex = 2)
        )
        assertEquals(
            "catalog without anchor",
            2,
            ContinueWatchingRowWindowPolicy.restoredFirstIndex("catalog_x", withNewTitle, anchorKey = null, savedIndex = 2)
        )
    }

    @Test
    fun `a new leader arriving while the row shows the old leader snaps back to the start`() {
        // The LazyRow kept "a" in view by key, so "new" sits at index 0 off-screen.
        assertTrue(
            ContinueWatchingRowWindowPolicy.shouldSnapToStart(
                cw, previousLeaderKey = "a", itemKeys = withNewTitle, firstVisibleIndex = 1, rowHasFocus = false
            )
        )
        // Same decision when read before the row re-measured (still index 0 -> nothing to scroll).
        assertFalse(
            "already at the start",
            ContinueWatchingRowWindowPolicy.shouldSnapToStart(
                cw, previousLeaderKey = "a", itemKeys = withNewTitle, firstVisibleIndex = 0, rowHasFocus = false
            )
        )
    }

    @Test
    fun `no snap when the viewer is browsing the row`() {
        assertFalse(
            "focused row is never yanked",
            ContinueWatchingRowWindowPolicy.shouldSnapToStart(
                cw, previousLeaderKey = "a", itemKeys = withNewTitle, firstVisibleIndex = 1, rowHasFocus = true
            )
        )
    }

    @Test
    fun `no snap when the viewer had scrolled past the old leader`() {
        assertFalse(
            "scrolled window is kept",
            ContinueWatchingRowWindowPolicy.shouldSnapToStart(
                cw, previousLeaderKey = "a", itemKeys = withNewTitle, firstVisibleIndex = 3, rowHasFocus = false
            )
        )
    }

    @Test
    fun `no snap without a change of leader or for other rows`() {
        assertFalse(
            "same leader",
            ContinueWatchingRowWindowPolicy.shouldSnapToStart(cw, "a", before, firstVisibleIndex = 1, rowHasFocus = false)
        )
        assertFalse(
            "first composition",
            ContinueWatchingRowWindowPolicy.shouldSnapToStart(cw, null, withNewTitle, firstVisibleIndex = 1, rowHasFocus = false)
        )
        assertFalse(
            "old leader removed",
            ContinueWatchingRowWindowPolicy.shouldSnapToStart(cw, "zzz", withNewTitle, firstVisibleIndex = 1, rowHasFocus = false)
        )
        assertFalse(
            "catalog row",
            ContinueWatchingRowWindowPolicy.shouldSnapToStart("catalog_x", "a", withNewTitle, firstVisibleIndex = 1, rowHasFocus = false)
        )
    }
}
