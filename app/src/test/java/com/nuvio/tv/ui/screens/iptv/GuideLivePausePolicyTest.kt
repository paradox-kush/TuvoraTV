package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.contracts.LivePausePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideLivePausePolicyTest {

    @Test
    fun `leaving fullscreen while time shifted returns the preview to live`() {
        assertTrue("shifted", GuideLivePausePolicy.goLiveOnCollapse(LivePausePhase.SHIFTED))
        assertTrue("preparing", GuideLivePausePolicy.goLiveOnCollapse(LivePausePhase.PREPARING))
        assertTrue("failed", GuideLivePausePolicy.goLiveOnCollapse(LivePausePhase.FAILED))
    }

    @Test
    fun `leaving fullscreen at live changes nothing`() {
        assertFalse("off", GuideLivePausePolicy.goLiveOnCollapse(LivePausePhase.OFF))
    }

    @Test
    fun `Retry belongs to live pause only when it failed`() {
        assertTrue("failed", GuideLivePausePolicy.retryGoesToPause(LivePausePhase.FAILED))
        assertFalse("off retries the stream", GuideLivePausePolicy.retryGoesToPause(LivePausePhase.OFF))
        assertFalse("shifted retries the stream", GuideLivePausePolicy.retryGoesToPause(LivePausePhase.SHIFTED))
    }
}
