package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Fork-only: the live-specific background/foreground behaviour, extracted out of the shared
 * pauseForLifecycle / resumeForLifecycle in PlayerRuntimeControllerMpv so those upstream methods keep
 * only a single fork call each (research/tv-player-mpv-engine-ownership.md, Part B). Same package =
 * the shared methods call these unqualified.
 */

/**
 * Background handling for the mpv engine. Live: kill the demux now instead of pausing — a paused live
 * socket goes stale and wedges the core, which would then block the main thread inside the synchronous
 * surface teardown; resume reloads the stream anyway. Non-live: ordinary pause.
 */
internal fun PlayerRuntimeController.pauseMpvForLifecycle() {
    if (isLiveFeed()) {
        mpvView?.stopPlayback()
    } else {
        mpvView?.setPaused(true)
    }
}

/**
 * Foreground handling for a live mpv channel: the paused buffer is stale and the socket likely dead,
 * so rejoin the live edge instead of unpausing. Catch-up replays are excluded (no live edge; reloading
 * would restart the recording). Returns true when it handled the resume, false to fall through to the
 * ordinary resume path.
 */
internal fun PlayerRuntimeController.tryResumeLiveForLifecycle(): Boolean {
    if (!isUsingMpvEngine() || !isLiveFeed() || currentStreamUrl.isBlank()) return false
    val view = mpvView ?: return true
    userPausedManually = false
    scope.launch(Dispatchers.Default) {
        runCatching {
            // loadfile keeps mpv's pause property — unpause or the rejoin stays frozen.
            view.setPaused(false)
            view.setMediaUsingLoadfile(currentStreamUrl, currentHeaders)
        }
    }
    _uiState.update { it.copy(isPlaying = true, isBuffering = true) }
    cancelPauseOverlay()
    startProgressUpdates()
    startWatchProgressSaving()
    return true
}
