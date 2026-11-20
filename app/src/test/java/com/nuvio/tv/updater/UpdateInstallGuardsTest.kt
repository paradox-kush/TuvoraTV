package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B274: Install gave no feedback for seconds on a loaded box; the background download ignored the player. */
class UpdateInstallGuardsTest {

    @Test
    fun `first install press starts, a repeat while installing is ignored`() {
        assertTrue("first press", InstallPressPolicy.shouldStart(isInstalling = false))
        assertFalse("repeat press", InstallPressPolicy.shouldStart(isInstalling = true))
    }

    @Test
    fun `busy hold outlasts a slow system installer`() {
        assertTrue("at least ten seconds", InstallPressPolicy.BUSY_HOLD_MS >= 10_000L)
    }

    @Test
    fun `background download waits while a player is on screen`() {
        assertTrue("player on", BackgroundDownloadGatePolicy.shouldDefer(playerOnScreen = true))
        assertFalse("player off", BackgroundDownloadGatePolicy.shouldDefer(playerOnScreen = false))
    }

    @Test
    fun `the gate flow releases the job when the player goes away`() {
        ImmersivePlaybackGate.resetForTest()
        ImmersivePlaybackGate.setImmersive(true)
        assertTrue("held", BackgroundDownloadGatePolicy.shouldDefer(ImmersivePlaybackGate.isActive.value))
        ImmersivePlaybackGate.setImmersive(false)
        assertEquals("released", false, BackgroundDownloadGatePolicy.shouldDefer(ImmersivePlaybackGate.isActive.value))
        ImmersivePlaybackGate.resetForTest()
    }
}
