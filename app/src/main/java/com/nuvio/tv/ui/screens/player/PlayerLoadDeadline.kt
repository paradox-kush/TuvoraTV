package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wires [PlayerLoadDeadlinePolicy] to the player. Called by every new load attempt
 * ([resetLoadingOverlayForNewStream]). The [StartDeadlineClock] counts only active time (not
 * paused or backgrounded), is kept across auto-retries and engine failovers, and restarts on a new
 * source identity (stream URL / video), after a failure was shown, and on a viewer restart.
 */
internal fun PlayerRuntimeController.armLoadDeadline() {
    val now = SystemClock.elapsedRealtime()
    if (loadDeadlineFailed) {
        // The shown failure was cleared by a new attempt (a source pick, Retry): a full new window.
        loadDeadlineFailed = false
        loadDeadlineFailedOffline = false
        loadDeadlineClock.onAttempt(null, now, force = true)
        // The failure's Retry affordance goes with the failure (a source pick clears the error).
        _uiState.update { if (it.showRetryErrorAction) it.copy(showRetryErrorAction = false) else it }
    } else {
        loadDeadlineClock.onAttempt(loadDeadlineKey(), now)
    }
    if (loadDeadlineJob?.isActive == true) return
    loadDeadlineJob = scope.launch {
        while (isActive) {
            delay(PlayerLoadDeadlinePolicy.POLL_INTERVAL_MS)
            val state = _uiState.value
            val tickNow = SystemClock.elapsedRealtime()
            // A different source than the one being timed (picked after the attempt began): new window.
            loadDeadlineClock.onAttempt(loadDeadlineKey(), tickNow)
            // Only a real background parks the clock: userPausedManually is also set by the lifecycle
            // pause (and never cleared by a resume), so it must not gate the deadline. Time with an
            // error on screen is not the viewer waiting either.
            val suspended = PlayerLoadDeadlinePolicy.clockSuspended(isInBackground, state.error != null)
            // Audio-only streams never render a first video frame: actually playing is "started".
            val started = PlayerLoadDeadlinePolicy.started(hasRenderedFirstFrame, _exoPlayer?.isPlaying == true)
            val action = PlayerLoadDeadlinePolicy.decide(
                elapsedMs = loadDeadlineClock.tick(tickNow, suspended),
                online = context.isNetworkOnline(),
                firstFrameRendered = started,
                isLive = LivePlaybackUiPolicy.isLivePlayback(
                    playerReportsLive = state.isLive,
                    contentType = contentType,
                    latchedLive = livePlaybackLatched
                ),
                isTorrent = state.isTorrentStream,
                suspended = suspended,
                hasError = state.error != null,
                failedOffline = loadDeadlineFailedOffline,
            )
            when (action) {
                PlayerLoadDeadlinePolicy.Action.Wait -> Unit
                PlayerLoadDeadlinePolicy.Action.FailOffline ->
                    failLoadDeadline(context.getString(R.string.player_error_offline), offline = true)
                PlayerLoadDeadlinePolicy.Action.FailTimeout ->
                    failLoadDeadline(context.getString(R.string.player_error_start_timeout), offline = false)
                PlayerLoadDeadlinePolicy.Action.RetryNetworkBack -> onEvent(PlayerEvent.OnRetry)
            }
            if (started) {
                loadDeadlineClock.clear()
                break
            }
        }
        loadDeadlineJob = null
    }
}

private fun PlayerRuntimeController.loadDeadlineKey(): String = "$currentStreamUrl|$currentVideoId"

/** A viewer-driven restart (Retry, manual engine switch): the next attempt gets a full window. */
internal fun PlayerRuntimeController.restartLoadDeadline() {
    loadDeadlineClock.reset(SystemClock.elapsedRealtime())
    loadDeadlineFailedOffline = false
    loadDeadlineFailed = false
}

private fun PlayerRuntimeController.failLoadDeadline(message: String, offline: Boolean) {
    errorRetryJob?.cancel()
    loadDeadlineFailed = true
    loadDeadlineFailedOffline = offline
    // Stop the engine so a late success cannot play under the error dialog.
    releasePlayer(flushPlaybackState = false)
    finishLoadingDiagnostics(if (offline) "load_deadline_offline" else "load_deadline_timeout")
    _uiState.update {
        it.copy(
            error = message,
            showRetryErrorAction = true,
            showSwitchToMpvErrorAction = false,
            showLoadingOverlay = false,
            showPauseOverlay = false,
            isBuffering = false,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
        )
    }
}

private fun Context.isNetworkOnline(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return true
    return runCatching {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(true)
}
