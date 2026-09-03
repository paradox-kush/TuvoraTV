@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player.clean

import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PreviewUnavailableReason
import com.nuvio.tv.playback.core.StreamUnavailableReason
import com.nuvio.tv.playback.ui.LivePlaybackUiErrorCode
import com.nuvio.tv.playback.ui.LivePlaybackUiState
import com.nuvio.tv.playback.ui.LivePlaybackUiStatusCode
import com.nuvio.tv.updater.ImmersivePlaybackGate
import kotlinx.coroutines.delay

/** Idle delay before the overlay chrome fades away over live video, matching the live guide's 4s. */
private const val CLEAN_LIVE_CONTROLS_AUTO_HIDE_MS = 4_000L

/**
 * Engine-neutral fullscreen live UI. The future route owns session construction and release.
 * [onExitRequested] must release its host before removing this screen from composition.
 */
@Composable
internal fun CleanLivePlayerScreen(
    sanitizedTitle: String,
    sanitizedSubtitle: String?,
    sanitizedStation: String?,
    uiState: LivePlaybackUiState,
    onSurfaceOwnerReady: (FrameLayout) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onZapPrevious: () -> Unit,
    onZapNext: () -> Unit,
    onExitRequested: () -> Unit,
) {
    val chrome = CleanLivePlayerUiPolicy.present(uiState)
    val latestSurfaceOwnerReady by rememberUpdatedState(onSurfaceOwnerReady)

    // The overlay chrome starts shown so the viewer sees what's playing, then auto-hides over the
    // video — the behaviour the live guide has always had but the clean player was missing, which
    // left the controls stuck on screen after launching from the Sports feed. Any remote key
    // reveals it again and re-arms the idle timer.
    var controlsVisible by remember { mutableStateOf(true) }
    var revealTick by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }

    LaunchedEffect(revealTick, controlsVisible, uiState.playWhenReady, chrome.retryEnabled) {
        if (controlsVisible && CleanLivePlayerUiPolicy.controlsMayAutoHide(uiState, chrome.retryEnabled)) {
            delay(CLEAN_LIVE_CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    // When the chrome hides, its focusable buttons leave composition; grab focus back to the root
    // so the next key press still reaches the handler that reveals the controls.
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) runCatching { rootFocus.requestFocus() }
    }

    DisposableEffect(Unit) {
        ImmersivePlaybackGate.setImmersive(true)
        onDispose { ImmersivePlaybackGate.setImmersive(false) }
    }
    BackHandler(onBack = onExitRequested)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    controlsVisible = true
                    revealTick++
                }
                val action = CleanLivePlayerUiPolicy.remoteAction(
                    keyCode = event.nativeKeyEvent.keyCode,
                    keyAction = event.nativeKeyEvent.action,
                    repeatCount = event.nativeKeyEvent.repeatCount,
                    uiState = uiState,
                ) ?: return@onPreviewKeyEvent false
                dispatchRemoteAction(
                    action = action,
                    onPause = onPause,
                    onResume = onResume,
                    onZapPrevious = onZapPrevious,
                    onZapNext = onZapNext,
                )
                true
            },
    ) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).also(latestSurfaceOwnerReady)
            },
            update = { owner ->
                owner.keepScreenOn = chrome.keepScreenOn
            },
            modifier = Modifier.fillMaxSize(),
        )

        CleanLivePlayerChrome(
            sanitizedTitle = sanitizedTitle,
            sanitizedSubtitle = sanitizedSubtitle,
            sanitizedStation = sanitizedStation,
            uiState = uiState,
            chrome = chrome,
            controlsVisible = controlsVisible,
            onPause = onPause,
            onResume = onResume,
            onRetry = onRetry,
            onZapPrevious = onZapPrevious,
            onZapNext = onZapNext,
            onExitRequested = onExitRequested,
        )
    }
}

