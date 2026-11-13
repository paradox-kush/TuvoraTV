package com.nuvio.tv.playback.ui

import com.nuvio.tv.R
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackNetworkRequest
import com.nuvio.tv.playback.core.PlaybackProgressEvidence
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy
import com.nuvio.tv.ui.screens.player.clean.CleanLivePlayerUiPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B259: a Stalker link minted fine, the provider's stream server then sent nothing, and the viewer read
 * "Live TV playback failed." (or "unreadable stream format"). With no named cause, say who failed.
 */
class ProviderNoVideoPolicyTest {

    @Test
    fun `minted single-use link that failed at startup with no cause names the provider`() {
        val ui = LivePlaybackUiPresenter.present(failed(FailureCode.UNKNOWN, FailurePhase.PLAYBACK))
        val error = ui.bottomErrorCode as LivePlaybackUiErrorCode.PlaybackFailed
        assertTrue("provider sent no video", error.providerSentNoVideo)
        assertEquals("fixed sentence", R.string.clean_live_error_provider_no_video, CleanLivePlayerUiPolicy.errorMessageRes(error))
    }

    @Test
    fun `unreadable format with zero bytes is the provider sending nothing`() {
        for (code in listOf(FailureCode.DEMUX_FAILED, FailureCode.MANIFEST_INVALID)) {
            assertTrue("$code, no bytes", ProviderNoVideoPolicy.applies(failed(code, FailurePhase.PLAYBACK)))
            assertFalse(
                "$code with bytes is a real format problem",
                ProviderNoVideoPolicy.applies(failed(code, FailurePhase.PLAYBACK, PlaybackProgressEvidence(receivedBytes = true))),
            )
        }
    }

    @Test
    fun `a startup watchdog with nothing rendered names the provider`() {
        assertTrue("no progress", ProviderNoVideoPolicy.applies(failed(FailureCode.NO_PROGRESS, FailurePhase.RECOVERY)))
    }

    @Test
    fun `a named cause still wins`() {
        listOf(
            FailureCode.NETWORK_UNREACHABLE, FailureCode.NETWORK_TIMEOUT, FailureCode.AUTHORIZATION_REJECTED,
            FailureCode.PROVIDER_CONNECTION_LIMIT, FailureCode.SOURCE_NOT_FOUND, FailureCode.VIDEO_DECODER_FAILED,
            FailureCode.TLS_HANDSHAKE_FAILED, FailureCode.LIVE_RECONNECT_EXHAUSTED, FailureCode.NO_ELIGIBLE_GRAPH,
        ).forEach { code ->
            assertFalse("$code", ProviderNoVideoPolicy.applies(failed(code, FailurePhase.PLAYBACK)))
        }
    }

    @Test
    fun `an unknown failure of the mint itself is not a stream that sent nothing`() {
        assertFalse("resolution", ProviderNoVideoPolicy.applies(failed(FailureCode.UNKNOWN, FailurePhase.REQUEST_RESOLUTION)))
        assertFalse("re-mint inside a recovery", ProviderNoVideoPolicy.applies(failed(FailureCode.UNKNOWN, FailurePhase.RECOVERY)))
    }

    @Test
    fun `a stream that rendered something did not fail at startup`() {
        assertFalse("video", ProviderNoVideoPolicy.applies(failed(FailureCode.UNKNOWN, FailurePhase.PLAYBACK, PlaybackProgressEvidence(renderedVideoFrame = true))))
        assertFalse("audio", ProviderNoVideoPolicy.applies(failed(FailureCode.UNKNOWN, FailurePhase.PLAYBACK, PlaybackProgressEvidence(renderedAudio = true))))
    }

    @Test
    fun `only a minted single-use live link qualifies`() {
        assertFalse("Xtream/M3U reusable link", ProviderNoVideoPolicy.applies(failed(FailureCode.UNKNOWN, FailurePhase.PLAYBACK, singleUse = false)))
        assertFalse("no link at all", ProviderNoVideoPolicy.applies(failed(FailureCode.UNKNOWN, FailurePhase.PLAYBACK).copy(requestSummary = null)))
        assertFalse("not failed", ProviderNoVideoPolicy.applies(failed(FailureCode.UNKNOWN, FailurePhase.PLAYBACK).copy(state = PlaybackState.PLAYING)))
    }

    @Test
    fun `other failures keep their existing wording`() {
        val ui = LivePlaybackUiPresenter.present(failed(FailureCode.UNKNOWN, FailurePhase.PLAYBACK, singleUse = false))
        assertEquals("unchanged", LivePlaybackUiErrorCode.PlaybackFailed(FailureCode.UNKNOWN), ui.bottomErrorCode)
        assertEquals(R.string.clean_live_error_unknown, CleanLivePlayerUiPolicy.errorMessageRes(ui.bottomErrorCode!!))
    }

    private fun failed(
        code: FailureCode,
        phase: FailurePhase,
        progress: PlaybackProgressEvidence = PlaybackProgressEvidence(),
        singleUse: Boolean = true,
    ): PlaybackSnapshot {
        val request = PlaybackRequest(
            url = "http://portal.invalid/play/live.php?play_token=t",
            contentType = ContentType.LIVE,
            network = if (singleUse) {
                PlaybackNetworkRequest(retryConnectionFailures = false, transientLoadRetryPolicy = TransientLoadRetryPolicy.SESSION_ONLY)
            } else {
                PlaybackNetworkRequest()
            },
        )
        return PlaybackSnapshot(
            state = PlaybackState.FAILED,
            requestSummary = request.summary(),
            progress = progress,
            failure = PlaybackFailure(code, FailureDomain.UNKNOWN, phase, Retryability.FATAL),
        )
    }
}
