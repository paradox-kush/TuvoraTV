package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.ui.input.key.Key
import com.nuvio.tv.core.contracts.LivePausePhase
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.ChipLabel
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's fullscreen player and the clean live player share one overlay; these are the decisions
 * both screens ask of it for live pause / rewind (the guide used to ignore all of them).
 */
class LiveControlsPolicyGuideWiringTest {

    @Test
    fun `only a shifted stream is scrubbable`() {
        assertTrue("shifted", LiveControlsPolicy.isScrubbable(LivePausePhase.SHIFTED))
        assertFalse("preparing", LiveControlsPolicy.isScrubbable(LivePausePhase.PREPARING))
        assertFalse("failed", LiveControlsPolicy.isScrubbable(LivePausePhase.FAILED))
        assertFalse("off", LiveControlsPolicy.isScrubbable(LivePausePhase.OFF))
    }

    @Test
    fun `LEFT and RIGHT scrub with the controls hidden when shifted`() {
        val back = LiveControlsPolicy.keyAction(
            Key.DirectionLeft, true, controlsVisible = false, paused = true, panelOpen = false,
            hasLineup = true, scrubbable = LiveControlsPolicy.isScrubbable(LivePausePhase.SHIFTED),
        )
        val forward = LiveControlsPolicy.keyAction(
            Key.DirectionRight, true, controlsVisible = false, paused = true, panelOpen = false,
            hasLineup = true, scrubbable = true,
        )
        assertEquals("left scrubs back even where LEFT opens the channel list live", KeyAction.SCRUB_BACK, back)
        assertEquals("right scrubs forward", KeyAction.SCRUB_FORWARD, forward)
    }

    @Test
    fun `the chip names the offset from the moment a shift begins`() {
        assertEquals("preparing", ChipLabel.BEHIND, LiveControlsPolicy.chipLabel(LivePausePhase.PREPARING))
        assertEquals("shifted", ChipLabel.BEHIND, LiveControlsPolicy.chipLabel(LivePausePhase.SHIFTED))
        assertEquals("failed offers the way back", ChipLabel.GO_LIVE, LiveControlsPolicy.chipLabel(LivePausePhase.FAILED))
        assertNull("off has no chip", LiveControlsPolicy.chipLabel(LivePausePhase.OFF))
    }

    @Test
    fun `the saved part decides paused while it is in play`() {
        assertTrue("shifted paused", LiveControlsPolicy.effectivePaused(LivePausePhase.SHIFTED, controlPaused = true, hostPaused = false))
        assertFalse("shifted playing though the host reads paused", LiveControlsPolicy.effectivePaused(LivePausePhase.SHIFTED, controlPaused = false, hostPaused = true))
        assertTrue("off follows the host", LiveControlsPolicy.effectivePaused(LivePausePhase.OFF, controlPaused = false, hostPaused = true))
        assertFalse("off follows the host (playing)", LiveControlsPolicy.effectivePaused(LivePausePhase.OFF, controlPaused = true, hostPaused = false))
    }
}
