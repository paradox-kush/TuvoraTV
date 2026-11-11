package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.BackAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B239 / B227: with the stream in trouble the controls keep coming back (a reconnect re-reveals them, and
 * while tuning they never auto-hide), so BACK only ever hid a bar that returned and the viewer had to
 * force-stop. In trouble, BACK leaves fullscreen in one press; an open panel still closes first.
 */
class LiveControlsPolicyBackInTroubleTest {

    @Test
    fun `failed playback leaves in one press with the controls up`() {
        assertEquals("failed", BackAction.EXIT,
            LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = true, failed = true))
    }

    @Test
    fun `reconnecting leaves in one press with the controls up`() {
        assertEquals("reconnecting", BackAction.EXIT,
            LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = true, reconnecting = true))
    }

    @Test
    fun `tuning leaves in one press with the controls up`() {
        assertEquals("tuning", BackAction.EXIT,
            LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = true, tuning = true))
    }

    @Test
    fun `an open panel still closes first even in trouble`() {
        assertEquals("panel first", BackAction.CLOSE_PANEL,
            LiveControlsPolicy.backAction(panelOpen = true, controlsVisible = true, failed = true, reconnecting = true, tuning = true))
    }

    @Test
    fun `healthy playback and an intentional pause keep hide then leave`() {
        assertEquals("healthy, controls up", BackAction.HIDE_CONTROLS,
            LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = true))
        assertEquals("healthy, controls hidden", BackAction.EXIT,
            LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = false))
        // A pause is not trouble: the viewer may want the controls gone and the picture held.
        assertEquals("paused, controls up", BackAction.HIDE_CONTROLS,
            LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = true, failed = false, reconnecting = false, tuning = false))
    }

    @Test
    fun `trouble with the controls hidden still leaves`() {
        assertEquals(BackAction.EXIT,
            LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = false, reconnecting = true))
    }
}
