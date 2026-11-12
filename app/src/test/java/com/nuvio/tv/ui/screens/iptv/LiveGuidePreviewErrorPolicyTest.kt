package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.R
import com.nuvio.tv.playback.core.PreviewUnavailableReason
import com.nuvio.tv.playback.ui.LivePlaybackUiErrorCode
import com.nuvio.tv.ui.screens.player.clean.CleanLivePlayerUiPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveGuidePreviewErrorPolicyTest {

    @Test
    fun `a preview error shows the full player's translated sentence`() {
        // Regression (B50, 2026-09-27): the guide printed the raw error object's toString().
        val error = LivePlaybackUiErrorCode.PreviewUnavailable(PreviewUnavailableReason.GUIDE_RENDER_PATH_UNAVAILABLE)
        assertEquals(
            "same string resource as the full player",
            CleanLivePlayerUiPolicy.errorMessageRes(error),
            LiveGuidePreviewErrorPolicy.messageRes(CleanLiveGuidePlaybackState.Initializing, error),
        )
        assertEquals("preview path sentence", R.string.clean_live_error_preview_path,
            LiveGuidePreviewErrorPolicy.messageRes(CleanLiveGuidePlaybackState.Initializing, error))
    }

    @Test
    fun `a rejected tune shows a sentence, never the enum name`() {
        for (reason in CleanLiveGuideFailure.entries) {
            val res = LiveGuidePreviewErrorPolicy.messageRes(CleanLiveGuidePlaybackState.Rejected(reason), null)
            assertEquals("$reason maps to its sentence", LiveGuidePreviewErrorPolicy.rejectionMessageRes(reason), res)
        }
        assertEquals("release", R.string.clean_live_error_release,
            LiveGuidePreviewErrorPolicy.rejectionMessageRes(CleanLiveGuideFailure.RELEASE_FAILED))
    }

    @Test
    fun `no error and no rejection shows nothing`() {
        assertNull(LiveGuidePreviewErrorPolicy.messageRes(CleanLiveGuidePlaybackState.Detached, null))
    }

    /** B256: a wait past the outcome backstop reads as failed, never as a silent black picture. */
    @Test
    fun `a stalled wait shows the no-progress sentence`() {
        assertEquals("stalled", R.string.clean_live_error_no_progress,
            LiveGuidePreviewErrorPolicy.messageRes(CleanLiveGuidePlaybackState.Initializing, null, stalled = true))
        val real = LivePlaybackUiErrorCode.PlaybackFailed(com.nuvio.tv.playback.core.FailureCode.SOURCE_NOT_FOUND)
        assertEquals("a real failure keeps its own reason", R.string.clean_live_error_stream_expired,
            LiveGuidePreviewErrorPolicy.messageRes(CleanLiveGuidePlaybackState.Initializing, real, stalled = true))
    }

    /** UX340 (2026-11-12): an unreachable provider read "Live TV playback failed." */
    @Test
    fun `an unreachable provider says so and asks to check the connection`() {
        for (code in listOf(com.nuvio.tv.playback.core.FailureCode.NETWORK_UNREACHABLE, com.nuvio.tv.playback.core.FailureCode.NETWORK_TIMEOUT)) {
            assertEquals("$code", R.string.clean_live_error_provider_unreachable,
                CleanLivePlayerUiPolicy.errorMessageRes(LivePlaybackUiErrorCode.PlaybackFailed(code)))
        }
        assertEquals("a spent reconnect budget keeps its sentence", R.string.clean_live_error_network,
            CleanLivePlayerUiPolicy.errorMessageRes(
                LivePlaybackUiErrorCode.PlaybackFailed(com.nuvio.tv.playback.core.FailureCode.LIVE_RECONNECT_EXHAUSTED)))
    }
}
