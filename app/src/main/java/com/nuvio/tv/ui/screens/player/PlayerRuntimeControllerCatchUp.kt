package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import com.nuvio.tv.R
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Fork-only: the catch-up replay adapter's calls on upstream's player (audit §7a option i). The
 * decisions are in [CatchUpReplayAdapter]; the URL shapes and their order are in core/iptv behind
 * [PlayerRuntimeController.catchUpPort]. Each function is one call at an upstream seam.
 *
 * Why the walk runs on the real attempt and never on a probe: on a `max_connections=1` account a
 * probe kicks the viewer's own stream, the same reason the .ts→HLS fix refused to probe.
 */

/** Clamps a seek to what the panel has archived; any other playback keeps [targetMs]. */
internal fun PlayerRuntimeController.catchUpSeekTargetMs(targetMs: Long): Long =
    catchUpReplay.seekTargetMs(targetMs, System.currentTimeMillis())

/** A player position as the seek bar shows it (re-based onto the programme for a continuing replay). */
internal fun PlayerRuntimeController.catchUpBarPositionMs(playerMs: Long): Long =
    catchUpReplay.barReading(playerMs, 0L, 0L, System.currentTimeMillis()).positionMs

/** A seek-bar position as the player counts it. */
internal fun PlayerRuntimeController.catchUpPlayerPositionMs(barMs: Long): Long =
    catchUpReplay.playerPositionMs(barMs, System.currentTimeMillis())

/** True for a replay whose link is spent by playing it (a Stalker archive); decided by the port. */
internal fun PlayerRuntimeController.isSingleUseCatchUp(): Boolean =
    SingleUseReplayPolicy.endMessage(catchUpReplay.isCatchUp, catchUpPort?.singleUseFailure(contentId, false)) != null

/** Ties the replay's state in the port to this player, once: it is dropped when the player is. */
private fun PlayerRuntimeController.holdCatchUpSession() {
    if (catchUpHeld) return
    val port = catchUpPort ?: return
    catchUpHeld = true
    catchUpEffects.adopt(port.hold(contentId))
}

/**
 * A single-use replay's mint (credentials + programme id, which Retry needs) is held from the moment the
 * player starts, not from the first playhead tick: leaving during the load must not strand it in the
 * coordinator until newer replays evict it.
 */
internal fun PlayerRuntimeController.holdSingleUseCatchUpAtStart() {
    if (SingleUseReplayPolicy.holdAtStart(isSingleUseCatchUp(), catchUpHeld)) holdCatchUpSession()
}

/** First rendered frame: remember the working URL shape for this account (once per replay). */
internal fun PlayerRuntimeController.reportCatchUpFirstFrame() {
    if (catchUpReplay.onFirstFrame()) catchUpPort?.onPlayed(contentId)
    catchUpStartupJob?.cancel()
    catchUpStartupJob = null
}

/**
 * Arms the first-frame deadline of the link now loading (every (re)initialisation calls this; the
 * budget clock and the timeout count live on [catchUpReplay], so neither a new URL nor an engine
 * failover restarts them). A link that never produces a frame in time is a transport failure: the
 * ladder moves to the next candidate, and when it is exhausted the player ends in a terminal error
 * with Retry — never "Starting stream…" for as long as the provider keeps the socket open.
 */
internal fun PlayerRuntimeController.armCatchUpStartupDeadline() {
    catchUpStartupJob?.cancel()
    catchUpStartupJob = null
    val deadlineMs = catchUpReplay.startupDeadlineMs(System.currentTimeMillis()) ?: return
    catchUpStartupJob = scope.launch {
        kotlinx.coroutines.delay(deadlineMs)
        if (hasRenderedFirstFrame) return@launch
        onCatchUpStartupDeadline()
    }
}

