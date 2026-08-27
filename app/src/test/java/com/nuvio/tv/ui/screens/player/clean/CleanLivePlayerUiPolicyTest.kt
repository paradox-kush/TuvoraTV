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
    fun `enabled up and down dispatch zap while disabled keys remain unconsumed`() {
        val enabled = state(controlsEnabled = true)
        assertEquals(
            CleanLiveRemoteAction.ZAP_PREVIOUS,
            action(KeyEvent.KEYCODE_DPAD_UP, enabled),
        )
        assertEquals(
            CleanLiveRemoteAction.ZAP_NEXT,
            action(KeyEvent.KEYCODE_DPAD_DOWN, enabled),
        )
        assertNull(action(KeyEvent.KEYCODE_DPAD_UP, state(controlsEnabled = false)))
        assertNull(action(KeyEvent.KEYCODE_DPAD_DOWN, state(controlsEnabled = false)))
        assertNull(
            action(
                keyCode = KeyEvent.KEYCODE_DPAD_UP,
                uiState = enabled,
                keyAction = KeyEvent.ACTION_UP,
            ),
        )
    }

    @Test
    fun `media keys use authoritative playback intent without duplicate repeat toggles`() {
        assertEquals(
            CleanLiveRemoteAction.PAUSE,
            action(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                state(controlsEnabled = true, playWhenReady = true),
            ),
        )
        assertEquals(
            CleanLiveRemoteAction.RESUME,
            action(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                state(controlsEnabled = true, playWhenReady = false),
            ),
        )
        assertEquals(
            CleanLiveRemoteAction.RESUME,
            action(
                KeyEvent.KEYCODE_MEDIA_PLAY,
                state(controlsEnabled = true, playWhenReady = false),
            ),
        )
        assertNull(
            action(
                KeyEvent.KEYCODE_MEDIA_PLAY,
                state(controlsEnabled = true, playWhenReady = true),
            ),
        )
        assertNull(
            action(
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                uiState = state(controlsEnabled = true),
                repeatCount = 1,
            ),
        )
    }

    private fun action(
        keyCode: Int,
        uiState: LivePlaybackUiState,
        keyAction: Int = KeyEvent.ACTION_DOWN,
        repeatCount: Int = 0,
    ) = CleanLivePlayerUiPolicy.remoteAction(
        keyCode = keyCode,
        keyAction = keyAction,
        repeatCount = repeatCount,
        uiState = uiState,
    )

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
