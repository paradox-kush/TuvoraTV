package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewLayoutPolicyTest {
    private val eps = 0.0005f

    private fun overlapArea(a: TileRect, b: TileRect): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }

    @Test
    fun `every layout at every count gives count tiles inside the area that do not overlap and fill it`() {
        for (layout in MultiviewLayout.entries) for (count in 1..4) {
            val rects = MultiviewLayoutPolicy.bounds(layout, count)
            val tag = "$layout x $count"
            assertEquals("$tag tile count", count, rects.size)
            assertEquals("$tag ids in reading order", (0 until count).toList(), rects.map { it.id })
            rects.forEach {
                assertTrue("$tag inside 0..1: $it", it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f)
                assertTrue("$tag positive size: $it", it.width > eps && it.height > eps)
            }
            for (i in rects.indices) for (j in i + 1 until rects.size) {
                assertTrue("$tag tiles $i/$j overlap", overlapArea(rects[i], rects[j]) < eps)
            }
            assertEquals("$tag fills the area", 1f, rects.sumOf { (it.width * it.height).toDouble() }.toFloat(), eps)
        }
    }

    @Test
    fun `focus gives one big tile and a column of equal small ones`() {
        val three = MultiviewLayoutPolicy.bounds(MultiviewLayout.FOCUS, 3)
        assertEquals("big tile spans full height", 1f, three[0].height, eps)
        assertTrue("big tile is wider than a small one", three[0].width > three[1].width)
        assertEquals("small tile 1 height", .5f, three[1].height, eps)
        val four = MultiviewLayoutPolicy.bounds(MultiviewLayout.FOCUS, 4)
        assertEquals("small tile heights with 4", 1f / 3f, four[2].height, eps)
        assertEquals("small tiles share the column", four[1].left, four[3].left, eps)
    }

    @Test
    fun `grid with four is 2x2 and with three has a wide bottom tile`() {
        val four = MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, 4)
        four.forEach { assertEquals("2x2 cell width", .5f, it.width, eps) }
        val three = MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, 3)
        assertEquals("third tile spans the width", 1f, three[2].width, eps)
    }

    @Test
    fun `defaultFor picks single, side by side, focus, grid`() {
        assertEquals("1", MultiviewLayout.SINGLE, MultiviewLayoutPolicy.defaultFor(1))
        assertEquals("2", MultiviewLayout.SIDE_BY_SIDE, MultiviewLayoutPolicy.defaultFor(2))
        assertEquals("3", MultiviewLayout.FOCUS, MultiviewLayoutPolicy.defaultFor(3))
        assertEquals("4", MultiviewLayout.GRID, MultiviewLayoutPolicy.defaultFor(4))
        assertEquals("0 falls back to single", MultiviewLayout.SINGLE, MultiviewLayoutPolicy.defaultFor(0))
    }

    @Test
    fun `effective keeps a layout that fits and falls back when it does not`() {
        assertEquals(MultiviewLayout.GRID, MultiviewLayoutPolicy.effective(MultiviewLayout.GRID, 4))
        assertEquals(MultiviewLayout.GRID, MultiviewLayoutPolicy.effective(MultiviewLayout.GRID, 3))
        assertEquals(MultiviewLayout.SIDE_BY_SIDE, MultiviewLayoutPolicy.effective(MultiviewLayout.GRID, 2))
        assertEquals(MultiviewLayout.SINGLE, MultiviewLayoutPolicy.effective(MultiviewLayout.FOCUS, 1))
        assertEquals(MultiviewLayout.SIDE_BY_SIDE, MultiviewLayoutPolicy.effective(MultiviewLayout.FOCUS, 2))
        assertEquals(MultiviewLayout.TWO_STACKED, MultiviewLayoutPolicy.effective(MultiviewLayout.TWO_STACKED, 2))
        assertEquals(MultiviewLayout.FOCUS, MultiviewLayoutPolicy.effective(MultiviewLayout.SIDE_BY_SIDE, 3))
        assertEquals(MultiviewLayout.GRID, MultiviewLayoutPolicy.effective(MultiviewLayout.SINGLE, 4))
    }

    @Test
    fun `bounds caps at four tiles and is empty for none`() {
        assertEquals("cap", 4, MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, 9).size)
        assertTrue("zero", MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, 0).isEmpty())
        assertTrue("negative", MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, -2).isEmpty())
    }
}
