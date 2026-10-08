package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.*
import org.junit.Test

class GuidePlaybackInteractionPolicyTest {
    @Test fun `moving focus clears a channel action notice without clearing playback state`() {
        val state = LiveGuideUiState(focusedChannelId = "old", actionError = "No recording")
        val next = GuidePlaybackInteractionPolicy.focus(state, "new")
        assertEquals("new", next.focusedChannelId)
        assertNull(next.actionError)
    }
    @Test fun `OK on a pending initial tune opens fullscreen without another tune`() {
        assertEquals(GuidePlaybackInteractionPolicy.Click.FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("new", null, "new", false))
    }
    @Test fun `OK on a pending zap does not retry the previous failed channel`() {
        assertEquals(GuidePlaybackInteractionPolicy.Click.FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("new", "old", "new", false))
    }
    @Test fun `another channel tunes and settled failure retries`() {
        assertEquals(GuidePlaybackInteractionPolicy.Click.TUNE,
            GuidePlaybackInteractionPolicy.click("other", "old", "new", true))
        assertEquals(GuidePlaybackInteractionPolicy.Click.RETRY,
            GuidePlaybackInteractionPolicy.click("old", "old", null, false))
        assertEquals(GuidePlaybackInteractionPolicy.Click.FULLSCREEN,
            GuidePlaybackInteractionPolicy.click("old", "old", null, true))
    }
    @Test fun `focus on another channel cannot inherit the playback error`() {
        assertFalse(GuidePlaybackInteractionPolicy.showFocusedError("new", "old"))
        assertFalse(GuidePlaybackInteractionPolicy.showFocusedError(null, "old"))
        assertTrue(GuidePlaybackInteractionPolicy.showFocusedError("old", "old"))
    }
    @Test fun `terminal playback error covers the retained video frame`() {
        assertTrue(GuidePlaybackInteractionPolicy.coverFailedFrame(true))
        assertFalse(GuidePlaybackInteractionPolicy.coverFailedFrame(false))
    }
}
