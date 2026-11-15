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
            "layout panel returns to the tile menu it was opened from", MultiviewBackAction.LAYOUT_BACK_TO_MENU,
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
            "the grid leaves through the guard", MultiviewBackAction.LEAVE_GRID,
            MultiviewBackPolicy.action(false, false, false, fullscreen = false),
        )
    }

    // ---- leaving the grid ----

    @Test
    fun `two or more channels take a second back within 2500 ms to leave`() {
        val guard = MultiviewLeaveGuard()
        assertEquals("first back only arms", MultiviewLeaveGuard.Result.ARMED, guard.onBack(10_000, channelsOnScreen = 2))
        assertEquals("second back inside the window leaves", MultiviewLeaveGuard.Result.LEAVE, guard.onBack(12_400, 2))
        assertEquals("and it is armed afresh after leaving", MultiviewLeaveGuard.Result.ARMED, guard.onBack(12_500, 2))
    }

    @Test
    fun `a second back after the window only arms again`() {
        val guard = MultiviewLeaveGuard()
        guard.onBack(0, 3)
        assertEquals(MultiviewLeaveGuard.Result.ARMED, guard.onBack(2_501, 3))
        assertEquals(MultiviewLeaveGuard.Result.LEAVE, guard.onBack(3_000, 3))
    }

    @Test
    fun `fewer than two channels leave at once`() {
        assertEquals(MultiviewLeaveGuard.Result.LEAVE, MultiviewLeaveGuard().onBack(0, 0))
        assertEquals(MultiviewLeaveGuard.Result.LEAVE, MultiviewLeaveGuard().onBack(0, 1))
    }

    @Test
    fun `moving the highlight cancels the armed state`() {
        val guard = MultiviewLeaveGuard()
        guard.onBack(0, 2)
        guard.disarm()
        assertEquals(MultiviewLeaveGuard.Result.ARMED, guard.onBack(500, 2))
    }

    @Test
    fun `notices last as long as the design says and the leave guard matches its window`() {
        assertEquals(8_000L, MultiviewNotice.Capacity(2).durationMs)
        assertEquals(4_000L, MultiviewNotice.PickFailed("A", "No data").durationMs)
        assertEquals(4_000L, MultiviewNotice.CannotAdd("A").durationMs)
        assertEquals(3_500L, MultiviewNotice.AlreadyOnScreen("A").durationMs)
        assertEquals(MultiviewLeaveGuard.WINDOW_MS, MultiviewNotice.LeaveGuard.durationMs)
    }

    // ---- what shows over the video ----

    @Test
    fun `labels and the hint stay for five seconds after a key`() {
        assertTrue(MultiviewChromePolicy.visible(0))
        assertTrue(MultiviewChromePolicy.visible(4_999))
        assertFalse(MultiviewChromePolicy.visible(5_000))
        assertEquals(5_000L, MultiviewChromePolicy.HIDE_AFTER_MS)
    }

    @Test
    fun `a tile that is loading says tuning in then still trying and a returning tile says rejoining`() {
        assertEquals(TileLoadingPhase.TUNING_IN, TileLoadingPolicy.phase(0, rejoining = false))
        assertEquals(TileLoadingPhase.TUNING_IN, TileLoadingPolicy.phase(7_999, rejoining = false))
        assertEquals(TileLoadingPhase.STILL_TRYING, TileLoadingPolicy.phase(8_000, rejoining = false))
        assertEquals(TileLoadingPhase.REJOINING, TileLoadingPolicy.phase(0, rejoining = true))
        assertEquals("rejoining wins even when slow", TileLoadingPhase.REJOINING, TileLoadingPolicy.phase(15_000, rejoining = true))
    }

    // ---- hint bar ----

    @Test
    fun `the hint names what OK does for the focused tile`() {
        assertEquals(GridHint.Kind.PLAYING, MultiviewHintPolicy.forTile(TileStatus.PLAYING, null, false, null).kind)
        assertEquals("loading is a playing tile", GridHint.Kind.PLAYING, MultiviewHintPolicy.forTile(TileStatus.LOADING, null, false, null).kind)
        assertEquals(GridHint.Kind.EMPTY, MultiviewHintPolicy.forTile(TileStatus.EMPTY, null, false, null).kind)
        val failed = MultiviewHintPolicy.forTile(TileStatus.FAILED, TileFailure.StreamGone, false, null)
        assertEquals(GridHint.Kind.FAILED, failed.kind)
        assertEquals("the failure's main action", TileAction.REPLACE, failed.failureOk)
        assertEquals(TileAction.RETRY, MultiviewHintPolicy.forTile(TileStatus.FAILED, TileFailure.Network, false, null).failureOk)
        assertEquals(GridHint.Kind.FULLSCREEN, MultiviewHintPolicy.forTile(TileStatus.PLAYING, null, true, null).kind)
    }

    @Test
    fun `the hint names the tile that keeps the sound`() {
        assertEquals("XC2", MultiviewHintPolicy.forTile(TileStatus.PLAYING, null, false, "XC2").soundKeptOn)
        assertNull(MultiviewHintPolicy.forTile(TileStatus.PLAYING, null, false, null).soundKeptOn)
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
    fun `the layout panel has exactly two choices with different names for every tile count`() {
        for (count in 2..4) {
            val options = MultiviewLayoutChoices.options(count)
            assertEquals("count $count", listOf(MultiviewLayoutPolicy.Family.SAME_SIZE, MultiviewLayoutPolicy.Family.ONE_BIG),
                options.map { MultiviewLayoutPolicy.familyOf(it.layout) })
            assertTrue("never changes the tile count", options.all { it.tileCount == count })
            assertTrue("all valid", options.all { it.layout.supports(it.tileCount) })
        }
    }

    @Test
    fun `same size is side by side for two and a grid for three or four`() {
        assertEquals(LayoutChoice(MultiviewLayout.SIDE_BY_SIDE, 2), MultiviewLayoutChoices.options(2)[0])
        assertEquals(LayoutChoice(MultiviewLayout.GRID, 3), MultiviewLayoutChoices.options(3)[0])
        assertEquals(LayoutChoice(MultiviewLayout.GRID, 4), MultiviewLayoutChoices.options(4)[0])
        assertEquals(LayoutChoice(MultiviewLayout.FOCUS, 2), MultiviewLayoutChoices.options(2)[1])
        assertEquals(LayoutChoice(MultiviewLayout.FOCUS, 4), MultiviewLayoutChoices.options(4)[1])
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
    fun `a playing tile leads with keep sound here and has no full screen item`() {
        assertEquals(
            listOf(
                TileMenuItem.KEEP_SOUND, TileMenuItem.REPLACE_CHANNEL, TileMenuItem.CLEAR_CHANNEL,
                TileMenuItem.ADD_ANOTHER, TileMenuItem.CHANGE_LAYOUT, TileMenuItem.EXIT,
            ),
            items(TileStatus.PLAYING),
        )
        assertEquals("with three tiles remove takes the tile away", TileMenuItem.REMOVE, items(TileStatus.PLAYING, count = 3)[2])
    }

    @Test
    fun `add another channel is offered below four tiles only and layout is always there`() {
        assertTrue(TileMenuItem.ADD_ANOTHER in items(TileStatus.PLAYING, count = 3))
        assertFalse(TileMenuItem.ADD_ANOTHER in items(TileStatus.PLAYING, count = 4))
        assertTrue(TileMenuItem.CHANGE_LAYOUT in items(TileStatus.PLAYING, count = 4))
        assertTrue("the Multiview group is last", items(TileStatus.PLAYING, count = 3).takeLast(3).all { it.inMultiviewGroup })
    }

    @Test
    fun `a tile that keeps the sound offers to follow the highlight instead`() {
        val menu = items(TileStatus.PLAYING, pinned = true)
        assertEquals("first row", TileMenuItem.FOLLOW_FOCUS, menu.first())
        assertFalse("not keep sound", TileMenuItem.KEEP_SOUND in menu)
    }

    @Test
    fun `a failed tile's menu leads with the main action for that failure then the others in order`() {
        assertEquals(
            listOf(
                TileMenuItem.RETRY, TileMenuItem.REPLACE_CHANNEL, TileMenuItem.OPEN_IN_PLAYER, TileMenuItem.CLEAR_CHANNEL,
                TileMenuItem.ADD_ANOTHER, TileMenuItem.CHANGE_LAYOUT, TileMenuItem.EXIT,
            ),
            items(TileStatus.FAILED, TileFailure.NoData),
        )
        val unsupported = items(TileStatus.FAILED, TileFailure.Unsupported)
        assertFalse("no retry for an unsupported format", TileMenuItem.RETRY in unsupported)
        assertEquals("the way out leads", TileMenuItem.OPEN_IN_PLAYER, unsupported.first())
    }

    @Test
    fun `a stream that is gone or a channel missing from the playlist offers replace first and remove and no retry`() {
        for (failure in listOf(TileFailure.StreamGone, TileFailure.ChannelUnavailable)) {
            val menu = items(TileStatus.FAILED, failure, count = 3)
            assertEquals("$failure: replace leads", TileMenuItem.REPLACE_CHANNEL, menu.first())
            assertEquals("$failure: remove follows", TileMenuItem.REMOVE, menu[1])
            assertFalse("$failure: no retry", TileMenuItem.RETRY in menu)
            assertFalse("$failure: the stream is not there for the full player either", TileMenuItem.OPEN_IN_PLAYER in menu)
        }
    }

    @Test
    fun `every failure's first menu row is its main action`() {
        val expected = mapOf(
            TileAction.RETRY to TileMenuItem.RETRY,
            TileAction.REPLACE to TileMenuItem.REPLACE_CHANNEL,
            TileAction.OPEN_IN_PLAYER to TileMenuItem.OPEN_IN_PLAYER,
        )
        TileFailure.entries.forEach {
            assertEquals("$it", expected.getValue(it.primary), items(TileStatus.FAILED, it).first())
        }
    }

    @Test
    fun `an empty tile offers add and only offers to take the tile away when more than two exist`() {
        assertEquals(TileMenuItem.ADD_CHANNEL, items(TileStatus.EMPTY).first())
        assertFalse(TileMenuItem.REMOVE_TILE in items(TileStatus.EMPTY, count = 2))
        assertTrue(TileMenuItem.REMOVE_TILE in items(TileStatus.EMPTY, count = 3))
    }

    @Test
    fun `full screen offers the way back the full player and exit`() {
        assertEquals(
            listOf(TileMenuItem.BACK_TO_GRID, TileMenuItem.WATCH_IN_PLAYER, TileMenuItem.EXIT),
            items(TileStatus.PLAYING, fullscreen = true),
        )
        assertEquals(
            listOf(TileMenuItem.BACK_TO_GRID, TileMenuItem.RETRY, TileMenuItem.WATCH_IN_PLAYER, TileMenuItem.EXIT),
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
    fun `a v2 grid keeps the channel names next to the keys`() {
        val grid = SavedGrid(
            MultiviewLayout.GRID,
            listOf("xtream:a:live:1", null, "xtream:b:live:2"),
            listOf("XC1 Archive Alpha", null, null),
        )
        assertEquals("v2;GRID;xtream:a:live:1,XC1 Archive Alpha;;xtream:b:live:2", MultiviewGridCodec.encode(grid))
        assertEquals(grid, MultiviewGridCodec.decode(MultiviewGridCodec.encode(grid)))
    }

    @Test
    fun `names holding the separators survive`() {
        val grid = SavedGrid(MultiviewLayout.SIDE_BY_SIDE, listOf("k1", "k2"), listOf("A; B, C %3B", "100%"))
        assertEquals(grid, MultiviewGridCodec.decode(MultiviewGridCodec.encode(grid)))
    }

    @Test
    fun `a v1 record still decodes, with no names`() {
        val decoded = MultiviewGridCodec.decode("v1;SIDE_BY_SIDE;xtream:acc:live:7;")
        assertEquals(SavedGrid(MultiviewLayout.SIDE_BY_SIDE, listOf("xtream:acc:live:7", null)), decoded)
        assertEquals(listOf<String?>(null, null), decoded?.names)
    }

    @Test
    fun `a bad record is nothing to resume`() {
        assertNull("null", MultiviewGridCodec.decode(null))
        assertNull("blank", MultiviewGridCodec.decode(" "))
        assertNull("old version", MultiviewGridCodec.decode("v0;GRID;a;b"))
        assertNull("unknown layout", MultiviewGridCodec.decode("v2;SPIRAL;a;b"))
        assertNull("no slots", MultiviewGridCodec.decode("v2;GRID"))
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
        assertEquals("v2;SIDE_BY_SIDE;xtream:acc:live:7;", encoded)
    }
}
