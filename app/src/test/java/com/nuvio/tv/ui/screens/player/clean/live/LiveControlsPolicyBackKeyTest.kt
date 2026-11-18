package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.ui.input.key.Key
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.BackKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BACK must make progress in every state. The key itself is answered by the overlay (key-down consumed,
 * key-up performs the back action) so nothing below it can swallow the press; a paused first frame with the
 * controls up was the state where the platform's own handling ate it on a Fire TV.
 */
class LiveControlsPolicyBackKeyTest {
    @Test
    fun `back key down is consumed so no one below it can act on half a press`() {
        assertEquals("down", BackKey.CONSUME, LiveControlsPolicy.backKey(Key.Back, isKeyDown = true, panelOpen = false))
    }

    @Test
    fun `back key up performs the back action`() {
        assertEquals("up", BackKey.PERFORM, LiveControlsPolicy.backKey(Key.Back, isKeyDown = false, panelOpen = false))
    }

    @Test
    fun `an open panel keeps its own back on both halves`() {
        assertEquals("down", BackKey.PASS, LiveControlsPolicy.backKey(Key.Back, isKeyDown = true, panelOpen = true))
        assertEquals("up", BackKey.PASS, LiveControlsPolicy.backKey(Key.Back, isKeyDown = false, panelOpen = true))
    }

    @Test
    fun `other keys are never answered by the back rule`() {
        assertEquals("dpad", BackKey.PASS, LiveControlsPolicy.backKey(Key.DirectionDown, isKeyDown = false, panelOpen = false))
    }

    @Test
    fun `paused with the controls up hides them, and a second back leaves`() {
        // The state of the field report: paused first frame, controls up, nothing in trouble.
        assertEquals(
            "first back",
            LiveControlsPolicy.BackAction.HIDE_CONTROLS,
            LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = true),
        )
        assertEquals(
            "second back",
            LiveControlsPolicy.BackAction.EXIT,
            LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = false),
        )
    }
}
