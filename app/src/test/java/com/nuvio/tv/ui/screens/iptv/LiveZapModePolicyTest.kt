package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Redesign change 4 (05-fullscreen, A3). JUnit: (message, expected, actual). */
class LiveZapModePolicyTest {

    @Test
    fun `single-use playlists surf and the rest switch at once`() {
        assertEquals("Stalker", LiveZapModePolicy.Mode.SURF, LiveZapModePolicy.mode(singleUseLinks = true))
        assertEquals("Xtream / M3U", LiveZapModePolicy.Mode.SWITCH, LiveZapModePolicy.mode(singleUseLinks = false))
        assertEquals(
            "the owner's flip turns surf off",
            LiveZapModePolicy.Mode.SWITCH,
            LiveZapModePolicy.mode(singleUseLinks = true, surfForSingleUse = false),
        )
    }

    @Test
    fun `the surf countdown and banner timings match the design`() {
        assertEquals("surf commit", 1_500L, LiveZapModePolicy.SURF_COMMIT_MS)
        assertEquals("banner", 4_000L, LiveZapModePolicy.BANNER_MS)
    }

    @Test
    fun `OK during a surf countdown tunes the aim now and otherwise shows the bar`() {
        assertEquals("aimed elsewhere", LiveZapModePolicy.OkAction.TUNE_AIM_NOW, LiveZapModePolicy.okAction("b", "a"))
        assertEquals("no aim", LiveZapModePolicy.OkAction.SHOW_CONTROLS, LiveZapModePolicy.okAction(null, "a"))
        assertEquals("aimed back at what plays", LiveZapModePolicy.OkAction.SHOW_CONTROLS, LiveZapModePolicy.okAction("a", "a"))
    }

    @Test
    fun `Back during a surf countdown cancels the aim and otherwise does its usual job`() {
        assertEquals("aim pending", true, LiveZapModePolicy.backCancelsSurf("b", "a"))
        assertEquals("nothing pending", false, LiveZapModePolicy.backCancelsSurf(null, "a"))
    }

    @Test
    fun `a zap shows the banner only while the control bar is down`() {
        assertEquals("bar down", true, LiveZapModePolicy.showsBanner(controlsVisible = false))
        assertEquals("bar up", false, LiveZapModePolicy.showsBanner(controlsVisible = true))
    }
}
