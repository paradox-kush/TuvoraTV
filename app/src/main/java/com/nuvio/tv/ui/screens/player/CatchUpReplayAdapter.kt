package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.contracts.CatchUpProgramme
import com.nuvio.tv.core.contracts.CatchUpContinuationPolicy

/**
 * Fork-only: what upstream's VOD player must do differently when it plays an IPTV catch-up replay
 * (`contentType="live", isCatchUp=true`, launched from the guide or Sports). Pure, so it tests without
 * a player (audit §7a option i; moving catch-up onto the clean live pipeline is later work).
 *
 * One instance per player session. The controller asks it three questions:
 * - [seekTargetMs]: where a seek may land, given what the panel has actually archived;
 * - [routeError]: whether a playback error goes to the URL-dialect walk before upstream's recovery;
 * - [onFirstFrame]: whether to report the working URL shape now.
 *
 * The URL shapes themselves, their order and the "transport-shaped failures only" rule live in
 * core/iptv (CatchUpDialectWalk / CatchUpPlaybackCoordinator) behind the
 * [com.nuvio.tv.core.contracts.CatchUpPlaybackPort]; this adapter only decides *when* to ask it.
 *
 * A replay that asked for a continuation window (M3U) is also followed: [onPlayhead] turns
 * [CatchUpContinuationPolicy]'s decision into the one thing the controller should do about it.
 */
