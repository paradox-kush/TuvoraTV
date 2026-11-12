package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.*
import org.junit.Test

class GuideFocusRestorePolicyTest {
    @Test fun `after zapping the guide highlights the channel now playing, not the one entered from`() {
        assertEquals("nature", GuideFocusRestorePolicy.target(playing = "nature", origin = "news", visibleIds = listOf("news", "sports", "nature")))
    }

    @Test fun `with no zap the playing channel is the entry row anyway`() {
        assertEquals("news", GuideFocusRestorePolicy.target(playing = "news", origin = "news", visibleIds = listOf("sports", "news")))
    }

    @Test fun `a playing channel that is not in this list falls back to the entry row`() {
        // e.g. All favorites mixes playlists, or a catalog update removed it.
        assertEquals("news", GuideFocusRestorePolicy.target(playing = "elsewhere", origin = "news", visibleIds = listOf("sports", "news")))
        assertEquals("news", GuideFocusRestorePolicy.target(playing = null, origin = "news", visibleIds = listOf("sports", "news")))
    }

    @Test fun `with neither playing nor entry row visible it falls back to the first row`() {
        assertEquals("nature", GuideFocusRestorePolicy.target(playing = "gone", origin = "also-gone", visibleIds = listOf("nature", "sports")))
        assertNull(GuideFocusRestorePolicy.target(playing = "gone", origin = "also-gone", visibleIds = emptyList()))
    }

    @Test fun `reordered rows do not matter because identities are used`() {
        assertEquals("sports", GuideFocusRestorePolicy.target(playing = "sports", origin = "news", visibleIds = listOf("sports", "news")))
    }

    // UX168: hiding the focused channel left focusedChannelId on the removed row, so the notice-expiry
    // restore found no row to aim at and the list's own fallback picked one (~3 rows up, or row 1).
    private val lineup = listOf("a", "b", "c", "d", "e", "f", "g", "h")

    @Test fun `a focused channel that is still listed keeps focus`() {
        assertEquals("d", GuideFocusRestorePolicy.afterListChange("d", lineup, lineup - "b"))
    }

    @Test fun `a removed mid-list channel hands focus to the row that slid up into its place`() {
        assertEquals("e", GuideFocusRestorePolicy.afterListChange("d", lineup, lineup - "d"))
    }

    @Test fun `a removed last channel hands focus to the one above it`() {
        assertEquals("g", GuideFocusRestorePolicy.afterListChange("h", lineup, lineup - "h"))
    }

    @Test fun `neighbours that are also gone are skipped in the same direction first`() {
        assertEquals("f", GuideFocusRestorePolicy.afterListChange("d", lineup, lineup - setOf("d", "e")))
        assertEquals("c", GuideFocusRestorePolicy.afterListChange("g", lineup, listOf("a", "b", "c")))
    }

    @Test fun `no focus before means no focus invented`() {
        assertNull(GuideFocusRestorePolicy.afterListChange(null, lineup, lineup))
    }

    @Test fun `a previous focus that was never in the old list falls back to the first row`() {
        assertEquals("a", GuideFocusRestorePolicy.afterListChange("zzz", lineup, lineup))
        assertNull(GuideFocusRestorePolicy.afterListChange("d", lineup, emptyList()))
    }
}
