package com.nuvio.tv.playback.mediasession

import com.nuvio.tv.playback.mediasession.ExternalPausePolicy.SwitchState
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalPausePolicyTest {
    @Test
    fun `a pause while our display switch is in progress is the platform's echo`() {
        assertEquals("HDMI re-sync pause mid-switch", true,
            ExternalPausePolicy.isDisplaySwitchPause(SwitchState(inProgress = true), nowMs = 5_000L))
    }

    @Test
    fun `a pause just after the switch completed is still the echo, a later one is the viewer's`() {
        val done = SwitchState(completedAtMs = 10_000L)
        assertEquals("inside", true,
            ExternalPausePolicy.isDisplaySwitchPause(done, 10_000L + ExternalPausePolicy.BACKSTOP_WINDOW_MS))
        assertEquals("outside", false,
            ExternalPausePolicy.isDisplaySwitchPause(done, 10_000L + ExternalPausePolicy.BACKSTOP_WINDOW_MS + 1))
    }

    @Test
    fun `the backstop window is a few seconds, not the old eight`() {
        assertEquals("3-4 s", true, ExternalPausePolicy.BACKSTOP_WINDOW_MS in 3_000L..4_000L)
    }

    @Test
    fun `without any display switch every pause is the viewer's`() {
        assertEquals("no switch", false, ExternalPausePolicy.isDisplaySwitchPause(SwitchState(), nowMs = 5_000L))
    }

    @Test
    fun `a clock that went backwards never swallows a pause`() {
        assertEquals("completion in the future", false,
            ExternalPausePolicy.isDisplaySwitchPause(SwitchState(completedAtMs = 20_000L), nowMs = 10_000L))
    }

    @Test
    fun `a removed headphone or bluetooth output always pauses, even mid switch`() {
        val state = SwitchState(inProgress = true, completedAtMs = 9_000L, nonHdmiOutputRemovedAtMs = 9_900L)
        assertEquals("real becoming-noisy", false, ExternalPausePolicy.isDisplaySwitchPause(state, nowMs = 10_000L))
    }

    @Test
    fun `an old non-hdmi removal does not disable the backstop`() {
        val state = SwitchState(inProgress = true, nonHdmiOutputRemovedAtMs = 1_000L)
        assertEquals("removal long ago", true, ExternalPausePolicy.isDisplaySwitchPause(state, nowMs = 60_000L))
    }

    @Test
    fun `hdmi sink types are the display's own, others are not`() {
        assertEquals(true, ExternalPausePolicy.isHdmiOutput(9))
        assertEquals(true, ExternalPausePolicy.isHdmiOutput(10))
        assertEquals(true, ExternalPausePolicy.isHdmiOutput(29))
        assertEquals("bluetooth a2dp = 8", false, ExternalPausePolicy.isHdmiOutput(8))
        assertEquals("wired headphones = 4", false, ExternalPausePolicy.isHdmiOutput(4))
    }
}
