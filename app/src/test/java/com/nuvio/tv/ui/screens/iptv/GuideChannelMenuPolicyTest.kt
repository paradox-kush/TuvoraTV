package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.ui.screens.iptv.GuideChannelMenuPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideChannelMenuPolicyTest {

    @Test
    fun `an ordinary channel is still hidden straight away`() {
        assertNull(GuideChannelMenuPolicy.menu(special = null, isPinned = false, indexInList = 3, listSize = 10))
        assertNull(GuideChannelMenuPolicy.menu(special = GuideSpecial.RECENT, isPinned = true, indexInList = 0, listSize = 2))
    }

    @Test
    fun `a favourite moves and can be removed but not hidden`() {
        assertEquals(listOf(Action.MOVE_UP, Action.MOVE_DOWN, Action.REMOVE_FAVORITE),
            GuideChannelMenuPolicy.menu(GuideSpecial.ALL_FAVORITES, isPinned = false, indexInList = 1, listSize = 3))
        assertEquals("top: no move up", listOf(Action.MOVE_DOWN, Action.REMOVE_FAVORITE),
            GuideChannelMenuPolicy.menu(GuideSpecial.FAVORITES, isPinned = true, indexInList = 0, listSize = 3))
    }

    @Test
    fun `a pinned channel moves among the pinned and can be hidden`() {
        assertEquals("last pinned: no move down", listOf(Action.MOVE_UP, Action.HIDE),
            GuideChannelMenuPolicy.menu(null, isPinned = true, indexInList = 2, listSize = 3))
    }
}
