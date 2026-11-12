package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.ui.screens.iptv.GuideRemoteHintPolicy.Hint
import org.junit.Assert.assertEquals
import org.junit.Test

/** B253: the first OK on a category enters it; the panel must say so instead of showing row 1's hint. */
class GuideRemoteHintPolicyTest {
    // categoryFocused = the category column holds focus (the screen's `!inCategory`).
    @Test
    fun `category column focused shows the category hint and dims the channel info`() {
        assertEquals("category hint", Hint.CATEGORY, GuideRemoteHintPolicy.hintFor(categoryFocused = true))
        assertEquals("dimmed", true, GuideRemoteHintPolicy.dimChannelInfo(categoryFocused = true))
    }

    @Test
    fun `channel list focused shows the channel hint and full-strength info`() {
        assertEquals("channel hint", Hint.CHANNEL, GuideRemoteHintPolicy.hintFor(categoryFocused = false))
        assertEquals("not dimmed", false, GuideRemoteHintPolicy.dimChannelInfo(categoryFocused = false))
    }
}