private fun PlayerRuntimeController.onCatchUpStartupDeadline() {
    if (failSingleUseCatchUp(startupHang = true)) return
    val decision = catchUpReplay.onStartupTimeout(System.currentTimeMillis())
    Log.w(PlayerRuntimeController.TAG, "CATCHUP_STARTUP_TIMEOUT: no frame from the replay link in time (decision=$decision)")
    if (decision == CatchUpStartupPolicy.Decision.ADVANCE) {
        val next = catchUpPort?.onFailed(contentId, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        if (next != null) {
            currentStreamUrl = next.url
            _uiState.update { it.copy(currentStreamUrl = next.url) }
            scheduleDeferredPlayerReinitialize(fromPositionMs = 0L, clearResumeProgress = true)
            return
        }
    }
    // Ladder exhausted (or the total budget is spent): stop the player and say so.
    endCatchUpWithError(context.getString(R.string.player_error_catchup_no_answer))
}

/** Stops the player and shows [message] with Retry: the terminal state of a replay that cannot start. */
private fun PlayerRuntimeController.endCatchUpWithError(message: String) {
    catchUpStartupJob?.cancel()
    catchUpStartupJob = null
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    errorRetryJob?.cancel()
    errorRetryJob = null
    releasePlayer(flushPlaybackState = false)
    _uiState.update {
        it.copy(
            error = message,
            showRetryErrorAction = true,
            showSwitchToMpvErrorAction = false,
            isPlaying = false,
            showControls = false,
            isBuffering = false,
            showLoadingOverlay = false,
            showPauseOverlay = false,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
        )
    }
}

/**
 * A failure of a replay whose link is spent by playing (a Stalker archive): no dialect walk, no engine
 * retry, no failover, no probe - every one of those would re-open the spent URL and spend a request or
 * a connection slot on a link that cannot play twice. The replay ends in its own message with Retry,
 * and Retry mints a fresh link ([retrySingleUseCatchUp]). True when it was one and has been ended.
 */
internal fun PlayerRuntimeController.failSingleUseCatchUp(startupHang: Boolean): Boolean {
    val message = SingleUseReplayPolicy.endMessage(
        catchUpReplay.isCatchUp, catchUpPort?.singleUseFailure(contentId, startupHang),
    ) ?: return false
    Log.w(PlayerRuntimeController.TAG, "CATCHUP_SINGLE_USE: link failed (startupHang=$startupHang); ending with Retry, no re-open")
    endCatchUpWithError(message)
    return true
}

/**
 * Retry on a single-use replay: ask the portal for a FRESH link and open that. Never the spent URL.
 * False when this is not such a replay (the caller's ordinary retry runs).
 */
internal fun PlayerRuntimeController.retrySingleUseCatchUp(): Boolean {
    val port = catchUpPort ?: return false
    if (!isSingleUseCatchUp()) return false
    catchUpReplay.onRelinked()
    hasRenderedFirstFrame = false
    resetErrorRetryState()
    _uiState.update {
        it.copy(
            error = null,
            showRetryErrorAction = false,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            showLoadingOverlay = it.loadingOverlayEnabled,
        )
    }
    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        when (val step = SingleUseReplayPolicy.retryStep(
            reopened = port.reopen(contentId),
            noAnswer = context.getString(R.string.player_error_catchup_no_answer),
        )) {
            is SingleUseReplayPolicy.RetryStep.Open -> {
                currentStreamUrl = step.url
                _uiState.update { it.copy(currentStreamUrl = step.url) }
                releasePlayer()
                reinitializeCurrentStream()
            }
            is SingleUseReplayPolicy.RetryStep.End -> endCatchUpWithError(step.message)
        }
    }
    return true
}

/**
 * An ExoPlayer error during a replay that has not played yet. Asks the walk for the next URL shape
 * (it advances on transport-shaped failures only) and re-tunes in place; true when it did, so the
 * caller skips its own recovery. False hands the error to upstream's handling, which shows it.
 *
 * Exo only, like the fork player before it: libmpv errors carry no PlaybackException code for
 * CatchUpFailure to classify, so a replay on libmpv falls through to upstream's mpv error path.
 */
