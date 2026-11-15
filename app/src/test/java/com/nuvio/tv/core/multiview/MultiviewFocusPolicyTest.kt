package com.nuvio.tv.core.multiview

import com.nuvio.tv.core.multiview.MultiviewDirection.DOWN
import com.nuvio.tv.core.multiview.MultiviewDirection.LEFT
import com.nuvio.tv.core.multiview.MultiviewDirection.RIGHT
import com.nuvio.tv.core.multiview.MultiviewDirection.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiviewFocusPolicyTest {
    private fun rects(layout: MultiviewLayout, n: Int) = MultiviewLayoutPolicy.bounds(layout, n)
    private fun go(layout: MultiviewLayout, n: Int, from: Int, d: MultiviewDirection) =
        MultiviewFocusPolicy.neighbour(rects(layout, n), from, d)

    @Test
    fun `grid of four moves in all four directions`() {
        assertEquals("0 right", 1, go(MultiviewLayout.GRID, 4, 0, RIGHT))
        assertEquals("0 down", 2, go(MultiviewLayout.GRID, 4, 0, DOWN))
        assertEquals("3 left", 2, go(MultiviewLayout.GRID, 4, 3, LEFT))
        assertEquals("3 up", 1, go(MultiviewLayout.GRID, 4, 3, UP))
    }

    @Test
    fun `grid of four has no neighbour at the edges and never wraps`() {
        assertNull("0 left", go(MultiviewLayout.GRID, 4, 0, LEFT))
        assertNull("0 up", go(MultiviewLayout.GRID, 4, 0, UP))
        assertNull("3 right", go(MultiviewLayout.GRID, 4, 3, RIGHT))
        assertNull("3 down", go(MultiviewLayout.GRID, 4, 3, DOWN))
    }

    @Test
    fun `side by side and stacked only move along their axis`() {
        assertEquals(1, go(MultiviewLayout.SIDE_BY_SIDE, 2, 0, RIGHT))
        assertEquals(0, go(MultiviewLayout.SIDE_BY_SIDE, 2, 1, LEFT))
        assertNull(go(MultiviewLayout.SIDE_BY_SIDE, 2, 0, UP))
        assertNull(go(MultiviewLayout.SIDE_BY_SIDE, 2, 0, DOWN))
        assertEquals(1, go(MultiviewLayout.TWO_STACKED, 2, 0, DOWN))
        assertEquals(0, go(MultiviewLayout.TWO_STACKED, 2, 1, UP))
        assertNull(go(MultiviewLayout.TWO_STACKED, 2, 0, LEFT))
    }

    @Test
    fun `single has no neighbours`() {
        MultiviewDirection.entries.forEach { assertNull("$it", go(MultiviewLayout.SINGLE, 1, 0, it)) }
    }

    @Test
    fun `focus layout with three moves between big and small tiles`() {
        assertEquals("big right goes to the upper small tile on a tie", 1, go(MultiviewLayout.FOCUS, 3, 0, RIGHT))
        assertEquals("small 1 left", 0, go(MultiviewLayout.FOCUS, 3, 1, LEFT))
        assertEquals("small 2 left", 0, go(MultiviewLayout.FOCUS, 3, 2, LEFT))
        assertEquals("small 1 down", 2, go(MultiviewLayout.FOCUS, 3, 1, DOWN))
        assertEquals("small 2 up", 1, go(MultiviewLayout.FOCUS, 3, 2, UP))
        assertNull("small 1 right", go(MultiviewLayout.FOCUS, 3, 1, RIGHT))
        assertNull("big left", go(MultiviewLayout.FOCUS, 3, 0, LEFT))
    }

    @Test
    fun `focus layout with four enters the column at the middle tile`() {
        assertEquals(2, go(MultiviewLayout.FOCUS, 4, 0, RIGHT))
        assertEquals(3, go(MultiviewLayout.FOCUS, 4, 2, DOWN))
        assertNull(go(MultiviewLayout.FOCUS, 4, 3, DOWN))
    }

    @Test
    fun `grid of three goes between the top pair and the wide bottom tile`() {
        assertEquals(2, go(MultiviewLayout.GRID, 3, 0, DOWN))
        assertEquals(2, go(MultiviewLayout.GRID, 3, 1, DOWN))
        assertEquals("up from the wide tile goes to the left on a tie", 0, go(MultiviewLayout.GRID, 3, 2, UP))
        assertNull(go(MultiviewLayout.GRID, 3, 2, LEFT))
    }

    @Test
    fun `unknown tile id has no neighbour`() {
        assertNull(go(MultiviewLayout.GRID, 4, 9, RIGHT))
    }
}
