package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.flow.update

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
