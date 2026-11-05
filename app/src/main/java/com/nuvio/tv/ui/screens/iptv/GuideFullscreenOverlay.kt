package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.picture.aspectModeLabel
import com.nuvio.tv.core.picture.nextAspectMode
import com.nuvio.tv.playback.live.LiveZapDirection
import com.nuvio.tv.playback.ui.LivePlaybackUiStatusCode
import com.nuvio.tv.ui.components.player.PlayerControlsTiming
import com.nuvio.tv.ui.screens.player.clean.CleanLivePlayerUiPolicy
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsOverlay
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy
import com.nuvio.tv.ui.screens.player.clean.live.LiveOverlayActions
import com.nuvio.tv.ui.screens.player.clean.live.LiveOverlayInfo
import com.nuvio.tv.ui.screens.player.clean.live.LivePanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * B180: the fullscreen overlay's own state. It used to be three `remember`ed vars inside the
 * 1.6k-line guide screen function, which READ them in composition (effect keys, `if (controlsVisible)`),
 * so every zap press restarted the whole hidden guide. Held here, the screen body only ever touches
 * them from event handlers (no composition read), and the two composables below are the only
 * readers: a press recomposes the overlay, not the guide.
 */
@Stable
internal class FullscreenControlsState {
    var visible by mutableStateOf(false)
    var moreOpen by mutableStateOf(false)
    /** Bumped by any activity that should restart the shared auto-hide. */
    var tick by mutableIntStateOf(0)
    /** The controls on screen came from a zap and nothing else has touched them since (LiveControlsPolicy). */
    var zapReveal by mutableStateOf(false)

    fun show() { visible = true; tick++ }
    fun keepAlive() { tick++ }
    fun reset() { visible = false; zapReveal = false; moreOpen = false }
}

/**
 * B180: what the hidden guide body is allowed to see. The aimed channel moves on every zap press;
 * nothing the body draws needs it (the preview pane and the overlay read it themselves), so the
 * body's state is the real state with that one field cleared. Two states that differ only by the
 * aim project equal, so a press emits nothing to the body.
 */
internal object GuideBodyProjection {
    fun of(state: LiveGuideUiState): LiveGuideUiState =
        if (state.focusedChannelId == null) state else state.copy(focusedChannelId = null)
}

/**
 * A zap retunes: the spinner is up, or the aimed channel is not the playing one yet. The aim only
 * counts when it names a channel that is in the list.
 */
internal object GuideZapTuningPolicy {
    fun tuning(spinnerVisible: Boolean, aimedContentId: String?, playingContentId: String?): Boolean =
        spinnerVisible || (aimedContentId != null && aimedContentId != playingContentId)
}

/** The aimed channel's content id, as a State that changes only when the aim does. */
@Composable
private fun rememberAimedContentId(viewModel: XtreamLiveGuideViewModel): androidx.compose.runtime.State<String?> {
    val flow = remember(viewModel) {
        viewModel.uiState.map { it.focusedChannel?.contentId }.distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value.focusedChannel?.contentId)
}

/**
 * The overlay's effects (trouble reveal, auto-hide, primary focus). They key on the controls state and the
 * aim, so they live here, in a scope of their own, instead of making the guide screen read them.
 */
@Composable
internal fun GuideFullscreenControlsEffects(
    fullscreen: Boolean,
    controls: FullscreenControlsState,
    viewModel: XtreamLiveGuideViewModel,
    playingContentId: String?,
    spinnerVisible: Boolean,
    paused: Boolean,
    liveFailed: Boolean,
    liveReconnecting: Boolean,
    livePanelOpen: Boolean,
    primaryFocus: FocusRequester,
    onHide: () -> Unit,
) {
    val aimed by rememberAimedContentId(viewModel)
    val tuning = GuideZapTuningPolicy.tuning(spinnerVisible, aimed, playingContentId)
    // T10: a failed tune or a reconnect shows the overlay (and its reason) without waiting for OK.
    LaunchedEffect(fullscreen, liveFailed, liveReconnecting) {
        if (fullscreen && LiveControlsPolicy.revealForTrouble(liveFailed, liveReconnecting, controls.visible, livePanelOpen)) {
            controls.show()
        }
    }
    // F28: one auto-hide delay shared with VOD; never while paused, on an error, or with a panel open.
    LaunchedEffect(fullscreen, controls.tick, paused, liveFailed, livePanelOpen, tuning) {
        if (fullscreen && LiveControlsPolicy.mayAutoHide(controls.visible, paused, liveFailed, livePanelOpen, tuning = tuning)) {
            delay(PlayerControlsTiming.AUTO_HIDE_MS)
            onHide()
        }
    }
    // Shown controls take the D-pad, starting on Retry (when offered) or play/pause.
    LaunchedEffect(fullscreen, controls.visible, livePanelOpen) {
        if (fullscreen && controls.visible && !livePanelOpen) primaryFocus.requestFocusOrFalse()
    }
}