@Composable
private fun CleanLivePlayerChrome(
    sanitizedTitle: String,
    sanitizedSubtitle: String?,
    sanitizedStation: String?,
    uiState: LivePlaybackUiState,
    chrome: CleanLivePlayerChromeState,
    controlsVisible: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onZapPrevious: () -> Unit,
    onZapNext: () -> Unit,
    onExitRequested: () -> Unit,
) {
    val playPauseFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val exitFocus = remember { FocusRequester() }

    // Re-focus a control each time the overlay reappears; when hidden its buttons aren't composed.
    LaunchedEffect(controlsVisible, uiState.controlsEnabled, chrome.retryEnabled) {
        if (!controlsVisible) return@LaunchedEffect
        runCatching {
            when {
                uiState.controlsEnabled -> playPauseFocus.requestFocus()
                chrome.retryEnabled -> retryFocus.requestFocus()
                else -> exitFocus.requestFocus()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // The spinner is playback feedback, not a control — it stays regardless of the overlay.
        if (uiState.spinnerVisible) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
            )
        }

        if (!controlsVisible) return@Box

        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(250.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                    ),
                ),
        )

        CleanLiveTitle(
            station = sanitizedStation,
            title = sanitizedTitle,
            subtitle = sanitizedSubtitle,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 48.dp, vertical = 32.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            chrome.messageRes?.let { messageRes ->
                Text(
                    text = stringResource(messageRes),
                    color = if (chrome.messageIsError) Color(0xFFFFB4AB) else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CleanLiveControlButton(
                    icon = Icons.Default.SkipPrevious,
                    descriptionRes = R.string.clean_live_action_previous_channel,
                    enabled = uiState.controlsEnabled,
                    onClick = onZapPrevious,
                )
                CleanLiveControlButton(
                    icon = if (uiState.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                    descriptionRes = if (uiState.playWhenReady) {
                        R.string.clean_live_action_pause
                    } else {
                        R.string.clean_live_action_play
                    },
                    enabled = uiState.controlsEnabled,
                    onClick = if (uiState.playWhenReady) onPause else onResume,
                    modifier = Modifier.focusRequester(playPauseFocus),
                )
                CleanLiveControlButton(
                    icon = Icons.Default.Refresh,
                    descriptionRes = R.string.clean_live_action_retry,
                    enabled = chrome.retryEnabled,
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(retryFocus),
                )
                CleanLiveControlButton(
                    icon = Icons.Default.SkipNext,
                    descriptionRes = R.string.clean_live_action_next_channel,
                    enabled = uiState.controlsEnabled,
                    onClick = onZapNext,
                )
                Spacer(Modifier.weight(1f))
                CleanLiveControlButton(
                    icon = Icons.Default.ArrowBack,
                    descriptionRes = R.string.clean_live_action_exit,
                    enabled = true,
                    onClick = onExitRequested,
                    modifier = Modifier.focusRequester(exitFocus),
                )
            }
        }
    }
}

@Composable
private fun CleanLiveTitle(
    station: String?,
    title: String,
    subtitle: String?,
    modifier: Modifier,
) {
    Column(modifier) {
        station?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
        )
        subtitle?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CleanLiveControlButton(
    icon: ImageVector,
    @StringRes descriptionRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(52.dp),
        colors = IconButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.06f),
            disabledContentColor = Color.White.copy(alpha = 0.3f),
        ),
        shape = IconButtonDefaults.shape(shape = RoundedCornerShape(14.dp)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(descriptionRes),
            modifier = Modifier.size(26.dp),
        )
    }
}

private fun dispatchRemoteAction(
    action: CleanLiveRemoteAction,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onZapPrevious: () -> Unit,
    onZapNext: () -> Unit,
) = when (action) {
    CleanLiveRemoteAction.PAUSE -> onPause()
    CleanLiveRemoteAction.RESUME -> onResume()
    CleanLiveRemoteAction.ZAP_PREVIOUS -> onZapPrevious()
    CleanLiveRemoteAction.ZAP_NEXT -> onZapNext()
}

