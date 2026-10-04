package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

// Sync-12 device pass (2026-10-04, Onn): a channel that failed on both engines showed, in
// fullscreen, the previous channel's held frame under the new name with status "Live" and no error
// (the error only reached the docked preview).
class LiveOverlayStatusPolicyTest {

    @Test
    fun `a failed tune reads as failed and not live`() {
        assertEquals(
            "failed, settled on the channel",
            LiveOverlayStatus.FAILED,
            LiveOverlayStatusPolicy.status(tuning = false, paused = false, failed = true),
        )
        assertEquals(
            "failed outranks paused",
            LiveOverlayStatus.FAILED,
            LiveOverlayStatusPolicy.status(tuning = false, paused = true, failed = true),
        )
    }

    @Test
    fun `tuning outranks a failure that belongs to the previous channel`() {
        assertEquals(
            "zap still walking ahead",
            LiveOverlayStatus.TUNING,
            LiveOverlayStatusPolicy.status(tuning = true, paused = false, failed = true),
        )
    }

    @Test
    fun `healthy playback keeps the existing labels`() {
        assertEquals("live", LiveOverlayStatus.LIVE, LiveOverlayStatusPolicy.status(tuning = false, paused = false, failed = false))
        assertEquals("paused", LiveOverlayStatus.PAUSED, LiveOverlayStatusPolicy.status(tuning = false, paused = true, failed = false))
        assertEquals("tuning", LiveOverlayStatus.TUNING, LiveOverlayStatusPolicy.status(tuning = true, paused = true, failed = false))
    }
}
