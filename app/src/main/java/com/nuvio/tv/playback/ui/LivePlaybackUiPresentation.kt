package com.nuvio.tv.playback.ui

import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.PreviewAvailability
import com.nuvio.tv.playback.core.PreviewUnavailableReason
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.StreamAvailability
import com.nuvio.tv.playback.core.StreamUnavailableReason

/** Stable, localizable bottom-of-player status codes. No user/provider text crosses this contract. */
enum class LivePlaybackUiStatusCode {
    RESOLVING,
    STARTING,
    BUFFERING,
    RECOVERING,
    HANDING_OFF,
    RECONNECTING,
    PAUSED,
    RELEASING,
    STOPPED,
}

/** Stable error code families; every payload is an engine-neutral enum from the clean core. */
sealed interface LivePlaybackUiErrorCode {
    data class PreviewUnavailable(val reasonCode: PreviewUnavailableReason) : LivePlaybackUiErrorCode
    data class StreamUnavailable(val reasonCode: StreamUnavailableReason) : LivePlaybackUiErrorCode
    data class PlaybackFailed(val reasonCode: FailureCode) : LivePlaybackUiErrorCode
}

/**
 * Compact, secret-free presentation state for the future guide/fullscreen live host.
 *
 * Text and icons are intentionally absent: the Compose host localizes these stable codes. Preview
 * and stream availability remain separate because a guide render failure does not prove that the
 * provider stream is unavailable in fullscreen.
 */
data class LivePlaybackUiState(
    val spinnerVisible: Boolean,
    val bottomStatusCode: LivePlaybackUiStatusCode?,
    val bottomErrorCode: LivePlaybackUiErrorCode?,
    val controlsEnabled: Boolean,
    val openFullscreenEnabled: Boolean,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val isPaused: Boolean,
    val previewAvailability: PreviewAvailability,
    val streamAvailability: StreamAvailability,
)

/** Pure projection only. Recovery, retry, engine selection, and provider resolution stay upstream. */
object LivePlaybackUiPresenter {
    fun present(snapshot: PlaybackSnapshot): LivePlaybackUiState {
        val terminalStream = snapshot.streamAvailability as? StreamAvailability.TerminallyUnavailable
        val recoveryInProgress = snapshot.state in RECOVERY_STATES
        val error = when {
            terminalStream != null -> LivePlaybackUiErrorCode.StreamUnavailable(terminalStream.reason)
            recoveryInProgress -> null
            snapshot.profile == SessionProfile.GUIDE &&
                snapshot.previewAvailability is PreviewAvailability.Unavailable ->
                LivePlaybackUiErrorCode.PreviewUnavailable(snapshot.previewAvailability.reason)
            snapshot.state == PlaybackState.FAILED ->
                LivePlaybackUiErrorCode.PlaybackFailed(snapshot.failure?.code ?: FailureCode.UNKNOWN)
            else -> null
        }
        val active = snapshot.state in CONTROL_STATES && terminalStream == null
        val status = if (error != null) null else snapshot.bottomStatusCode()
        return LivePlaybackUiState(
            spinnerVisible = error == null && snapshot.shouldShowSpinner(),
            bottomStatusCode = status,
            bottomErrorCode = error,
            controlsEnabled = active,
            openFullscreenEnabled = snapshot.canOpenFullscreen(terminalStream),
            playWhenReady = snapshot.playWhenReady,
            isPlaying = snapshot.isPlaying,
            isPaused = active && !snapshot.playWhenReady,
            previewAvailability = snapshot.previewAvailability,
            streamAvailability = snapshot.streamAvailability,
        )
    }

    private fun PlaybackSnapshot.canOpenFullscreen(
        terminalStream: StreamAvailability.TerminallyUnavailable?,
    ): Boolean =
        profile == SessionProfile.GUIDE &&
            requestSummary != null &&
            terminalStream == null &&
            state !in FULLSCREEN_ACTION_DISABLED_STATES

    private fun PlaybackSnapshot.shouldShowSpinner(): Boolean = when (state) {
        PlaybackState.RESOLVING,
        PlaybackState.SELECTING_GRAPH,
        PlaybackState.ATTACHING_SURFACE,
        PlaybackState.STARTING_PRIMARY,
        PlaybackState.RECOVERING_IN_PLACE,
        PlaybackState.HANDING_OFF_ONCE,
        PlaybackState.LIVE_RECONNECTING,
        -> true

        PlaybackState.DEGRADED,
        PlaybackState.PLAYING,
        -> isBuffering

        PlaybackState.IDLE,
        PlaybackState.RELEASING,
        PlaybackState.STOPPED,
        PlaybackState.FAILED,
        -> false
    }

    private fun PlaybackSnapshot.bottomStatusCode(): LivePlaybackUiStatusCode? = when (state) {
        PlaybackState.RESOLVING -> LivePlaybackUiStatusCode.RESOLVING
        PlaybackState.SELECTING_GRAPH,
        PlaybackState.ATTACHING_SURFACE,
        PlaybackState.STARTING_PRIMARY,
        -> LivePlaybackUiStatusCode.STARTING
        PlaybackState.RECOVERING_IN_PLACE -> LivePlaybackUiStatusCode.RECOVERING
        PlaybackState.HANDING_OFF_ONCE -> LivePlaybackUiStatusCode.HANDING_OFF
        PlaybackState.LIVE_RECONNECTING -> LivePlaybackUiStatusCode.RECONNECTING
        PlaybackState.PLAYING,
        PlaybackState.DEGRADED,
        -> when {
            isBuffering -> LivePlaybackUiStatusCode.BUFFERING
            !playWhenReady -> LivePlaybackUiStatusCode.PAUSED
            else -> null
        }
        PlaybackState.RELEASING -> LivePlaybackUiStatusCode.RELEASING
        PlaybackState.STOPPED -> LivePlaybackUiStatusCode.STOPPED
        PlaybackState.IDLE,
        PlaybackState.FAILED,
        -> null
    }

    private val RECOVERY_STATES = setOf(
        PlaybackState.RESOLVING,
        PlaybackState.SELECTING_GRAPH,
        PlaybackState.ATTACHING_SURFACE,
        PlaybackState.STARTING_PRIMARY,
        PlaybackState.RECOVERING_IN_PLACE,
        PlaybackState.HANDING_OFF_ONCE,
        PlaybackState.LIVE_RECONNECTING,
    )

    private val CONTROL_STATES = RECOVERY_STATES + setOf(
        PlaybackState.PLAYING,
        PlaybackState.DEGRADED,
    )

    private val FULLSCREEN_ACTION_DISABLED_STATES = setOf(
        PlaybackState.IDLE,
        PlaybackState.RELEASING,
        PlaybackState.STOPPED,
    )
}