internal enum class CleanLiveRemoteAction { PAUSE, RESUME, ZAP_PREVIOUS, ZAP_NEXT }

internal data class CleanLivePlayerChromeState(
    val keepScreenOn: Boolean,
    val retryEnabled: Boolean,
    @get:StringRes val messageRes: Int?,
    val messageIsError: Boolean,
)

internal object CleanLivePlayerUiPolicy {
    fun present(uiState: LivePlaybackUiState): CleanLivePlayerChromeState {
        val error = uiState.bottomErrorCode
        return CleanLivePlayerChromeState(
            keepScreenOn = uiState.playWhenReady &&
                (uiState.isPlaying || uiState.spinnerVisible),
            retryEnabled = error is LivePlaybackUiErrorCode.PlaybackFailed ||
                error is LivePlaybackUiErrorCode.PreviewUnavailable,
            messageRes = error?.let(::errorMessageRes)
                ?: uiState.bottomStatusCode?.let(::statusMessageRes),
            messageIsError = error != null,
        )
    }

    /**
     * Whether the overlay chrome may auto-hide after the idle delay. It hides only while playback
     * is proceeding under the user's play intent with nothing to act on; a paused stream or an
     * actionable (retryable) error keeps the controls up so the buttons stay reachable. This is the
     * decision the Sports/clean-player path was missing entirely — its chrome was drawn
     * unconditionally, so the controls never went away over the video.
     */
    fun controlsMayAutoHide(uiState: LivePlaybackUiState, retryEnabled: Boolean): Boolean =
        uiState.playWhenReady && !retryEnabled

