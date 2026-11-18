package com.nuvio.tv.playback.mediasession

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalPausePolicyTest {
    @Test
    fun `a pause right after our own display mode request is the platform's echo`() {
        assertEquals(
            "AFTKM: HDMI re-sync pauses the session ~1.2 s after the mode request",
            true,
            ExternalPausePolicy.isDisplaySwitchPause(lastModeRequestAtMs = 10_000L, nowMs = 11_250L),
        )
    }

    @Test
    fun `the last millisecond of the window still counts and the next does not`() {
        val last = 10_000L
        assertEquals(
            "inside",
            true,
            ExternalPausePolicy.isDisplaySwitchPause(last, last + ExternalPausePolicy.DISPLAY_SWITCH_WINDOW_MS),
        )
        assertEquals(
            "outside",
            false,
            ExternalPausePolicy.isDisplaySwitchPause(last, last + ExternalPausePolicy.DISPLAY_SWITCH_WINDOW_MS + 1),
        )
    }

    @Test
    fun `a pause long after the switch is the viewer's`() {
        assertEquals(
            "a CEC or voice pause a minute later must pause",
            false,
            ExternalPausePolicy.isDisplaySwitchPause(lastModeRequestAtMs = 10_000L, nowMs = 70_000L),
        )
    }

    @Test
    fun `without any display switch every pause is the viewer's`() {
        assertEquals(
            "no switch, no echo",
            false,
            ExternalPausePolicy.isDisplaySwitchPause(lastModeRequestAtMs = null, nowMs = 5_000L),
        )
    }

    @Test
    fun `a clock that went backwards never swallows a pause`() {
        assertEquals(
            "request in the future of now",
            false,
            ExternalPausePolicy.isDisplaySwitchPause(lastModeRequestAtMs = 20_000L, nowMs = 10_000L),
        )
    }
}
