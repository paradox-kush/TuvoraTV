package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent
import com.nuvio.tv.ui.screens.detail.TrailerPreviewKeyPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B118 (Onn pass 2026-10-03): on a movie detail page, pressing Play ~8 s after landing showed the
 * trailer instead of the sources. The auto trailer preview starts 7 s after the Play button takes
 * focus (default delay), hides the hero buttons and — before this fix — swallowed every key but
 * Back, so a Play press landing just after it started did nothing but leave the trailer playing.
 * The preview only ever arms while Play is focused, so OK during it means "play".
 */
class TrailerPreviewKeyPolicyTest {

    private fun act(keyCode: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        TrailerPreviewKeyPolicy.actionFor(keyCode, action, repeat)

    @Test
    fun `OK during the auto preview plays the title`() {
        assertEquals("DPAD_CENTER down", Action.PLAY, act(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("ENTER down", Action.PLAY, act(KeyEvent.KEYCODE_ENTER))
        assertEquals("NUMPAD_ENTER down", Action.PLAY, act(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals("media PLAY down", Action.PLAY, act(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals("media PLAY_PAUSE down", Action.PLAY, act(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test
    fun `an OK whose press began before the preview still plays on release`() {
        // DOWN landed on the Play button a beat before the preview started; only UP reaches us.
        assertEquals("DPAD_CENTER up", Action.PLAY, act(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP))
    }

    @Test
    fun `a held OK fires once, not per repeat`() {
        assertEquals("auto-repeat", Action.SWALLOW, act(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 3))
    }

    @Test
    fun `Back and Escape keep their meaning (stop the preview)`() {
        assertEquals("BACK", Action.PASS, act(KeyEvent.KEYCODE_BACK))
        assertEquals("ESCAPE", Action.PASS, act(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun `other keys are still swallowed so the page does not scroll under the preview`() {
        assertEquals("DPAD_DOWN", Action.SWALLOW, act(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("DPAD_RIGHT", Action.SWALLOW, act(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("MENU", Action.SWALLOW, act(KeyEvent.KEYCODE_MENU))
    }
}
