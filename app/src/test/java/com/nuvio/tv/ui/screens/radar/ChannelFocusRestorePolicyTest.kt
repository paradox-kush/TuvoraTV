package com.nuvio.tv.ui.screens.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelFocusRestorePolicyTest {
    @Test fun `a focused channel that is still listed keeps focus after a re-group`() {
        assertEquals(
            "focus follows the channel id, not its old row",
            "c",
            ChannelFocusRestorePolicy.target("c", listOf("a", "b", "c"), listOf("c", "a", "b", "d")),
        )
    }

    @Test fun `a channel that vanished hands focus to the nearest remaining row`() {
        // b was at index 1; a (above) and c (below) remain. The row that slid into its place wins.
        assertEquals("c", ChannelFocusRestorePolicy.target("b", listOf("a", "b", "c"), listOf("a", "c")))
    }

    @Test fun `a vanished last row falls back to the nearest row above`() {
        assertEquals("b", ChannelFocusRestorePolicy.target("c", listOf("a", "b", "c"), listOf("a", "b")))
    }

    @Test fun `a vanished row with its neighbours also gone finds the next nearest`() {
        assertEquals("a", ChannelFocusRestorePolicy.target("c", listOf("a", "b", "c"), listOf("a", "x")))
    }

    @Test fun `an empty list has no target`() {
        assertNull(ChannelFocusRestorePolicy.target("a", listOf("a"), emptyList()))
    }

    @Test fun `nothing was focused means nothing to restore`() {
        assertNull(ChannelFocusRestorePolicy.target(null, listOf("a", "b"), listOf("a", "b")))
    }

    @Test fun `a vanished row with no remembered neighbours goes to the first row`() {
        assertEquals("x", ChannelFocusRestorePolicy.target("gone", emptyList(), listOf("x", "y")))
    }
}
