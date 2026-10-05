package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.ui.input.key.Key
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.BackAction
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.KeyAction
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F28: the owner's live-controls rules (2026-10-03), one test per rule. The live overlay used to map
 * OK to play/pause (a zap could pause by accident), had no Back sequence, and kept a 4 s auto-hide
 * that VOD (3 s) did not share.
 */
class LiveControlsPolicyTest {

    private fun key(
        key: Key,
        down: Boolean = true,
        visible: Boolean = false,
        paused: Boolean = false,
        panel: Boolean = false,
    ) = LiveControlsPolicy.keyAction(key, down, visible, paused, panel)

    @Test
    fun `OK with the controls hidden shows them and never pauses`() {
        assertEquals("ok", KeyAction.SHOW_CONTROLS, key(Key.DirectionCenter))
        assertEquals("enter", KeyAction.SHOW_CONTROLS, key(Key.Enter))
    }

    @Test
    fun `UP and DOWN with the controls hidden change channel`() {
        assertEquals("up", KeyAction.ZAP_PREVIOUS, key(Key.DirectionUp))
        assertEquals("down", KeyAction.ZAP_NEXT, key(Key.DirectionDown))
    }

    @Test
    fun `LEFT and RIGHT reveal the controls and never scrub`() {
        assertEquals("left", KeyAction.SHOW_CONTROLS, key(Key.DirectionLeft))
        assertEquals("right", KeyAction.SHOW_CONTROLS, key(Key.DirectionRight))
    }

