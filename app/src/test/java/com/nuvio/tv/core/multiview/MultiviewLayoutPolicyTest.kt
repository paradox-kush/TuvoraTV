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

    private val w = MultiviewLayoutPolicy.SAFE_WIDTH_DP
    private val h = MultiviewLayoutPolicy.SAFE_HEIGHT_DP

    /** A rect in dp of the 864 x 486 safe area. */
    private fun TileRect.dpWidth() = width * w
    private fun TileRect.dpHeight() = height * h

    @Test
    fun `every layout at every count gives count 16 by 9 tiles inside the area that do not overlap`() {
        for (layout in MultiviewLayout.entries) for (count in 1..4) {
            val rects = MultiviewLayoutPolicy.bounds(layout, count)
            val tag = "$layout x $count"
            assertEquals("$tag tile count", count, rects.size)
            assertEquals("$tag ids in reading order", (0 until count).toList(), rects.map { it.id })
            rects.forEach {
                assertTrue("$tag inside 0..1: $it", it.left >= -eps && it.top >= -eps && it.right <= 1f + eps && it.bottom <= 1f + eps)
                assertTrue("$tag positive size: $it", it.width > eps && it.height > eps)
                if (count > 1) {
                    assertEquals("$tag is 16:9: $it", 16f / 9f, it.dpWidth() / it.dpHeight(), 0.01f)
                }
            }
            for (i in rects.indices) for (j in i + 1 until rects.size) {
                assertTrue("$tag tiles $i/$j overlap", overlapArea(rects[i], rects[j]) < eps)
            }
        }
    }

    @Test
    fun `two tiles of the same size are 428 by 241 dp with an 8 dp gap and centred`() {
        val r = MultiviewLayoutPolicy.bounds(MultiviewLayout.SIDE_BY_SIDE, 2)
        assertEquals("width", 428f, r[0].dpWidth(), 0.5f)
        assertEquals("height", 240.75f, r[0].dpHeight(), 0.5f)
        assertEquals("gap", 8f, (r[1].left - r[0].right) * w, 0.01f)
        assertEquals("tops aligned", r[0].top, r[1].top, eps)
        assertEquals("centred vertically", 1f - r[0].bottom, r[0].top, eps)
        assertEquals("fills the width", 1f, r[1].right - r[0].left, eps)
    }

    @Test
    fun `three and four of the same size are 425 by 239 dp`() {
        val four = MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, 4)
        four.forEach {
            assertEquals(425f, it.dpWidth(), 1f)
            assertEquals(239f, it.dpHeight(), 1f)
        }
        assertEquals("2 x 2 gap", 8f, (four[3].left - four[2].right) * w, 0.01f)
        val three = MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, 3)
        assertEquals("the third is centred below", 0.5f, three[2].centerX, eps)
        assertEquals("same size as the others", three[0].width, three[2].width, eps)
    }

    @Test
    fun `one big gives a big tile and a column of small ones with the same total height`() {
        val three = MultiviewLayoutPolicy.bounds(MultiviewLayout.FOCUS, 3)
        assertEquals("big 576 x 324", 576f, three[0].dpWidth(), 1.5f)
        assertEquals(324f, three[0].dpHeight(), 1.5f)
        assertEquals("small 280 x 158", 280f, three[1].dpWidth(), 1.5f)
        assertEquals(158f, three[1].dpHeight(), 1.5f)
        assertEquals("the column is as tall as the big tile", three[0].height, three[2].bottom - three[1].top, eps)
        val four = MultiviewLayoutPolicy.bounds(MultiviewLayout.FOCUS, 4)
        assertEquals("big 649 wide with four", 649f, four[0].dpWidth(), 1.5f)
        assertEquals("small 207 wide", 207f, four[1].dpWidth(), 1.5f)
        assertEquals("small column shares one left edge", four[1].left, four[3].left, eps)
        assertEquals("the column is as tall as the big tile", four[0].height, four[3].bottom - four[1].top, eps)
    }

    @Test
    fun `one big works for two tiles with a small one half the big one's width`() {
        val two = MultiviewLayoutPolicy.bounds(MultiviewLayout.FOCUS, 2)
        assertEquals(2, two.size)
        assertTrue("big is wider", two[0].width > two[1].width)
        assertEquals("tops aligned is not required but centred vertically is", two[0].top, 1f - two[0].bottom, eps)
        assertEquals("small is roughly half the big one", 0.5f, two[1].width / two[0].width, 0.02f)
    }

    @Test
    fun `a narrower stage keeps every tile 16 by 9 and inside it`() {
        // The tile menu and the layout panel: the grid is laid out in the 552 dp left of the panel.
        for (layout in listOf(MultiviewLayout.SIDE_BY_SIDE, MultiviewLayout.GRID, MultiviewLayout.FOCUS)) {
            val count = if (layout == MultiviewLayout.SIDE_BY_SIDE) 2 else 4
            val rects = MultiviewLayoutPolicy.bounds(layout, count, 552f, 486f)
            rects.forEach {
                assertTrue("$layout inside: $it", it.left >= -eps && it.right <= 1f + eps && it.top >= -eps && it.bottom <= 1f + eps)
                assertEquals("$layout 16:9", 16f / 9f, it.width * 552f / (it.height * 486f), 0.01f)
            }
        }
    }

    @Test
    fun `a tile under 300 dp is small`() {
        assertTrue(MultiviewLayoutPolicy.isSmall(280f))
        assertTrue(!MultiviewLayoutPolicy.isSmall(300f))
        val three = MultiviewLayoutPolicy.bounds(MultiviewLayout.FOCUS, 3)
        assertTrue("small ones in One big", MultiviewLayoutPolicy.isSmall(three[1].dpWidth()))
        assertTrue("the big one is not", !MultiviewLayoutPolicy.isSmall(three[0].dpWidth()))
    }

    @Test
    fun `the layout family is kept across tile counts`() {
        assertEquals(MultiviewLayoutPolicy.Family.ONE_BIG, MultiviewLayoutPolicy.familyOf(MultiviewLayout.FOCUS))
        for (l in listOf(MultiviewLayout.SIDE_BY_SIDE, MultiviewLayout.TWO_STACKED, MultiviewLayout.GRID)) {
            assertEquals("$l", MultiviewLayoutPolicy.Family.SAME_SIZE, MultiviewLayoutPolicy.familyOf(l))
        }
        assertEquals(MultiviewLayout.SIDE_BY_SIDE, MultiviewLayoutPolicy.layoutFor(MultiviewLayoutPolicy.Family.SAME_SIZE, 2))
        assertEquals(MultiviewLayout.GRID, MultiviewLayoutPolicy.layoutFor(MultiviewLayoutPolicy.Family.SAME_SIZE, 3))
        assertEquals(MultiviewLayout.GRID, MultiviewLayoutPolicy.layoutFor(MultiviewLayoutPolicy.Family.SAME_SIZE, 4))
        for (n in 2..4) assertEquals("one big at $n", MultiviewLayout.FOCUS, MultiviewLayoutPolicy.layoutFor(MultiviewLayoutPolicy.Family.ONE_BIG, n))
    }

    @Test
    fun `defaultFor picks single, side by side, then same size grids`() {
        assertEquals("1", MultiviewLayout.SINGLE, MultiviewLayoutPolicy.defaultFor(1))
        assertEquals("2", MultiviewLayout.SIDE_BY_SIDE, MultiviewLayoutPolicy.defaultFor(2))
        assertEquals("3 starts as Same size", MultiviewLayout.GRID, MultiviewLayoutPolicy.defaultFor(3))
        assertEquals("4", MultiviewLayout.GRID, MultiviewLayoutPolicy.defaultFor(4))
        assertEquals("0 falls back to single", MultiviewLayout.SINGLE, MultiviewLayoutPolicy.defaultFor(0))
    }

    @Test
    fun `effective keeps a layout that fits and falls back when it does not`() {
        assertEquals(MultiviewLayout.GRID, MultiviewLayoutPolicy.effective(MultiviewLayout.GRID, 4))
        assertEquals(MultiviewLayout.GRID, MultiviewLayoutPolicy.effective(MultiviewLayout.GRID, 3))
        assertEquals(MultiviewLayout.SIDE_BY_SIDE, MultiviewLayoutPolicy.effective(MultiviewLayout.GRID, 2))
        assertEquals(MultiviewLayout.SINGLE, MultiviewLayoutPolicy.effective(MultiviewLayout.FOCUS, 1))
        assertEquals("one big holds two", MultiviewLayout.FOCUS, MultiviewLayoutPolicy.effective(MultiviewLayout.FOCUS, 2))
        assertEquals(MultiviewLayout.TWO_STACKED, MultiviewLayoutPolicy.effective(MultiviewLayout.TWO_STACKED, 2))
        assertEquals(MultiviewLayout.GRID, MultiviewLayoutPolicy.effective(MultiviewLayout.SIDE_BY_SIDE, 3))
        assertEquals(MultiviewLayout.GRID, MultiviewLayoutPolicy.effective(MultiviewLayout.SINGLE, 4))
    }

    @Test
    fun `bounds caps at four tiles and is empty for none`() {
        assertEquals("cap", 4, MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, 9).size)
        assertTrue("zero", MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, 0).isEmpty())
        assertTrue("negative", MultiviewLayoutPolicy.bounds(MultiviewLayout.GRID, -2).isEmpty())
    }
}
