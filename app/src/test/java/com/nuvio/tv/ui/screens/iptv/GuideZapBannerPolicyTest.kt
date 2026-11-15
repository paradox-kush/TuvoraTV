package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Device pass 2 (2026-11-15). JUnit: (message, expected, actual). */
class GuideZapBannerPolicyTest {
    /** D4: a surf aim reads as a question about the target, the channel still playing right under it. */
    @Test
    fun `a pending surf aim is headed Switch to with Still watching under it`() {
        assertEquals(GuideZapBannerPolicy.Heading.SWITCH_TO, GuideZapBannerPolicy.heading(surfing = true))
        assertEquals(true, GuideZapBannerPolicy.stillWatchingUnderHeading(surfing = true))
        assertEquals("a plain zap names the channel", GuideZapBannerPolicy.Heading.NAME, GuideZapBannerPolicy.heading(surfing = false))
        assertEquals(false, GuideZapBannerPolicy.stillWatchingUnderHeading(surfing = false))
    }
}
