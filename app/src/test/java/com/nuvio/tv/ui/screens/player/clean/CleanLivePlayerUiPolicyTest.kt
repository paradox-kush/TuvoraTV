package com.nuvio.tv.ui.screens.player.clean

import android.view.KeyEvent
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PreviewAvailability
import com.nuvio.tv.playback.core.PreviewUnavailableReason
import com.nuvio.tv.playback.core.StreamAvailability
import com.nuvio.tv.playback.core.StreamUnavailableReason
import com.nuvio.tv.playback.ui.LivePlaybackUiErrorCode
import com.nuvio.tv.playback.ui.LivePlaybackUiState
import com.nuvio.tv.playback.ui.LivePlaybackUiStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanLivePlayerUiPolicyTest {
    @Test
    fun `keep screen on follows only active playback intent and presentation facts`() {
        assertTrue(
            CleanLivePlayerUiPolicy.present(
                state(playWhenReady = true, isPlaying = true),
            ).keepScreenOn,
        )
        assertTrue(
            CleanLivePlayerUiPolicy.present(
                state(playWhenReady = true, spinnerVisible = true),
            ).keepScreenOn,
        )
        assertFalse(
            CleanLivePlayerUiPolicy.present(
                state(playWhenReady = false, isPlaying = false, spinnerVisible = true),
            ).keepScreenOn,
        )
        assertFalse(CleanLivePlayerUiPolicy.present(state()).keepScreenOn)
    }

    @Test
    fun `keep screen on stays true through a live stall so the TV screensaver never fires`() {
        // Regression (reported on 1.7.1): keepScreenOn was gated on the transient isPlaying, so a
        // live stall / re-buffer with no spinner dropped FLAG_KEEP_SCREEN_ON and Android TV entered
        // Ambient Mode (the "screen blacks out during live TV" black-out) after its inactivity
        // timeout. Intent-based keep-awake holds the flag whenever the user is watching, even when
        // isPlaying momentarily reports false with no spinner up.
        assertTrue(
            "an actively-watched channel keeps the screen on during a non-playing, non-spinner stall",
            CleanLivePlayerUiPolicy.present(
                state(playWhenReady = true, isPlaying = false, spinnerVisible = false),
            ).keepScreenOn,
        )
        // And it still correctly releases on pause so a paused channel can sleep.
        assertFalse(
            "a paused channel (no play intent) lets the screen sleep",
            CleanLivePlayerUiPolicy.present(
                state(playWhenReady = false, isPlaying = false),
            ).keepScreenOn,
        )
    }

    @Test
    fun `retry is conservative for playback and preview failure but not terminal stream`() {
        assertTrue(
            CleanLivePlayerUiPolicy.present(
                state(
                    error = LivePlaybackUiErrorCode.PlaybackFailed(FailureCode.NO_PROGRESS),
                ),
            ).retryEnabled,
        )
        assertTrue(
            CleanLivePlayerUiPolicy.present(
                state(
                    error = LivePlaybackUiErrorCode.PreviewUnavailable(
                        PreviewUnavailableReason.PREFERRED_ENGINE_FAILED,
                    ),
                ),
            ).retryEnabled,
        )
        assertFalse(
            CleanLivePlayerUiPolicy.present(
                state(
                    error = LivePlaybackUiErrorCode.StreamUnavailable(
                        StreamUnavailableReason.REMOVED_OR_EXPIRED,
                    ),
                ),
            ).retryEnabled,
        )
    }

    @Test
    fun `error message wins over status without exposing dynamic text`() {
        val chrome = CleanLivePlayerUiPolicy.present(
            state(
                status = LivePlaybackUiStatusCode.RECONNECTING,
                error = LivePlaybackUiErrorCode.PlaybackFailed(FailureCode.VIDEO_DECODER_FAILED),
            ),
        )

        assertEquals(
            CleanLivePlayerUiPolicy.errorMessageRes(
                LivePlaybackUiErrorCode.PlaybackFailed(FailureCode.VIDEO_DECODER_FAILED),
            ),
            chrome.messageRes,
        )
        assertTrue(chrome.messageIsError)
    }

    @Test
    fun `every stable status code has a localizable resource`() {
        LivePlaybackUiStatusCode.entries.forEach { code ->
            assertNotEquals("status $code", 0, CleanLivePlayerUiPolicy.statusMessageRes(code))
        }
    }

    @Test
    fun `every stable preview stream and playback error has a localizable resource`() {
        PreviewUnavailableReason.entries.forEach { reason ->
            assertNotEquals(
                "preview $reason",
                0,
                CleanLivePlayerUiPolicy.errorMessageRes(
                    LivePlaybackUiErrorCode.PreviewUnavailable(reason),
                ),
            )
        }
        StreamUnavailableReason.entries.forEach { reason ->
            assertNotEquals(
                "stream $reason",
                0,
                CleanLivePlayerUiPolicy.errorMessageRes(
                    LivePlaybackUiErrorCode.StreamUnavailable(reason),
                ),
            )
        }
        FailureCode.entries.forEach { reason ->
            assertNotEquals(
                "playback $reason",
                0,
                CleanLivePlayerUiPolicy.errorMessageRes(
                    LivePlaybackUiErrorCode.PlaybackFailed(reason),
                ),
            )
        }
    }

    @Test
    fun `a 458 or 509 failure names the account connection limit while 429 keeps the generic copy`() {
        listOf(458, 509).forEach { status ->
            val ui = com.nuvio.tv.playback.ui.LivePlaybackUiPresenter.present(failedSnapshot(status))
            assertEquals(
                "HTTP $status",
                com.nuvio.tv.R.string.clean_live_error_connection_limit,
                CleanLivePlayerUiPolicy.errorMessageRes(ui.bottomErrorCode!!),
            )
        }
        val rateLimited = com.nuvio.tv.playback.ui.LivePlaybackUiPresenter.present(failedSnapshot(429))
        assertEquals(
            "HTTP 429 keeps its copy",
            com.nuvio.tv.R.string.clean_live_error_access,
            CleanLivePlayerUiPolicy.errorMessageRes(rateLimited.bottomErrorCode!!),
        )
    }

    /** Device pass: a 503 ended as "Couldn't reach the provider. Check your connection…" — the viewer's network was fine. */
    @Test
    fun `a provider 5xx says the provider's server had a problem while a silent network stays unreachable`() {
        listOf(500, 503).forEach { status ->
            val direct = com.nuvio.tv.playback.ui.LivePlaybackUiPresenter.present(failedSnapshot(status))
            assertEquals("HTTP $status", com.nuvio.tv.R.string.clean_live_error_provider_server, CleanLivePlayerUiPolicy.errorMessageRes(direct.bottomErrorCode!!))
            val exhausted = com.nuvio.tv.playback.ui.LivePlaybackUiPresenter.present(
                com.nuvio.tv.playback.core.PlaybackSnapshot(
                    state = com.nuvio.tv.playback.core.PlaybackState.FAILED,
                    failure = com.nuvio.tv.playback.core.PlaybackPolicy().liveReconnectExhaustedFailure(
                        com.nuvio.tv.playback.core.HttpStatusFailures.forStatus(status, com.nuvio.tv.playback.core.FailurePhase.PLAYBACK),
                    ),
                ),
            )
            assertEquals("HTTP $status after reconnects", com.nuvio.tv.R.string.clean_live_error_provider_server, CleanLivePlayerUiPolicy.errorMessageRes(exhausted.bottomErrorCode!!))
        }
        val silent = com.nuvio.tv.playback.ui.LivePlaybackUiPresenter.present(
            com.nuvio.tv.playback.core.PlaybackSnapshot(
                state = com.nuvio.tv.playback.core.PlaybackState.FAILED,
                failure = com.nuvio.tv.playback.core.PlaybackFailure(
                    FailureCode.NETWORK_UNREACHABLE,
                    com.nuvio.tv.playback.core.FailureDomain.NETWORK,
                    com.nuvio.tv.playback.core.FailurePhase.PLAYBACK,
                    com.nuvio.tv.playback.core.Retryability.RETRYABLE_WITH_FRESH_REQUEST,
                ),
            ),
        )
        assertEquals("no status: unreachable", com.nuvio.tv.R.string.clean_live_error_provider_unreachable, CleanLivePlayerUiPolicy.errorMessageRes(silent.bottomErrorCode!!))
    }

    private fun failedSnapshot(status: Int) = com.nuvio.tv.playback.core.PlaybackSnapshot(
        state = com.nuvio.tv.playback.core.PlaybackState.FAILED,
        failure = com.nuvio.tv.playback.core.HttpStatusFailures.forStatus(
            status,
            com.nuvio.tv.playback.core.FailurePhase.PLAYBACK,
        ),
    )

    // F28: the key map and auto-hide rules moved to LiveControlsPolicy (shared with the guide's
    // fullscreen) and are tested in LiveControlsPolicyTest, including the media-key repeat guard.

    private fun state(
        spinnerVisible: Boolean = false,
        status: LivePlaybackUiStatusCode? = null,
        error: LivePlaybackUiErrorCode? = null,
        controlsEnabled: Boolean = false,
        playWhenReady: Boolean = false,
        isPlaying: Boolean = false,
    ) = LivePlaybackUiState(
        spinnerVisible = spinnerVisible,
        bottomStatusCode = status,
        bottomErrorCode = error,
        controlsEnabled = controlsEnabled,
        openFullscreenEnabled = false,
        playWhenReady = playWhenReady,
        isPlaying = isPlaying,
        isPaused = controlsEnabled && !playWhenReady,
        previewAvailability = PreviewAvailability.Unknown,
        streamAvailability = StreamAvailability.Unknown,
    )
}
