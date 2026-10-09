package com.nuvio.tv.core.build

import org.junit.Assert.assertEquals
import org.junit.Test

class NoAddonsHintPolicyTest {
    @Test
    fun `full builds point at add-ons`() {
        assertEquals(NoAddonsHint.INSTALL_ADDONS, NoAddonsHintPolicy.hint(addonsEnabled = true, hasAnyIptvPlaylist = false))
        assertEquals(NoAddonsHint.INSTALL_ADDONS, NoAddonsHintPolicy.hint(addonsEnabled = true, hasAnyIptvPlaylist = true))
    }

    @Test
    fun `store build without a playlist asks for one`() {
        assertEquals(NoAddonsHint.ADD_IPTV_PLAYLIST, NoAddonsHintPolicy.hint(addonsEnabled = false, hasAnyIptvPlaylist = false))
    }

    // Regression: the playstore build told a viewer who already had a playlist to add one.
    @Test
    fun `store build with a playlist never asks for one`() {
        assertEquals(NoAddonsHint.PLAYLIST_PRESENT, NoAddonsHintPolicy.hint(addonsEnabled = false, hasAnyIptvPlaylist = true))
    }
}