    @Test
    fun `with the controls up the D-pad belongs to the buttons and keeps them up`() {
        for (k in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter)) {
            assertEquals("$k", KeyAction.PASS_AND_KEEP_ALIVE, key(k, visible = true))
        }
    }

    @Test
    fun `media and channel keys work whether or not the controls are up`() {
        for (visible in listOf(false, true)) {
            assertEquals("play/pause $visible", KeyAction.TOGGLE_PAUSE, key(Key.MediaPlayPause, visible = visible))
            assertEquals("ch+ $visible", KeyAction.ZAP_NEXT, key(Key.ChannelUp, visible = visible))
            assertEquals("ch- $visible", KeyAction.ZAP_PREVIOUS, key(Key.ChannelDown, visible = visible))
            assertEquals("last $visible", KeyAction.ZAP_BACK, key(Key.LastChannel, visible = visible))
        }
        assertEquals("play while playing just shows", KeyAction.SHOW_CONTROLS, key(Key.MediaPlay))
        assertEquals("play while paused resumes", KeyAction.TOGGLE_PAUSE, key(Key.MediaPlay, paused = true))
    }

    @Test
    fun `a held media key does not toggle pause on every repeat`() {
        assertEquals("repeat", KeyAction.CONSUME, LiveControlsPolicy.keyAction(Key.MediaPlayPause, true, false, false, false, isRepeat = true))
    }

    @Test
    fun `MENU hides the channel, the KeyUp halves are consumed`() {
        assertEquals("menu", KeyAction.HIDE_CHANNEL, key(Key.Menu))
        assertEquals("ok up", KeyAction.CONSUME, key(Key.DirectionCenter, down = false))
        assertEquals("menu up", KeyAction.CONSUME, key(Key.Menu, down = false))
    }

    @Test
    fun `an open panel keeps its own keys`() {
        assertEquals("list ok", KeyAction.PASS, key(Key.DirectionCenter, panel = true))
        assertEquals("list up", KeyAction.PASS, key(Key.DirectionUp, panel = true))
        assertEquals("unrelated", KeyAction.PASS, key(Key.VolumeUp))
    }

    @Test
    fun `Back closes a panel, then hides the controls, then leaves`() {
        assertEquals("panel", BackAction.CLOSE_PANEL, LiveControlsPolicy.backAction(panelOpen = true, controlsVisible = true))
        assertEquals("controls", BackAction.HIDE_CONTROLS, LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = true))
        assertEquals("bare video", BackAction.EXIT, LiveControlsPolicy.backAction(panelOpen = false, controlsVisible = false))
    }

    @Test
    fun `auto-hide never while paused, on an error or with a panel open`() {
        assertTrue("playing", LiveControlsPolicy.mayAutoHide(controlsVisible = true, paused = false, failed = false, panelOpen = false))
        assertFalse("paused", LiveControlsPolicy.mayAutoHide(true, paused = true, failed = false, panelOpen = false))
        assertFalse("error", LiveControlsPolicy.mayAutoHide(true, paused = false, failed = true, panelOpen = false))
        assertFalse("panel", LiveControlsPolicy.mayAutoHide(true, paused = false, failed = false, panelOpen = true))
    }

    @Test
    fun `the badge says tuning first, then failed, reconnecting, paused, live`() {
        assertEquals(Status.TUNING, LiveControlsPolicy.status(tuning = true, reconnecting = true, paused = true, failed = true))
        assertEquals(Status.FAILED, LiveControlsPolicy.status(tuning = false, reconnecting = true, paused = true, failed = true))
        assertEquals(Status.RECONNECTING, LiveControlsPolicy.status(tuning = false, reconnecting = true, paused = true, failed = false))
        assertEquals(Status.PAUSED, LiveControlsPolicy.status(tuning = false, reconnecting = false, paused = true, failed = false))
        assertEquals(Status.LIVE, LiveControlsPolicy.status(tuning = false, reconnecting = false, paused = false, failed = false))
    }

    @Test
    fun `the programme line is the elapsed share, clamped, and absent when unknown`() {
        assertEquals(0.25f, LiveControlsPolicy.programmeProgress(1_000, 5_000, 2_000)!!, 0.0001f)
        assertEquals("before start", 0f, LiveControlsPolicy.programmeProgress(1_000, 5_000, 0)!!, 0f)
        assertEquals("after end", 1f, LiveControlsPolicy.programmeProgress(1_000, 5_000, 9_000)!!, 0f)
        assertNull("no programme", LiveControlsPolicy.programmeProgress(null, null, 2_000))
        assertNull("degenerate row", LiveControlsPolicy.programmeProgress(5_000, 5_000, 5_000))
    }

    @Test
    fun `retry only on an error, audio only when there is a choice, list only with a lineup`() {
        val healthy = LiveControlsPolicy.buttons(failed = false, subtitleTracks = 0, audioTracks = 1, hasLineup = true)
        assertFalse("no retry while healthy", healthy.retry)
        assertFalse("no CC without subtitles", healthy.subtitles)
        assertFalse("one audio track is no choice", healthy.audio)
        assertTrue("guide has a channel list", healthy.channelList)
        val failedOneOff = LiveControlsPolicy.buttons(failed = true, subtitleTracks = 2, audioTracks = 2, hasLineup = false)
        assertTrue("retry on error", failedOneOff.retry)
        assertTrue("CC", failedOneOff.subtitles)
        assertTrue("audio choice", failedOneOff.audio)
        assertFalse("a one-off stream has no list", failedOneOff.channelList)
        assertFalse("nor a favourite", failedOneOff.favourite)
    }

    /** T10 (W2 device pass): a failed tune left fullscreen black with no message until OK. */
    @Test
    fun `trouble reveals the hidden controls by itself`() {
        assertEquals("failed, hidden -> reveal", true, LiveControlsPolicy.revealForTrouble(failed = true, reconnecting = false, controlsVisible = false, panelOpen = false))
        assertEquals("reconnecting, hidden -> reveal", true, LiveControlsPolicy.revealForTrouble(failed = false, reconnecting = true, controlsVisible = false, panelOpen = false))
        assertEquals("already shown -> nothing", false, LiveControlsPolicy.revealForTrouble(failed = true, reconnecting = false, controlsVisible = true, panelOpen = false))
        assertEquals("a panel keeps its focus", false, LiveControlsPolicy.revealForTrouble(failed = true, reconnecting = false, controlsVisible = false, panelOpen = true))
        assertEquals("healthy -> nothing", false, LiveControlsPolicy.revealForTrouble(failed = false, reconnecting = false, controlsVisible = false, panelOpen = false))
    }
}
