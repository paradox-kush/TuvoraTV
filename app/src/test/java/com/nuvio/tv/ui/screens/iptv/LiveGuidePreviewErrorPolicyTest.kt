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
}