internal fun PlayerRuntimeController.advanceCatchUpDialect(errorCode: Int): Boolean {
    when (SingleUseReplayPolicy.routeError(isSingleUseCatchUp(), catchUpReplay.routeError())) {
        SingleUseReplayPolicy.ErrorRoute.END_WITH_RETRY ->
            if (failSingleUseCatchUp(startupHang = false)) return true
        SingleUseReplayPolicy.ErrorRoute.DIALECT_WALK -> Unit
        SingleUseReplayPolicy.ErrorRoute.PLAYER_RECOVERY -> return false
    }
    val next = catchUpPort?.onFailed(contentId, errorCode) ?: return false
    Log.i(PlayerRuntimeController.TAG, "CATCHUP_DIALECT_WALK: attempt failed (code=$errorCode); trying the next URL shape")
    // Same headers, new URL: a plain re-initialise, not a source switch, so nothing is persisted as a
    // reusable link and the sources UI is untouched.
    currentStreamUrl = next.url
    _uiState.update { it.copy(currentStreamUrl = next.url) }
    scheduleDeferredPlayerReinitialize(fromPositionMs = 0L, clearResumeProgress = true)
    return true
}

/**
 * Called from the progress loop on every tick. Follows the playhead of a replay that asked for a
 * continuation window (M3U): retitles as it crosses into the next show, asks for a new window when
 * this one runs out mid-show, rejoins live at the live edge, and says so when there is nothing left.
 * Every decision is [CatchUpReplayAdapter]'s (and [com.nuvio.tv.core.contracts.CatchUpContinuationPolicy]'s);
 * this only acts on it. A plain replay (or VOD) costs one boolean check per tick.
 */
internal fun PlayerRuntimeController.followCatchUpPlayhead(positionMs: Long) {
    if (!catchUpReplay.isCatchUp) return
    val port = catchUpPort ?: return
    if (!catchUpContinuationChecked) {
        catchUpContinuationChecked = true
        val continuation = port.continuation(contentId)
        continuation?.let {
            catchUpReplay.enableContinuation(it.programme.startMs, it.windowEndMs, it.programme.endMs)
        }
        // The continuation state lives exactly as long as this player (a single-use replay's mint was held at start).
        if (continuation != null) holdCatchUpSession()
    }
    val nowMs = System.currentTimeMillis()
    if (!catchUpReplay.needsLookup(positionMs, nowMs) || catchUpLookupJob?.isActive == true) return
    val playhead = catchUpReplay.playheadMs(positionMs)
    catchUpLookupJob = scope.launch {
        val next = try {
            port.programmeAt(contentId, playhead)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c // the player left: do not carry on to act on a dead session
        } catch (_: Throwable) {
            null
        }
        val now = System.currentTimeMillis()
        val pos = currentPlaybackPositionMs() ?: positionMs
        when (val action = catchUpReplay.onPlayhead(pos, now, next)) {
            CatchUpReplayAdapter.ContinuationAction.None -> Unit
            is CatchUpReplayAdapter.ContinuationAction.MoveTo ->
                _uiState.update { it.copy(title = action.programme.title) }
            is CatchUpReplayAdapter.ContinuationAction.Restart -> {
                val restart = port.restartAt(contentId, action.atMs)
                if (restart == null) {
                    showCatchUpEnded()
                } else {
                    Log.i(PlayerRuntimeController.TAG, "CATCHUP_CONTINUE: window ran out; opening a new one")
                    catchUpReplay.restarted(restart.startMs, restart.windowEndMs)
                    retuneCatchUp(restart.url)
                }
            }
            CatchUpReplayAdapter.ContinuationAction.GoLive -> {
                val live = port.liveUrl(contentId)
                if (live == null) {
                    showCatchUpEnded()
                } else {
                    Log.i(PlayerRuntimeController.TAG, "CATCHUP_CONTINUE: reached the live edge; rejoining live")
                    // Back to the channel's name: the programme the title carried is over.
                    _uiState.update { it.copy(title = it.title.substringBefore(" · ")) }
                    retuneCatchUp(live)
                }
            }
            CatchUpReplayAdapter.ContinuationAction.Ended -> showCatchUpEnded()
        }
    }
}

/** Same headers, new URL, from the start of it: a plain re-initialise, nothing persisted as a source. */
private fun PlayerRuntimeController.retuneCatchUp(url: String) {
    currentStreamUrl = url
    _uiState.update { it.copy(currentStreamUrl = url) }
    scheduleDeferredPlayerReinitialize(fromPositionMs = 0L, clearResumeProgress = true)
}

/** The window ran out with nothing left to replay: stop waiting and say so. */
private fun PlayerRuntimeController.showCatchUpEnded() {
    _uiState.update { it.copy(error = context.getString(R.string.catchup_recording_ended)) }
}
