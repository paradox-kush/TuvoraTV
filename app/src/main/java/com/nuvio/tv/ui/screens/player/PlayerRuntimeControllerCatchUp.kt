package com.nuvio.tv.ui.screens.player

import android.util.Log
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

/** First rendered frame: remember the working URL shape for this account (once per replay). */
internal fun PlayerRuntimeController.reportCatchUpFirstFrame() {
    if (catchUpReplay.onFirstFrame()) catchUpPort?.onPlayed(contentId)
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
    if (catchUpReplay.routeError() != CatchUpReplayAdapter.ErrorRoute.DIALECT_WALK_FIRST) return false
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
        port.continuation(contentId)?.let {
            catchUpReplay.enableContinuation(it.programme.startMs, it.windowEndMs, it.programme.endMs)
            // The continuation state lives exactly as long as this player.
            catchUpEffects.adopt(port.hold(contentId))
        }
    }
    val nowMs = System.currentTimeMillis()
    if (!catchUpReplay.needsLookup(positionMs, nowMs) || catchUpLookupJob?.isActive == true) return
    val playhead = catchUpReplay.playheadMs(positionMs)
    catchUpLookupJob = scope.launch {
        val next = runCatching { port.programmeAt(contentId, playhead) }.getOrNull()
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