internal class CatchUpReplayAdapter(
    val isCatchUp: Boolean,
    private val programmeStartMs: Long?,
    private val programmeEndMs: Long?,
) {
    enum class ErrorRoute {
        /**
         * Ask the dialect walk first. Before a replay has played, an error most often means this
         * panel wants another URL shape; upstream's retry rungs would replay the same wrong shape and
         * spend the account's connection doing it.
         */
        DIALECT_WALK_FIRST,

        /** Not catch-up, or the shape already played: upstream's own error handling owns it. */
        PLAYER_RECOVERY,
    }

    private var played = false

    /**
     * A restarted window (continuation) or a Retry on a replay that already played is waiting for ITS first
     * frame: the startup deadline applies again, but the proven shape is not reported twice and errors
     * keep going to player recovery ([played] stays true).
     */
    private var awaitingRestartFrame = false

    /** Set by [enableContinuation]; null for a replay that ends with its programme. */
    private var continuation: Continuation? = null

    private class Continuation(
        var sessionStartMs: Long,
        var windowEndMs: Long,
        var playing: CatchUpContinuationPolicy.Programme,
        var lastLookupMs: Long = Long.MIN_VALUE,
        /** A terminal decision (or a restart in flight) was handed out; nothing more until [restarted]. */
        var settled: Boolean = false,
    )

    /** What the controller does about the playhead. Each terminal action is handed out once. */
    sealed interface ContinuationAction {
        data object None : ContinuationAction

        /** The playhead entered [programme]: retitle. */
        data class MoveTo(val programme: CatchUpProgramme) : ContinuationAction

        /** The window ran out at [atMs] with more to replay: request a new window from there. */
        data class Restart(val atMs: Long) : ContinuationAction

        /** The window ran out at the live edge: rejoin the live stream. */
        data object GoLive : ContinuationAction

        /** The window ran out with nothing left to replay: say so. */
        data object Ended : ContinuationAction
    }

    /**
     * Turns continuation on: the replay was opened at [sessionStartMs] (wall clock, the stream's
     * position 0), asked for a window ending at [windowEndMs], and opened on a programme ending at [playingEndMs].
     */
    fun enableContinuation(sessionStartMs: Long, windowEndMs: Long, playingEndMs: Long) {
        continuation = Continuation(
            sessionStartMs, windowEndMs, CatchUpContinuationPolicy.Programme(sessionStartMs, playingEndMs),
        )
    }

    /** A new window is open: positions count from [sessionStartMs] again. */
    fun restarted(sessionStartMs: Long, windowEndMs: Long) {
        val c = continuation ?: return
        c.sessionStartMs = sessionStartMs
        c.windowEndMs = windowEndMs
        c.settled = false
        c.lastLookupMs = Long.MIN_VALUE
        resetStartupBudget()
    }

    /** The replay is over (live rejoined or ended): it is no longer a recording to clamp or follow. */
    private var leftCatchUp = false

    fun playheadMs(positionMs: Long): Long = (continuation?.sessionStartMs ?: programmeStartMs ?: 0L) + positionMs

    /**
     * Whether the playhead is where asking the guide for the next programme is worth a read: at or past
     * the end of the one playing, not settled, and not more often than every [LOOKUP_RETRY_MS].
     */
    fun needsLookup(positionMs: Long, nowMs: Long): Boolean {
        val c = continuation ?: return false
        if (c.settled || leftCatchUp) return false
        if (playheadMs(positionMs) < c.playing.endMs && playheadMs(positionMs) < c.windowEndMs) return false
        return c.lastLookupMs == Long.MIN_VALUE || nowMs - c.lastLookupMs >= LOOKUP_RETRY_MS
    }

    /** [next]: the programme covering the playhead, else the first after the one playing; null when unknown. */
    fun onPlayhead(positionMs: Long, nowMs: Long, next: CatchUpProgramme?): ContinuationAction {
        val c = continuation ?: return ContinuationAction.None
        if (c.settled || leftCatchUp) return ContinuationAction.None
        c.lastLookupMs = nowMs
        val decision = CatchUpContinuationPolicy.decide(
            playing = c.playing,
            next = next?.let { CatchUpContinuationPolicy.Programme(it.startMs, it.endMs) },
            playheadMs = playheadMs(positionMs),
            windowEndMs = c.windowEndMs,
            nowMs = nowMs,
        )
        return when (decision) {
            CatchUpContinuationPolicy.Decision.Stay -> ContinuationAction.None
            is CatchUpContinuationPolicy.Decision.MoveToNext -> {
                c.playing = decision.programme
                next?.let { ContinuationAction.MoveTo(it) } ?: ContinuationAction.None
            }
            is CatchUpContinuationPolicy.Decision.StartNewReplay -> {
                c.settled = true
                ContinuationAction.Restart(decision.atMs)
            }
            CatchUpContinuationPolicy.Decision.GoLive -> {
                c.settled = true
                leftCatchUp = true
                ContinuationAction.GoLive
            }
            CatchUpContinuationPolicy.Decision.Ended -> {
                c.settled = true
                ContinuationAction.Ended
            }
        }
    }

    /**
     * The seek target clamped to the archived programme ([CatchUpPlaybackPolicy.clampSeekMs]: the
     * whole programme once it has finished, what has aired less the live-edge guard while it is still
     * airing). Unchanged for VOD, or when the guide did not pass both bounds.
     */
    fun seekTargetMs(targetMs: Long, nowMs: Long): Long {
        if (!isCatchUp || leftCatchUp) return targetMs
        // A continuing replay is clamped to the programme being watched (the bar's span), not the
        // whole window it asked for; skipping on re-bases when the playhead moves to the next show.
        replayBar(nowMs)?.let { return CatchUpPlaybackPolicy.clampPlayerSeekMs(targetMs, it) }
        val start = programmeStartMs ?: return targetMs
        val end = programmeEndMs ?: return targetMs
        return CatchUpPlaybackPolicy.clampSeekMs(targetMs, start, end, nowMs)
    }

    /** The programme-spanning bar of a continuing replay; null for any other playback (no re-basing). */
    private fun replayBar(nowMs: Long): CatchUpPlaybackPolicy.ReplayBar? {
        val c = continuation ?: return null
        if (!isCatchUp || leftCatchUp) return null
        return CatchUpPlaybackPolicy.replayBar(c.sessionStartMs, c.windowEndMs, c.playing.startMs, c.playing.endMs, nowMs)
    }

    /** What the seek bar shows for a player reading; [playerDurationMs] unknown (<= 0) stays unknown. */
    data class BarReading(val positionMs: Long, val durationMs: Long, val bufferedMs: Long, val programmeLengthMs: Long?)

    fun barReading(playerPositionMs: Long, playerDurationMs: Long, playerBufferedMs: Long, nowMs: Long): BarReading {
        val bar = replayBar(nowMs)
            ?: return BarReading(playerPositionMs, playerDurationMs, playerBufferedMs, null)
        return BarReading(
            positionMs = CatchUpPlaybackPolicy.barPositionMs(playerPositionMs, bar),
            durationMs = CatchUpPlaybackPolicy.barDurationMs(playerDurationMs, bar),
            bufferedMs = CatchUpPlaybackPolicy.barPositionMs(playerBufferedMs, bar),
            programmeLengthMs = bar.programmeLengthMs,
        )
    }

    /** A position on the bar -> the player's own; unchanged when there is no re-based bar. */
    fun playerPositionMs(barPositionMs: Long, nowMs: Long): Long =
        replayBar(nowMs)?.let { CatchUpPlaybackPolicy.playerPositionMs(barPositionMs, it) } ?: barPositionMs

    fun routeError(): ErrorRoute =
        if (isCatchUp && !leftCatchUp && !played) ErrorRoute.DIALECT_WALK_FIRST else ErrorRoute.PLAYER_RECOVERY

    private var startupBeganMs = Long.MIN_VALUE
    private var startupTimeouts = 0

    /**
     * Called each time a link starts loading. How long to wait for its first frame, or null when no
     * deadline applies (not a replay, or it already played). The clock starts at the FIRST call and is
     * not restarted by later links, engine failovers or recovery rungs — the total stays bounded.
     */
    fun startupDeadlineMs(nowMs: Long): Long? {
        if (!isCatchUp || (played && !awaitingRestartFrame) || leftCatchUp) return null
        if (startupBeganMs == Long.MIN_VALUE) startupBeganMs = nowMs
        return CatchUpStartupPolicy.deadlineMs(nowMs - startupBeganMs)
    }

    /** A link's deadline passed with no frame: advance the ladder, or give up (the count lives here, not on the URL). */
    fun onStartupTimeout(nowMs: Long): CatchUpStartupPolicy.Decision {
        startupTimeouts++
        val began = if (startupBeganMs == Long.MIN_VALUE) nowMs else startupBeganMs
        return CatchUpStartupPolicy.onTimeout(startupTimeouts, nowMs - began)
    }

    /** The viewer pressed Retry: a fresh budget for a fresh attempt. */
    fun resetStartupBudget() {
        startupBeganMs = Long.MIN_VALUE
        startupTimeouts = 0
        if (isCatchUp && played) awaitingRestartFrame = true
    }

    /**
     * Retry of a single-use replay (a Stalker archive) opens a fresh link, which is a new attempt in
     * every respect: it has not played, and it gets a fresh startup deadline and budget.
     */
    fun onRelinked() {
        played = false
        awaitingRestartFrame = false
        resetStartupBudget()
    }

    /** True exactly once per replay, on its first rendered frame: report the proven shape then. */
    fun onFirstFrame(): Boolean {
        if (!isCatchUp) return false
        awaitingRestartFrame = false
        if (played) return false
        played = true
        return true
    }

    private companion object {
        /** A guide that has nothing for the playhead is asked again no sooner than this. */
        const val LOOKUP_RETRY_MS = 5_000L
    }
}
