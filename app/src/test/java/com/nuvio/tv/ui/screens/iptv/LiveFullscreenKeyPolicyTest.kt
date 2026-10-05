package com.nuvio.tv.ui.screens.iptv

import androidx.compose.ui.input.key.Key
import com.nuvio.tv.ui.screens.iptv.LiveFullscreenKeyPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F08: the guide's fullscreen live keys, pulled out of the Composable. What was there stays
 * (OK play/pause, UP/DOWN zap, MENU hide, any other key shows the overlay); new: LEFT opens the
 * channel list without leaving fullscreen, the remote's LAST/RECALL key jumps back to the previous
 * channel, and the dedicated CH+/CH- keys zap like UP/DOWN.
 */
class LiveFullscreenKeyPolicyTest {

    private fun action(
        key: Key,
        isKeyDown: Boolean = true,
        paused: Boolean = false,
        channelListOpen: Boolean = false,
        noticeShowing: Boolean = false,
    ) = LiveFullscreenKeyPolicy.actionFor(key, isKeyDown, paused, channelListOpen, noticeShowing)

    @Test
    fun `existing keys keep their meaning`() {
        assertEquals("ok", Action.TOGGLE_PAUSE, action(Key.DirectionCenter))
        assertEquals("enter", Action.TOGGLE_PAUSE, action(Key.Enter))
        assertEquals("play/pause", Action.TOGGLE_PAUSE, action(Key.MediaPlayPause))
        assertEquals("up", Action.ZAP_PREVIOUS, action(Key.DirectionUp))
        assertEquals("down", Action.ZAP_NEXT, action(Key.DirectionDown))
        assertEquals("menu", Action.HIDE_CHANNEL, action(Key.Menu))
        assertEquals("right just shows the overlay", Action.SHOW_CONTROLS, action(Key.DirectionRight))
    }

    @Test
    fun `play and pause keys only act in their own direction`() {
        assertEquals("play while paused", Action.TOGGLE_PAUSE, action(Key.MediaPlay, paused = true))
        assertEquals("play while playing", Action.SHOW_CONTROLS, action(Key.MediaPlay, paused = false))
        assertEquals("pause while playing", Action.TOGGLE_PAUSE, action(Key.MediaPause, paused = false))
        assertEquals("pause while paused", Action.SHOW_CONTROLS, action(Key.MediaPause, paused = true))
    }

    @Test
    fun `LEFT opens the channel list`() {
        assertEquals("left", Action.OPEN_CHANNEL_LIST, action(Key.DirectionLeft))
    }

    @Test
    fun `the last-channel key jumps back and the channel keys zap`() {
        assertEquals("last channel", Action.ZAP_BACK, action(Key.LastChannel))
        // CH+ follows the remote's number: the next (higher-numbered) channel, i.e. down the list.
        assertEquals("ch+", Action.ZAP_NEXT, action(Key.ChannelUp))
        assertEquals("ch-", Action.ZAP_PREVIOUS, action(Key.ChannelDown))
    }

    @Test
    fun `the KeyUp half of a handled key is consumed so the locked row never clicks`() {
        assertEquals("ok up", Action.CONSUME, action(Key.DirectionCenter, isKeyDown = false))
        assertEquals("left up", Action.CONSUME, action(Key.DirectionLeft, isKeyDown = false))
        assertEquals("last up", Action.CONSUME, action(Key.LastChannel, isKeyDown = false))
    }

    @Test
    fun `while the channel list or a hide notice is up their own keys are left alone`() {
        assertEquals("list ok", Action.PASS, action(Key.DirectionCenter, channelListOpen = true))
        assertEquals("list up", Action.PASS, action(Key.DirectionUp, channelListOpen = true))
        assertEquals("notice ok", Action.PASS, action(Key.DirectionCenter, noticeShowing = true))
    }

    @Test
    fun `Back is never taken here`() {
        // BACK belongs to the BackHandlers: close the list, else leave fullscreen.
        assertEquals("back", Action.PASS, action(Key.Back))
    }

    @Test
    fun `unrelated keys fall through`() {
        assertEquals("volume", Action.PASS, action(Key.VolumeUp))
    }
}
