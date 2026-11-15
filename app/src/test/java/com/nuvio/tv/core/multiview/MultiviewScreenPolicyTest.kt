package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewScreenPolicyTest {
    // ---- Back ----

    @Test
    fun `back closes the innermost thing first`() {
        assertEquals(
            "picker before everything", MultiviewBackAction.CLOSE_PICKER,
            MultiviewBackPolicy.action(channelPickerOpen = true, layoutPickerOpen = true, tileMenuOpen = true, fullscreen = true),
        )
        assertEquals(
            "layout picker before the menu", MultiviewBackAction.CLOSE_LAYOUT_PICKER,
            MultiviewBackPolicy.action(false, layoutPickerOpen = true, tileMenuOpen = true, fullscreen = true),
        )
        assertEquals(
            "menu before full screen", MultiviewBackAction.CLOSE_TILE_MENU,
            MultiviewBackPolicy.action(false, false, tileMenuOpen = true, fullscreen = true),
        )
        assertEquals(
            "full screen returns to the grid", MultiviewBackAction.EXIT_FULLSCREEN,
            MultiviewBackPolicy.action(false, false, false, fullscreen = true),
        )
        assertEquals(
            "the grid exits multiview", MultiviewBackAction.EXIT_MULTIVIEW,
            MultiviewBackPolicy.action(false, false, false, fullscreen = false),
        )
    }

    // ---- OK short and long press ----

    @Test
    fun `a short press activates on key up`() {
        val t = CenterPressTracker()
        assertEquals("down", CenterPressTracker.Result.NONE, t.onKeyDown(0))
        assertEquals("up", CenterPressTracker.Result.ACTIVATE, t.onKeyUp())
    }

    @Test
    fun `a held press opens the menu once and its key up does nothing`() {
        val t = CenterPressTracker()
        t.onKeyDown(0)
        assertEquals("first repeat", CenterPressTracker.Result.LONG_PRESS, t.onKeyDown(1))
        assertEquals("more repeats", CenterPressTracker.Result.NONE, t.onKeyDown(2))
        assertEquals("release", CenterPressTracker.Result.NONE, t.onKeyUp())
    }

    @Test
    fun `a key up whose key down happened elsewhere is ignored`() {
        val t = CenterPressTracker()
        assertEquals("orphan up", CenterPressTracker.Result.NONE, t.onKeyUp())
        assertEquals("orphan repeat", CenterPressTracker.Result.NONE, t.onKeyDown(3))
    }

    @Test
    fun `a long press whose key up went to a panel does not swallow the next short press`() {
        val t = CenterPressTracker()
        t.onKeyDown(0)
        t.onKeyDown(1) // menu opened; the KeyUp is never delivered here
        t.onKeyDown(0)
        assertEquals("next short press", CenterPressTracker.Result.ACTIVATE, t.onKeyUp())
    }

    // ---- layout choices ----

    @Test
    fun `layout choices never offer fewer tiles than are on screen`() {
        for (count in 1..4) {
            val options = MultiviewLayoutChoices.options(count)
            assertTrue("count $count has options", options.isNotEmpty())
            assertTrue("none below $count", options.all { it.tileCount >= count })
            assertTrue("all valid", options.all { it.layout.supports(it.tileCount) })
        }
    }

    @Test
    fun `one tile can grow into any arrangement and four tiles only rearrange`() {
        assertEquals(
            listOf(
                LayoutChoice(MultiviewLayout.SINGLE, 1),
                LayoutChoice(MultiviewLayout.SIDE_BY_SIDE, 2),
                LayoutChoice(MultiviewLayout.TWO_STACKED, 2),
                LayoutChoice(MultiviewLayout.FOCUS, 3),
                LayoutChoice(MultiviewLayout.FOCUS, 4),
                LayoutChoice(MultiviewLayout.GRID, 3),
                LayoutChoice(MultiviewLayout.GRID, 4),
            ),
            MultiviewLayoutChoices.options(1),
        )
        assertEquals(
            listOf(LayoutChoice(MultiviewLayout.FOCUS, 4), LayoutChoice(MultiviewLayout.GRID, 4)),
            MultiviewLayoutChoices.options(4),
        )
    }

    // ---- full screen ----

    @Test
    fun `full screen closes every tile but the chosen one`() {
        assertEquals(listOf(0, 2, 3), MultiviewFullscreenPolicy.hiddenSlots(4, 1))
        assertEquals(emptyList<Int>(), MultiviewFullscreenPolicy.hiddenSlots(1, 0))
    }

    // ---- tile menu ----

    private fun items(
        status: TileStatus,
        failure: TileFailure? = null,
        pinned: Boolean = false,
        count: Int = 2,
        fullscreen: Boolean = false,
    ) = MultiviewTileMenuPolicy.items(status, failure, pinned, count, fullscreen)

    @Test
    fun `a playing tile offers full screen sound replace remove layout and exit`() {
        assertEquals(
            listOf(
                TileMenuItem.FULL_SCREEN, TileMenuItem.KEEP_SOUND, TileMenuItem.REPLACE_CHANNEL,
                TileMenuItem.REMOVE, TileMenuItem.CHANGE_LAYOUT, TileMenuItem.EXIT,
            ),
            items(TileStatus.PLAYING),
        )
    }

    @Test
    fun `a tile that keeps the sound offers to follow focus instead`() {
        val menu = items(TileStatus.PLAYING, pinned = true)
        assertTrue("follow focus", TileMenuItem.FOLLOW_FOCUS in menu)
        assertFalse("not keep sound", TileMenuItem.KEEP_SOUND in menu)
    }

    @Test
    fun `retry appears only when the failure allows it and leads the menu`() {
        val retryable = items(TileStatus.FAILED, TileFailure.NoData)
        assertEquals("retry first", TileMenuItem.RETRY, retryable.first())
        assertTrue("open in the full player", TileMenuItem.OPEN_IN_PLAYER in retryable)
        val unsupported = items(TileStatus.FAILED, TileFailure.Unsupported)
        assertFalse("no retry for an unsupported format", TileMenuItem.RETRY in unsupported)
        assertEquals("the way out leads", TileMenuItem.OPEN_IN_PLAYER, unsupported.first())
    }

    @Test
    fun `a stream that is gone offers replace and remove first and no retry`() {
        val menu = items(TileStatus.FAILED, TileFailure.StreamGone)
        assertEquals("replace leads", TileMenuItem.REPLACE_CHANNEL, menu.first())
        assertEquals("remove follows", TileMenuItem.REMOVE, menu[1])
        assertFalse("no retry", TileMenuItem.RETRY in menu)
        assertFalse("the same link would fail in the full player too", TileMenuItem.OPEN_IN_PLAYER in menu)
    }

    @Test
    fun `an empty tile offers add and only offers remove when another tile exists`() {
        assertEquals(TileMenuItem.ADD_CHANNEL, items(TileStatus.EMPTY).first())
        assertTrue(TileMenuItem.REMOVE in items(TileStatus.EMPTY, count = 2))
        assertFalse(TileMenuItem.REMOVE in items(TileStatus.EMPTY, count = 1))
    }

    @Test
    fun `full screen offers the way back and exit`() {
        assertEquals(
            listOf(TileMenuItem.BACK_TO_GRID, TileMenuItem.EXIT),
            items(TileStatus.PLAYING, fullscreen = true),
        )
        assertEquals(
            listOf(TileMenuItem.BACK_TO_GRID, TileMenuItem.RETRY, TileMenuItem.EXIT),
            items(TileStatus.FAILED, TileFailure.Network, fullscreen = true),
        )
    }

    // ---- remembered grid ----

    @Test
    fun `a grid round-trips layout and keys with empty slots`() {
        val grid = SavedGrid(MultiviewLayout.GRID, listOf("xtream:a:live:1", null, "xtream:b:live:2"))
        assertEquals(grid, MultiviewGridCodec.decode(MultiviewGridCodec.encode(grid)))
    }

    @Test
    fun `keys holding the separator and the escape character survive`() {
        val grid = SavedGrid(MultiviewLayout.SIDE_BY_SIDE, listOf("a;b%3Bc%", ";"))
        assertEquals(grid, MultiviewGridCodec.decode(MultiviewGridCodec.encode(grid)))
    }

    @Test
    fun `a bad record is nothing to resume`() {
        assertNull("null", MultiviewGridCodec.decode(null))
        assertNull("blank", MultiviewGridCodec.decode(" "))
        assertNull("old version", MultiviewGridCodec.decode("v0;GRID;a;b"))
        assertNull("unknown layout", MultiviewGridCodec.decode("v1;SPIRAL;a;b"))
        assertNull("no slots", MultiviewGridCodec.decode("v1;GRID"))
    }

    @Test
    fun `a layout that cannot hold the saved slots falls back to the default for that count`() {
        val decoded = MultiviewGridCodec.decode("v1;SINGLE;a;b;c")
        assertEquals("three slots", 3, decoded?.keys?.size)
        assertEquals("default for three", MultiviewLayoutPolicy.defaultFor(3), decoded?.layout)
    }

    @Test
    fun `the saved record holds channel keys only`() {
        val encoded = MultiviewGridCodec.encode(SavedGrid(MultiviewLayout.SIDE_BY_SIDE, listOf("xtream:acc:live:7", null)))
        assertEquals("v1;SIDE_BY_SIDE;xtream:acc:live:7;", encoded)
    }
}
