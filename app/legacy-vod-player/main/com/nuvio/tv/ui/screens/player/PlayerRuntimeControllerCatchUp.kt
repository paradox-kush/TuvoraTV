package com.nuvio.tv.ui.screens.player

import android.util.Log

/**
 * The player's half of catch-up: the clamped seek ceiling, and the dialect walk running on the REAL
 * playback attempt.
 *
 * The walk cannot live in the guide, because only the player knows whether a URL played; and it
 * cannot be resolved before launching, because deciding by probing would spend a provider
 * connection out of band — on a `max_connections=1` account that probe kicks the viewer's own live
 * stream, which is the same reason the .ts→HLS fix refused to probe.
 */

/**
 * The furthest a catch-up seek may reach, or null when this is not a catch-up playback (in which
 * case the caller's ordinary duration-based ceiling applies).
 *
 * Start-over plays a programme that has not finished airing, so its nominal end is in the future and
 * the panel has not archived that far — the ceiling is what has actually aired, less a guard.
 */
internal fun PlayerRuntimeController.catchUpSeekCeilingMs(): Long? {
    if (!isCatchUpPlayback) return null
    val start = catchUpProgrammeStartMs ?: return null
    val end = catchUpProgrammeEndMs ?: return null
    return CatchUpPlaybackPolicy.maxSeekMs(start, end, System.currentTimeMillis())
}

internal fun PlayerRuntimeController.clampCatchUpSeekMs(positionMs: Long): Long {
    val ceiling = catchUpSeekCeilingMs() ?: return positionMs
    return positionMs.coerceIn(0L, ceiling)
}

/**
 * A frame reached the screen, so this URL shape works for this panel — remember it so every later
 * replay on the account builds the right shape first time.
 */
internal fun PlayerRuntimeController.reportCatchUpPlayed() {
    if (!isCatchUpPlayback) return
    livePlayback.catchUp.onPlayed(contentId)
}

/**
 * Playback failed while replaying. Advances the dialect ladder on transport-shaped failures only
 * and re-tunes in place; returns true when it did, so the caller stops before its own recovery
 * ladder spends its budget on what is really a wrong-URL-shape problem.
 *
 * A false answer means the walk is over (or this is not catch-up), and the ordinary error handling
 * takes it from there — the viewer sees "this provider had no recording of this programme", not a
 * loop through eight URLs forever.
 */
internal fun PlayerRuntimeController.advanceCatchUpDialect(errorCode: Int): Boolean {
    if (!isCatchUpPlayback) return false
    val next = livePlayback.catchUp.onFailed(contentId, errorCode) ?: return false
    Log.i(
        PlayerRuntimeController.TAG,
        "CATCHUP_DIALECT_WALK: attempt failed (code=$errorCode); trying the next URL shape",
    )
    switchToLiveChannel(name = next.channelName, url = next.url)
    return true
}
