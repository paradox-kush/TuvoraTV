package com.nuvio.tv.ui.screens.player

/**
 * Catch-up is a recording, not a live feed — and the player disagrees by default.
 *
 * `isLiveContentId()` matches any id containing `:live:`, and a replay id keeps that segment, so a
 * recording inherits every live-only behaviour: up/down zaps to another channel mid-programme,
 * backgrounding resumes at the live edge instead of where you were, and the freeze reporter arms
 * against something that cannot freeze. The fix is a flag BESIDE the content type rather than a new
 * content type — a new type would have to be taught to every one of the dozen places that already
 * key on "live", and would silently break the ones we missed.
 */
object CatchUpPlaybackPolicy {

    /**
     * How far short of live a seek stops. A catch-up stream's right edge is a fragment the panel has
     * not archived yet; iptvsimple refuses seeks within one to two minutes of live for the same
     * reason.
     */
    const val LIVE_EDGE_GUARD_MS = 2 * 60 * 1000L

    /** Whether the progress bar gets a handle the viewer can drag. */
    enum class Scrub {
        /** A duration and a seekable source: a real bar. */
        SEEKABLE,

        /** A progressive stream with no duration: a flat bar and an honest caption, not a dead handle. */
        NO_SCRUB,
    }

    /** Up/down must not change channel in the middle of a recording. */
    fun allowsChannelZap(isLive: Boolean, isCatchUpPlayback: Boolean): Boolean =
        isLive && !isCatchUpPlayback

    /** Coming back from the background resumes where the viewer was, not at the live edge. */
    fun allowsLiveEdgeResume(isLive: Boolean, isCatchUpPlayback: Boolean): Boolean =
        isLive && !isCatchUpPlayback

    /**
     * A recording that stops advancing has ended, not frozen. Arming the watchdog here reports
     * phantom freezes AND runs a recovery ladder whose last rung reloads the stream from the live
     * edge — which would throw the viewer out of the programme they were watching.
     */
    fun armsFreezeWatchdog(isLive: Boolean, isCatchUpPlayback: Boolean): Boolean =
        isLive && !isCatchUpPlayback

    /**
     * Whether to draw a draggable bar. Not our decision to make: a panel answering `.m3u8` sends a
     * playlist with every segment's duration and scrubs; a panel answering `.ts` sends a
     * progressive stream with no duration and does not. Same programme, same app, different
     * provider — so both have to render without either looking broken.
     */
    fun scrubFor(durationMs: Long, playerSaysSeekable: Boolean): Scrub =
        if (playerSaysSeekable && durationMs > 0L) Scrub.SEEKABLE else Scrub.NO_SCRUB

    /**
     * The furthest position a catch-up seek may reach, as an offset into the programme.
     *
     * A finished programme is entirely in the past, so all of it is fair game. Start-over plays one
     * that is still airing, and its nominal end is in the future — seeking there asks for a fragment
     * that does not exist yet, so the ceiling is what has actually aired, less the guard. Never
     * negative: a programme thirty seconds old has nothing to seek into, not a ceiling behind zero.
     */
    fun maxSeekMs(programmeStartMs: Long, programmeEndMs: Long, nowMs: Long): Long =
        (minOf(programmeEndMs, nowMs - LIVE_EDGE_GUARD_MS) - programmeStartMs).coerceAtLeast(0L)

    fun clampSeekMs(targetMs: Long, programmeStartMs: Long, programmeEndMs: Long, nowMs: Long): Long =
        targetMs.coerceIn(0L, maxSeekMs(programmeStartMs, programmeEndMs, nowMs))
}
