package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sim pass 2026-11-16 (UX): a channel banner on entering full screen. JUnit: (message, expected, actual). */
class GuideBannerPolicyTest {

    @Test
    fun `entering full screen shows the banner for 3 s`() {
        assertEquals("entry banner", 3_000L, GuideBannerPolicy.durationMs(GuideBannerPolicy.Reason.ENTRY))
    }

    @Test
    fun `a zap keeps the 4 s banner`() {
        assertEquals("zap banner", LiveZapModePolicy.BANNER_MS, GuideBannerPolicy.durationMs(GuideBannerPolicy.Reason.ZAP))
    }

    @Test
    fun `the entry banner names the channel playing, not the row aimed at`() {
        assertEquals("Play/Pause from another row", "playing", GuideBannerPolicy.channelId(GuideBannerPolicy.Reason.ENTRY, aimed = "aimed", playing = "playing"))
        assertEquals("nothing playing yet", "aimed", GuideBannerPolicy.channelId(GuideBannerPolicy.Reason.ENTRY, aimed = "aimed", playing = null))
    }

    @Test
    fun `a zap banner names the aimed channel`() {
        assertEquals("surf aim", "aimed", GuideBannerPolicy.channelId(GuideBannerPolicy.Reason.ZAP, aimed = "aimed", playing = "playing"))
    }
}
