package com.nuvio.tv.ui.screens.player.clean.live

import androidx.compose.ui.input.key.Key
import com.nuvio.tv.core.contracts.LivePausePhase
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.KeyAction
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.ScrubDirection
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy.Status
import org.junit.Assert.assertEquals
import org.junit.Test

/** Live pause / rewind on the live overlay: scrub keys, bounds, the offset label and the badge. */
class LiveControlsPolicyShiftTest {
    private fun key(
        key: Key,
        down: Boolean = true,
        visible: Boolean = false,
        scrubbable: Boolean = true,
        lineup: Boolean = false,
    ) = LiveControlsPolicy.keyAction(key, down, visible, paused = true, panelOpen = false, hasLineup = lineup, scrubbable = scrubbable)

    @Test
    fun `LEFT and RIGHT scrub while time-shifted with the controls hidden`() {
        assertEquals("left", KeyAction.SCRUB_BACK, key(Key.DirectionLeft))
        assertEquals("right", KeyAction.SCRUB_FORWARD, key(Key.DirectionRight))
        assertEquals("key up is only consumed", KeyAction.CONSUME, key(Key.DirectionLeft, down = false))
    }

    @Test
    fun `scrub wins over the guide's channel list on LEFT only while shifted`() {
        assertEquals("shifted", KeyAction.SCRUB_BACK, key(Key.DirectionLeft, lineup = true))
        assertEquals("live", KeyAction.OPEN_CHANNEL_LIST, key(Key.DirectionLeft, scrubbable = false, lineup = true))
    }

    @Test
    fun `with the controls up the scrub bar owns LEFT and RIGHT itself`() {
        assertEquals(KeyAction.PASS_AND_KEEP_ALIVE, key(Key.DirectionLeft, visible = true))
    }

    @Test
    fun `not shifted LEFT only shows the controls, as before`() {
        assertEquals(KeyAction.SHOW_CONTROLS, key(Key.DirectionLeft, scrubbable = false))
    }

    @Test
    fun `a held key scrubs faster`() {
        assertEquals(10_000L, LiveControlsPolicy.scrubStepMs(0))
        assertEquals(30_000L, LiveControlsPolicy.scrubStepMs(3))
        assertEquals(60_000L, LiveControlsPolicy.scrubStepMs(8))
    }

    @Test
    fun `a scrub never leaves the saved window`() {
        assertEquals("back", 0L, LiveControlsPolicy.scrubTarget(4_000, 0, 60_000, ScrubDirection.BACK, 10_000))
        assertEquals("forward", 60_000L, LiveControlsPolicy.scrubTarget(55_000, 0, 60_000, ScrubDirection.FORWARD, 10_000))
        assertEquals("inside", 25_000L, LiveControlsPolicy.scrubTarget(35_000, 0, 60_000, ScrubDirection.BACK, 10_000))
        assertEquals("a moving window start", 20_000L, LiveControlsPolicy.scrubTarget(25_000, 20_000, 80_000, ScrubDirection.BACK, 10_000))
        assertEquals("an empty window", 5L, LiveControlsPolicy.scrubTarget(5, 5, 5, ScrubDirection.FORWARD, 10_000))
    }

    @Test
    fun `how far behind live reads as minutes and seconds`() {
        assertEquals("−00:00", LiveControlsPolicy.behindLabel(0))
        assertEquals("−03:12", LiveControlsPolicy.behindLabel(192_000))
        assertEquals("rounds to the nearest second", "−00:02", LiveControlsPolicy.behindLabel(1_600))
        assertEquals("past an hour", "−1:03:12", LiveControlsPolicy.behindLabel(3_792_000))
        assertEquals("never negative", "−00:00", LiveControlsPolicy.behindLabel(-5))
    }

    @Test
    fun `the badge follows live pause while it is in play`() {
        assertEquals(Status.TUNING, LiveControlsPolicy.withLivePause(Status.TUNING, LivePausePhase.OFF, paused = false))
        assertEquals(Status.PREPARING, LiveControlsPolicy.withLivePause(Status.PAUSED, LivePausePhase.PREPARING, paused = true))
        assertEquals("shifted and paused", Status.PAUSED, LiveControlsPolicy.withLivePause(Status.LIVE, LivePausePhase.SHIFTED, paused = true))
        assertEquals("shifted and playing is not LIVE", Status.SHIFTED, LiveControlsPolicy.withLivePause(Status.LIVE, LivePausePhase.SHIFTED, paused = false))
        assertEquals("failed pause", Status.PAUSED, LiveControlsPolicy.withLivePause(Status.LIVE, LivePausePhase.FAILED, paused = true))
    }
}