/** The fullscreen live controls, drawn only while visible. Collects the guide state itself. */
@Composable
internal fun BoxScope.GuideFullscreenOverlay(
    controls: FullscreenControlsState,
    viewModel: XtreamLiveGuideViewModel,
    playbackViewModel: CleanLiveGuidePlaybackViewModel,
    playbackState: CleanLiveGuidePlaybackState,
    playingContentId: String?,
    nowMs: Long,
    favoriteIds: Set<String>,
    livePicture: com.nuvio.tv.core.picture.PictureChoice,
    primaryFocus: FocusRequester,
    onTogglePause: () -> Unit,
    onHide: () -> Unit,
    onOpenPanel: (LivePanel) -> Unit,
    onAspectLabel: (String) -> Unit,
    onOpenChannelList: () -> Unit,
) {
    val context = LocalContext.current
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val readyPlayback = playbackState as? CleanLiveGuidePlaybackState.Ready
    val playbackUi = readyPlayback?.presentation
    val liveChrome = playbackUi?.let { CleanLivePlayerUiPolicy.present(it) }
    // The focused channel is the AIMED one during a settled zap; it converges with the playing
    // channel once the tune commits. Naming the aim (not the frame still on screen) is what makes
    // a multi-press zap legible.
    val overlayChannel = ui.focusedChannel
        ?: ui.channels.firstOrNull { it.contentId == playingContentId }
    val loadedOverlayEpg = overlayChannel?.let { ui.epg[it.streamId] }
    val overlayEpg = remember(loadedOverlayEpg, nowMs) {
        loadedOverlayEpg?.let { GuideWindowRows.atTime(it, nowMs) }
    }
    val catalog = readyPlayback?.snapshot?.trackCatalog
    // Same message the docked preview shows; without it a failed tune read "Live".
    val errorText = playbackErrorText(playbackState, playbackUi?.bottomErrorCode)
    val status = playbackUi?.bottomStatusCode
    val tuningNow = GuideZapTuningPolicy.tuning(
        spinnerVisible = playbackUi?.spinnerVisible == true,
        aimedContentId = ui.focusedChannel?.contentId,
        playingContentId = playingContentId,
    )
    val currentButtons = LiveControlsPolicy.buttons(
        failed = liveChrome?.retryEnabled == true,
        subtitleTracks = catalog?.subtitles?.size ?: 0,
        audioTracks = catalog?.audio?.size ?: 0,
        hasLineup = true,
    )
    // Zapping retunes every press: hold the track buttons steady until the stream settles.
    var settledButtons by remember { mutableStateOf<LiveControlsPolicy.Buttons?>(null) }
    if (!tuningNow) settledButtons = currentButtons
    LiveControlsOverlay(
        info = LiveOverlayInfo(
            channelName = overlayChannel?.name.orEmpty(),
            logoUrl = overlayChannel?.logo,
            reserveLogoSlot = true,
            nowTitle = overlayEpg?.now?.let { "${timeRange(it)}  ${it.title}" },
            nowStartMs = overlayEpg?.now?.startMs,
            nowEndMs = overlayEpg?.now?.endMs,
            nextLine = overlayEpg?.next?.let { stringResource(R.string.iptv_guide_next_programme, timeRange(it), it.title) },
            status = LiveControlsPolicy.status(
                tuning = tuningNow,
                reconnecting = status == LivePlaybackUiStatusCode.RECONNECTING ||
                    status == LivePlaybackUiStatusCode.RECOVERING,
                paused = playbackUi?.isPaused == true,
                failed = errorText != null,
            ),
            errorText = errorText,
        ),
        nowMs = nowMs,
        buttons = LiveControlsPolicy.holdWhileTuning(settledButtons, currentButtons, tuningNow),
        paused = playbackUi?.isPaused == true,
        favourite = overlayChannel?.contentId?.let { it in favoriteIds } == true,
        moreOpen = controls.moreOpen,
        primaryFocus = primaryFocus,
        actions = LiveOverlayActions(
            onPlayPause = onTogglePause,
            onZapPrevious = {
                viewModel.moveChannelFocus(LiveZapDirection.PREVIOUS, playingContentId)
                    ?.let(playbackViewModel::requestSettledTune)
                controls.keepAlive()
            },
            onZapNext = {
                viewModel.moveChannelFocus(LiveZapDirection.NEXT, playingContentId)
                    ?.let(playbackViewModel::requestSettledTune)
                controls.keepAlive()
            },
            onRetry = { playbackViewModel.requestRetry(); controls.keepAlive() },
            onSubtitles = { onOpenPanel(LivePanel.SUBTITLES) },
            onAudio = { onOpenPanel(LivePanel.AUDIO) },
            // F28: lane F's picture model, per channel. One press = next aspect mode.
            onAspect = {
                val next = nextAspectMode(livePicture.aspectMode)
                playbackViewModel.requestPictureChange { it.copy(aspectMode = next) }
                onAspectLabel(aspectModeLabel(next, context::getString))
                controls.keepAlive()
            },
            onZoom = { onOpenPanel(LivePanel.ZOOM) },
            onChannelList = onOpenChannelList,
            onToggleFavourite = overlayChannel?.let { ch -> { viewModel.toggleFavorite(ch); controls.keepAlive() } },
            onStreamInfo = { onOpenPanel(LivePanel.STREAM_INFO) }.takeIf { readyPlayback != null },
            onToggleMore = { controls.moreOpen = !controls.moreOpen; controls.keepAlive() },
            onKeepAlive = { controls.keepAlive() },
            onHide = onHide,
        ),
    )
}
