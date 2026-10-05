@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player.clean

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PlaybackTrackCatalog
import com.nuvio.tv.playback.core.PlaybackTrackId
import com.nuvio.tv.playback.core.PreviewUnavailableReason
import com.nuvio.tv.playback.core.StreamUnavailableReason
import com.nuvio.tv.playback.ui.LivePlaybackUiErrorCode
import com.nuvio.tv.playback.ui.LivePlaybackUiState
import com.nuvio.tv.playback.ui.LivePlaybackUiStatusCode
import com.nuvio.tv.ui.components.player.PlayerControlsTiming
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsOverlay
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy
import com.nuvio.tv.ui.screens.player.clean.live.LiveOverlayActions
import com.nuvio.tv.ui.screens.player.clean.live.LiveOverlayInfo
import com.nuvio.tv.ui.screens.player.clean.live.LivePanel
import com.nuvio.tv.ui.screens.player.clean.live.LiveStreamInfoDialog
import com.nuvio.tv.ui.screens.player.clean.live.LiveTrackChoices
import com.nuvio.tv.ui.screens.player.clean.live.LiveTrackDialog
import com.nuvio.tv.updater.ImmersivePlaybackGate
import kotlinx.coroutines.delay

/**
 * Engine-neutral fullscreen live UI (a Sports launch, a one-off channel). The future route owns
 * session construction and release. [onExitRequested] must release its host before removing this
 * screen from composition.
 *
 * F28: draws the ONE live overlay ([LiveControlsOverlay], the VOD player's own pieces) that the
 * guide's fullscreen draws too, with the same remote rules ([LiveControlsPolicy]). There is no
 * lineup here, so no channel list or favourite; channel up/down zap the published lineup.
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
    trackCatalog: PlaybackTrackCatalog = PlaybackTrackCatalog(),
    streamInfo: List<Pair<String, String>> = emptyList(),
    onSelectAudio: (PlaybackTrackId) -> Unit = {},
    onSelectSubtitle: (PlaybackTrackId?) -> Unit = {},
) {
    val chrome = CleanLivePlayerUiPolicy.present(uiState)
    val latestSurfaceOwnerReady by rememberUpdatedState(onSurfaceOwnerReady)

    // Starts shown so the viewer sees what's playing, then hides on the shared delay.
    var controlsVisible by remember { mutableStateOf(true) }
    var revealTick by remember { mutableIntStateOf(0) }
    var moreOpen by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<LivePanel?>(null) }
    val rootFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val paused = !uiState.playWhenReady
    // Any error keeps the controls up; Retry itself is offered only where retrying can help.
    val failed = chrome.messageIsError
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }

    fun showControls() { controlsVisible = true; revealTick++ }
    fun hideControls() { controlsVisible = false; moreOpen = false }

    LaunchedEffect(revealTick, controlsVisible, paused, failed, panel) {
        if (LiveControlsPolicy.mayAutoHide(controlsVisible, paused, failed, panelOpen = panel != null)) {
            delay(PlayerControlsTiming.AUTO_HIDE_MS)
            hideControls()
        }
    }
    // Shown: focus the first control. Hidden: the buttons leave composition, so focus returns to
    // the root, where the next key still reaches the policy.
    LaunchedEffect(controlsVisible, failed) {
        runCatching { if (controlsVisible) primaryFocus.requestFocus() else rootFocus.requestFocus() }
    }

    DisposableEffect(Unit) {
        ImmersivePlaybackGate.setImmersive(true)
        onDispose { ImmersivePlaybackGate.setImmersive(false) }
    }
    // BACK: close a panel (the dialogs answer it themselves), then hide the controls, then leave.
    BackHandler {
        when (LiveControlsPolicy.backAction(panelOpen = panel != null, controlsVisible = controlsVisible)) {
            LiveControlsPolicy.BackAction.CLOSE_PANEL -> panel = null
            LiveControlsPolicy.BackAction.HIDE_CONTROLS -> hideControls()
            LiveControlsPolicy.BackAction.EXIT -> onExitRequested()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val action = LiveControlsPolicy.keyAction(
                    key = event.key,
                    isKeyDown = event.type == KeyEventType.KeyDown,
                    controlsVisible = controlsVisible,
                    paused = paused,
                    panelOpen = panel != null,
                    isRepeat = event.nativeKeyEvent.repeatCount > 0,
                )
                when (action) {
                    LiveControlsPolicy.KeyAction.PASS -> return@onPreviewKeyEvent false
                    LiveControlsPolicy.KeyAction.PASS_AND_KEEP_ALIVE -> {
                        if (event.type == KeyEventType.KeyDown) revealTick++
                        return@onPreviewKeyEvent false
                    }
                    LiveControlsPolicy.KeyAction.CONSUME -> Unit
                    LiveControlsPolicy.KeyAction.SHOW_CONTROLS -> showControls()
                    LiveControlsPolicy.KeyAction.TOGGLE_PAUSE -> {
                        if (uiState.controlsEnabled) { if (paused) onResume() else onPause() }
                        showControls()
                    }
                    // Zapping is only dispatched while the session accepts controls; disabled keys
                    // fall through rather than being swallowed silently.
                    LiveControlsPolicy.KeyAction.ZAP_PREVIOUS ->
                        if (uiState.controlsEnabled) onZapPrevious() else return@onPreviewKeyEvent false
                    LiveControlsPolicy.KeyAction.ZAP_NEXT ->
                        if (uiState.controlsEnabled) onZapNext() else return@onPreviewKeyEvent false
                    // No lineup history or hideable channel identity on this destination.
                    LiveControlsPolicy.KeyAction.ZAP_BACK,
                    LiveControlsPolicy.KeyAction.HIDE_CHANNEL -> showControls()
                }
                true
            },
    ) {
        AndroidView(
            factory = { context -> FrameLayout(context).also(latestSurfaceOwnerReady) },
            update = { owner -> owner.keepScreenOn = chrome.keepScreenOn },
            modifier = Modifier.fillMaxSize(),
        )
        // Playback feedback, not a control — it stays regardless of the overlay.
        if (uiState.spinnerVisible) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(44.dp))
        }
        if (controlsVisible) {
            val buttons = LiveControlsPolicy.buttons(
                failed = chrome.retryEnabled,
                subtitleTracks = trackCatalog.subtitles.size,
                audioTracks = trackCatalog.audio.size,
                hasLineup = false,
            )
            val messageText = chrome.messageRes?.let { stringResource(it) }
            LiveControlsOverlay(
                info = LiveOverlayInfo(
                    channelName = sanitizedTitle,
                    subtitle = listOfNotNull(sanitizedStation, sanitizedSubtitle).filter(String::isNotBlank).joinToString(" · ").ifBlank { null },
                    status = LiveControlsPolicy.status(
                        tuning = uiState.bottomStatusCode == LivePlaybackUiStatusCode.RESOLVING ||
                            uiState.bottomStatusCode == LivePlaybackUiStatusCode.STARTING,
                        reconnecting = uiState.bottomStatusCode == LivePlaybackUiStatusCode.RECONNECTING ||
                            uiState.bottomStatusCode == LivePlaybackUiStatusCode.RECOVERING,
                        paused = paused,
                        failed = chrome.messageIsError,
                    ),
                    errorText = if (chrome.messageIsError) messageText else null,
                ),
                nowMs = nowMs,
                buttons = buttons,
                paused = paused,
                favourite = false,
                moreOpen = moreOpen,
                primaryFocus = primaryFocus,
                actions = LiveOverlayActions(
                    onPlayPause = { if (paused) onResume() else onPause() },
                    onZapPrevious = onZapPrevious.takeIf { uiState.controlsEnabled },
                    onZapNext = onZapNext.takeIf { uiState.controlsEnabled },
                    onRetry = onRetry,
                    onSubtitles = { panel = LivePanel.SUBTITLES },
                    onAudio = { panel = LivePanel.AUDIO },
                    onAspect = null, // TODO(F28-next): needs a clean-host fit/zoom command (see report)
                    onChannelList = null,
                    onToggleFavourite = null,
                    onStreamInfo = { panel = LivePanel.STREAM_INFO }.takeIf { streamInfo.isNotEmpty() },
                    onToggleMore = { moreOpen = !moreOpen; revealTick++ },
                    onKeepAlive = { revealTick++ },
                    onHide = { hideControls() },
                ),
            )
        }
        when (panel) {
            LivePanel.SUBTITLES -> LiveTrackDialog(
                title = stringResource(R.string.cd_subtitles),
                choices = LiveTrackChoices.subtitles(trackCatalog, stringResource(R.string.live_subtitles_off)),
                onPick = { onSelectSubtitle(it); panel = null },
                onDismiss = { panel = null },
            )
            LivePanel.AUDIO -> LiveTrackDialog(
                title = stringResource(R.string.cd_audio_tracks),
                choices = LiveTrackChoices.audio(trackCatalog),
                onPick = { id -> id?.let(onSelectAudio); panel = null },
                onDismiss = { panel = null },
            )
            LivePanel.STREAM_INFO -> LiveStreamInfoDialog(streamInfo) { panel = null }
            null -> Unit
        }
    }
}

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
            // Keep the screen awake on watch INTENT, not the transient isPlaying: FLAG_KEEP_SCREEN_ON
            // must stay held for the whole time the user is watching or Android TV enters Ambient Mode
            // (the screensaver black-out) after its inactivity timeout. Gating on the momentary
            // isPlaying dropped the flag during live stalls/re-buffers, letting the screensaver fire.
            // playWhenReady is false only when the user pauses (isPaused = !playWhenReady), so the
            // screen still correctly sleeps on pause. See developer.android.com .../awake/screen-on.
            keepScreenOn = uiState.playWhenReady,
            retryEnabled = error is LivePlaybackUiErrorCode.PlaybackFailed ||
                error is LivePlaybackUiErrorCode.PreviewUnavailable,
            messageRes = error?.let(::errorMessageRes)
                ?: uiState.bottomStatusCode?.let(::statusMessageRes),
            messageIsError = error != null,
        )
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