    /** UP/DOWN are consumed by the screen only when an enabled zap is actually dispatched. */
    fun remoteAction(
        keyCode: Int,
        keyAction: Int,
        repeatCount: Int,
        uiState: LivePlaybackUiState,
    ): CleanLiveRemoteAction? {
        if (keyAction != KeyEvent.ACTION_DOWN) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (uiState.controlsEnabled) {
                CleanLiveRemoteAction.ZAP_PREVIOUS
            } else {
                null
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (uiState.controlsEnabled) {
                CleanLiveRemoteAction.ZAP_NEXT
            } else {
                null
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (repeatCount == 0 && uiState.controlsEnabled) {
                if (uiState.playWhenReady) CleanLiveRemoteAction.PAUSE
                else CleanLiveRemoteAction.RESUME
            } else {
                null
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (
                repeatCount == 0 && uiState.controlsEnabled && !uiState.playWhenReady
            ) {
                CleanLiveRemoteAction.RESUME
            } else {
                null
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (
                repeatCount == 0 && uiState.controlsEnabled && uiState.playWhenReady
            ) {
                CleanLiveRemoteAction.PAUSE
            } else {
                null
            }
            else -> null
        }
    }

    @StringRes
    fun statusMessageRes(code: LivePlaybackUiStatusCode): Int = when (code) {
        LivePlaybackUiStatusCode.RESOLVING -> R.string.clean_live_status_resolving
        LivePlaybackUiStatusCode.STARTING -> R.string.clean_live_status_starting
        LivePlaybackUiStatusCode.BUFFERING -> R.string.clean_live_status_buffering
        LivePlaybackUiStatusCode.RECOVERING -> R.string.clean_live_status_recovering
        LivePlaybackUiStatusCode.HANDING_OFF -> R.string.clean_live_status_handing_off
        LivePlaybackUiStatusCode.RECONNECTING -> R.string.clean_live_status_reconnecting
        LivePlaybackUiStatusCode.PAUSED -> R.string.clean_live_status_paused
        LivePlaybackUiStatusCode.RELEASING -> R.string.clean_live_status_releasing
        LivePlaybackUiStatusCode.STOPPED -> R.string.clean_live_status_stopped
    }

    @StringRes
    fun errorMessageRes(code: LivePlaybackUiErrorCode): Int = when (code) {
        is LivePlaybackUiErrorCode.PreviewUnavailable -> previewErrorMessageRes(code.reasonCode)
        is LivePlaybackUiErrorCode.StreamUnavailable -> streamErrorMessageRes(code.reasonCode)
        is LivePlaybackUiErrorCode.PlaybackFailed -> playbackErrorMessageRes(code.reasonCode)
    }

    @StringRes
    private fun previewErrorMessageRes(reason: PreviewUnavailableReason): Int = when (reason) {
        PreviewUnavailableReason.GUIDE_RESOURCE_RESTRICTION ->
            R.string.clean_live_error_preview_resource
        PreviewUnavailableReason.GUIDE_SURFACE_RESTRICTION ->
            R.string.clean_live_error_preview_surface
        PreviewUnavailableReason.GUIDE_RENDER_PATH_UNAVAILABLE,
        PreviewUnavailableReason.PREFERRED_ENGINE_FAILED,
        -> R.string.clean_live_error_preview_path
        PreviewUnavailableReason.ALL_PREVIEW_GRAPHS_FAILED ->
            R.string.clean_live_error_preview_exhausted
    }

    @StringRes
    private fun streamErrorMessageRes(reason: StreamUnavailableReason): Int = when (reason) {
        StreamUnavailableReason.AUTHORIZATION -> R.string.clean_live_error_stream_authorization
        StreamUnavailableReason.REMOVED_OR_EXPIRED -> R.string.clean_live_error_stream_expired
        StreamUnavailableReason.PROVIDER_DECLARED -> R.string.clean_live_error_stream_provider
        StreamUnavailableReason.NO_ELIGIBLE_GRAPH -> R.string.clean_live_error_stream_no_graph
    }

    @StringRes
    private fun playbackErrorMessageRes(reason: FailureCode): Int = when (reason) {
        FailureCode.NETWORK_UNREACHABLE,
        FailureCode.NETWORK_TIMEOUT,
        FailureCode.LIVE_RECONNECT_EXHAUSTED,
        -> R.string.clean_live_error_network
        FailureCode.AUTHORIZATION_REJECTED,
        FailureCode.PROVIDER_CONNECTION_LIMIT,
        -> R.string.clean_live_error_access
        FailureCode.TLS_HANDSHAKE_FAILED -> R.string.clean_live_error_secure_connection
        FailureCode.MANIFEST_INVALID,
        FailureCode.DEMUX_FAILED,
        -> R.string.clean_live_error_stream_format
        FailureCode.VIDEO_DECODER_UNAVAILABLE,
        FailureCode.VIDEO_DECODER_FAILED,
        FailureCode.VIDEO_RENDERER_FAILED,
        -> R.string.clean_live_error_video
        FailureCode.SURFACE_LOST -> R.string.clean_live_error_surface
        FailureCode.AUDIO_OUTPUT_FAILED,
        FailureCode.AUDIO_DECODER_FAILED,
        FailureCode.AUDIO_SINK_FAILED,
        -> R.string.clean_live_error_audio
        FailureCode.SUBTITLE_OUTPUT_UNSUPPORTED -> R.string.clean_live_error_stream_format
        FailureCode.DRM_UNSUPPORTED,
        FailureCode.DRM_LICENSE_FAILED,
        -> R.string.clean_live_error_drm
        FailureCode.RESOURCE_BUDGET_EXCEEDED -> R.string.clean_live_error_resources
        FailureCode.RESOURCE_RELEASE_FAILED -> R.string.clean_live_error_release
        FailureCode.NO_ELIGIBLE_GRAPH -> R.string.clean_live_error_no_graph
        FailureCode.NO_PROGRESS -> R.string.clean_live_error_no_progress
        FailureCode.UNKNOWN -> R.string.clean_live_error_unknown
    }
}
