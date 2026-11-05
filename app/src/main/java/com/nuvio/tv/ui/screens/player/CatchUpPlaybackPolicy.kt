package com.nuvio.tv.ui.screens.player

/**
 * Catch-up is a recording, not a live feed — and the player disagrees by default.
 *
 * `isLiveContentId()` matches any id containing `:live:`, and a replay id keeps that segment, so a
 * recording inherits live-only behaviour: backgrounding resumes at the live edge instead of where
 * you were, and the freeze reporter arms against something that cannot freeze. The fix is a flag
 * BESIDE the content type rather than a new
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

    /**
     * The seek bar of a replay that asked for a continuation window: it spans the programme being
     * watched, not the whole window. Positions are the player's own, which count from the start of
     * the window ([streamStartMs], wall clock).
     *
     * @property originMs where the programme starts in the player's timeline (bar position 0)
     * @property lengthMs how far the bar reaches: the programme, stopping [LIVE_EDGE_GUARD_MS] short of live while it airs
     * @property programmeLengthMs the programme's own length, for the "Ends at" label (not guarded)
     */
    data class ReplayBar(val originMs: Long, val lengthMs: Long, val programmeLengthMs: Long)

    /**
     * [programmeEndMs] at or before the window start (a stale programme after a restart) falls back
     * to [windowEndMs]: the bar then spans the rest of the window rather than collapsing to nothing.
     */
    fun replayBar(
        streamStartMs: Long,
        windowEndMs: Long,
        programmeStartMs: Long,
        programmeEndMs: Long,
        nowMs: Long,
    ): ReplayBar {
        val origin = (programmeStartMs - streamStartMs).coerceAtLeast(0L)
        val start = streamStartMs + origin
        val end = if (programmeEndMs > start) programmeEndMs else windowEndMs
        return ReplayBar(
            originMs = origin,
            lengthMs = maxSeekMs(start, end, nowMs),
            programmeLengthMs = (end - start).coerceAtLeast(0L),
        )
    }

    /** Player position -> bar position. */
    fun barPositionMs(playerMs: Long, bar: ReplayBar): Long = (playerMs - bar.originMs).coerceIn(0L, bar.lengthMs)

    /** Bar position -> player position. */
    fun playerPositionMs(barMs: Long, bar: ReplayBar): Long = bar.originMs + barMs.coerceAtLeast(0L)

    /** The bar's length; an unknown duration (<= 0) stays unknown so the bar still falls back to NO_SCRUB. */
    fun barDurationMs(playerDurationMs: Long, bar: ReplayBar): Long =
        if (playerDurationMs <= 0L) playerDurationMs
        else minOf(bar.lengthMs, (playerDurationMs - bar.originMs).coerceAtLeast(0L))

    /** A player-space seek target clamped to the bar. */
    fun clampPlayerSeekMs(playerMs: Long, bar: ReplayBar): Long =
        playerMs.coerceIn(bar.originMs, bar.originMs + bar.lengthMs)
}
