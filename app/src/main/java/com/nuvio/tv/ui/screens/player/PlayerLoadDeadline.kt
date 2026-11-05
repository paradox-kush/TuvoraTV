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
 * Wires [PlayerLoadDeadlinePolicy] to the player. The anchor is set by the first attempt of a
 * stream and survives auto-retries and engine failovers; it restarts only on a user action
 * (Retry, manual engine switch) so the deadline is the viewer's real wait.
 */
internal fun PlayerRuntimeController.armLoadDeadline() {
    if (loadDeadlineAnchorMs == 0L) loadDeadlineAnchorMs = SystemClock.elapsedRealtime()
    if (loadDeadlineJob?.isActive == true) return
    loadDeadlineJob = scope.launch {
        while (isActive) {
            delay(PlayerLoadDeadlinePolicy.POLL_INTERVAL_MS)
            val state = _uiState.value
            val online = context.isNetworkOnline()
            val action = PlayerLoadDeadlinePolicy.decide(
                elapsedMs = SystemClock.elapsedRealtime() - loadDeadlineAnchorMs,
                online = online,
                firstFrameRendered = hasRenderedFirstFrame,
                isLive = LivePlaybackUiPolicy.isLivePlayback(
                    playerReportsLive = state.isLive,
                    contentType = contentType,
                    latchedLive = livePlaybackLatched
                ),
                isTorrent = state.isTorrentStream,
                suspended = isInBackground || userPausedManually,
                hasError = state.error != null,
                failedOffline = loadDeadlineFailedOffline,
            )
            when (action) {
                PlayerLoadDeadlinePolicy.Action.Wait -> Unit
                PlayerLoadDeadlinePolicy.Action.FailOffline ->
                    failLoadDeadline(context.getString(R.string.player_error_offline), offline = true)
                PlayerLoadDeadlinePolicy.Action.FailTimeout ->
                    failLoadDeadline(context.getString(R.string.player_error_start_timeout), offline = false)
                PlayerLoadDeadlinePolicy.Action.RetryNetworkBack -> {
                    onEvent(PlayerEvent.OnRetry)
                }
            }
            if (hasRenderedFirstFrame) {
                loadDeadlineAnchorMs = 0L
                break
            }
        }
        loadDeadlineJob = null
    }
}

/** A user-driven restart: the next attempt gets a full deadline. */
internal fun PlayerRuntimeController.restartLoadDeadline() {
    loadDeadlineAnchorMs = SystemClock.elapsedRealtime()
    loadDeadlineFailedOffline = false
}

private fun PlayerRuntimeController.failLoadDeadline(message: String, offline: Boolean) {
    errorRetryJob?.cancel()
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
