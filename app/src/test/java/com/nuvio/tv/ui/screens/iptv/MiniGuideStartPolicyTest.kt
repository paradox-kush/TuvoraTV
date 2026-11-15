package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UX107: after hiding the channel being watched, the LEFT mini-guide opened on row 1 (the playing id is no
 * longer a row and the start index fell back to 0). The aimed neighbour is the anchor then.
 */
class MiniGuideStartPolicyTest {
    private val rows = listOf("a", "b", "c", "d", "e")

    @Test
    fun `the playing channel is the anchor`() {
        assertEquals(2, MiniGuideStartPolicy.startIndex(rows, playingId = "c", aimedId = "e"))
    }

    @Test
    fun `a hidden playing channel anchors on the aimed neighbour, not row one`() {
        assertEquals(3, MiniGuideStartPolicy.startIndex(rows, playingId = "hidden", aimedId = "d"))
    }

    @Test
    fun `no anchor at all starts on row one`() {
        assertEquals(0, MiniGuideStartPolicy.startIndex(rows, playingId = null, aimedId = null))
        assertEquals(0, MiniGuideStartPolicy.startIndex(rows, playingId = "x", aimedId = "y"))
        assertEquals(0, MiniGuideStartPolicy.startIndex(emptyList(), playingId = "x", aimedId = "y"))
    }
}
