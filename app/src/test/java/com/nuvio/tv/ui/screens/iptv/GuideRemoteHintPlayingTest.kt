package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Device pass 2 (2026-11-15). JUnit: (message, expected, actual). */
class GuideRemoteHintPlayingTest {
    /** D5: OK on the row already in the preview goes straight to full screen; the hint must say that. */
    @Test
    fun `the hint matches what OK does on the focused row`() {
        assertEquals(GuideRemoteHintPolicy.Hint.CHANNEL_PLAYING, GuideRemoteHintPolicy.hintFor(categoryFocused = false, aimedIsPlaying = true))
        assertEquals(GuideRemoteHintPolicy.Hint.CHANNEL, GuideRemoteHintPolicy.hintFor(categoryFocused = false, aimedIsPlaying = false))
        assertEquals(GuideRemoteHintPolicy.Hint.CATEGORY, GuideRemoteHintPolicy.hintFor(categoryFocused = true, aimedIsPlaying = true))
    }
}
