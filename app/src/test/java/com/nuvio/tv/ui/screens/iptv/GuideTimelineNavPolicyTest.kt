package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.ui.screens.iptv.GuideTimelineNavPolicy.Direction
import com.nuvio.tv.ui.screens.iptv.GuideTimelineNavPolicy.KeyOutcome
import com.nuvio.tv.ui.screens.iptv.GuideTimelineNavPolicy.Landing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B114 / B11: the remote could not move the guide back or forward in time. Future programmes are
 * never actionable (and nor is the past of a channel with no archive), so a window that travelled
 * there had no cell to focus — the cursor was dumped on the channel row and the next RIGHT reset the
 * guide to now. Travel must keep working when the landed window has nothing to press.
 */
class GuideTimelineNavPolicyTest {

    private val slot = GuideTimeTravel.SLOT_MS
    private val a = 1_710_000_000_000L
    private val b = a + slot
    private val c = a + 2 * slot

    @Test
    fun `RIGHT on the strip of a window with nothing to press keeps travelling forward`() {
        // The window has travelled into the future: no actionable cell, the strip holds focus.
        assertEquals(
            "forward from the strip",
            KeyOutcome.TRAVEL_FORWARD,
            GuideTimelineNavPolicy.onHorizontalKey(Direction.FORWARD, focusedCellStart = null, actionableStarts = emptyList()),
        )
    }

    @Test
    fun `LEFT on the strip keeps travelling back through a channel with no archive`() {
        assertEquals(
            "back from the strip",
            KeyOutcome.TRAVEL_BACK,
            GuideTimelineNavPolicy.onHorizontalKey(Direction.BACK, focusedCellStart = null, actionableStarts = emptyList()),
        )
    }

    @Test
    fun `a window with nothing actionable lands the cursor on the strip, not off the timeline`() {
        assertEquals("no cell", Landing.STRIP, GuideTimelineNavPolicy.landing(hasActionableCell = false))
        assertEquals("a cell", Landing.CELL, GuideTimelineNavPolicy.landing(hasActionableCell = true))
    }

    @Test
    fun `the strip is focusable only on the timeline row and only while it has no cell to offer`() {
        assertTrue("empty window on the timeline row", GuideTimelineNavPolicy.stripFocusable(true, hasActionableCell = false, stripFocused = false))
        assertFalse("cells to land on", GuideTimelineNavPolicy.stripFocusable(true, hasActionableCell = true, stripFocused = false))
        assertFalse("not the timeline row", GuideTimelineNavPolicy.stripFocusable(false, hasActionableCell = false, stripFocused = false))
    }

    @Test
    fun `a strip holding focus stays focusable when cells arrive under it`() {
        // History landing late must not pull the focus target out from under the cursor.
        assertTrue("keeps focus", GuideTimelineNavPolicy.stripFocusable(true, hasActionableCell = true, stripFocused = true))
    }

    @Test
    fun `inside a window the D-pad walks between cells and pages only at the edge`() {
        val cells = listOf(a, b, c)
        assertEquals("left at the first cell pages back", KeyOutcome.TRAVEL_BACK,
            GuideTimelineNavPolicy.onHorizontalKey(Direction.BACK, a, cells))
        assertEquals("right at the last cell pages forward", KeyOutcome.TRAVEL_FORWARD,
            GuideTimelineNavPolicy.onHorizontalKey(Direction.FORWARD, c, cells))
        assertEquals("left in the middle walks", KeyOutcome.WALK,
            GuideTimelineNavPolicy.onHorizontalKey(Direction.BACK, b, cells))
        assertEquals("right at the first walks", KeyOutcome.WALK,
            GuideTimelineNavPolicy.onHorizontalKey(Direction.FORWARD, a, cells))
    }

    @Test
    fun `a single actionable cell is both edges`() {
        // The airing programme on a channel with no archive: the only stop in the live window.
        val cells = listOf(b)
        assertEquals("back", KeyOutcome.TRAVEL_BACK, GuideTimelineNavPolicy.onHorizontalKey(Direction.BACK, b, cells))
        assertEquals("forward", KeyOutcome.TRAVEL_FORWARD, GuideTimelineNavPolicy.onHorizontalKey(Direction.FORWARD, b, cells))
    }
}
