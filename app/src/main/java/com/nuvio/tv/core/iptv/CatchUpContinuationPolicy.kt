package com.nuvio.tv.core.iptv

/*
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), LiveTvViewModel.followCatchup().
 * The decision is lifted out of the player/EPG state so it tests without either.
 */

/**
 * A catch-up replay is asked for as one window (the show plus up to [CONTINUE_MS] after it) so
 * the next shows follow on seamlessly. This decides, as the playhead moves, whether the title and
 * seek bar stay, move to the next programme, or a new replay has to be requested.
 */
object CatchUpContinuationPolicy {

    /** How far past the end of a show a replay keeps going (into the next shows). */
    const val CONTINUE_MS = 3L * 60 * 60 * 1000

    /** A replay never asks for the last stretch before now: it has not been recorded yet. */
    private const val LIVE_MARGIN_MS = 30_000L

    /** A playhead this close to now has nothing left to replay. */
    private const val LIVE_EDGE_MS = 60_000L

    data class Programme(val startMs: Long, val endMs: Long)

    sealed interface Decision {
        data object Stay : Decision
        data class MoveToNext(val programme: Programme) : Decision
        data class StartNewReplay(val atMs: Long) : Decision
    }

    /** End of the window to request for [programme]: at least its end, up to [CONTINUE_MS] more, never past aired. */
    fun replayWindowEndMs(programme: Programme, now: Long): Long =
        maxOf(programme.endMs, minOf(now - LIVE_MARGIN_MS, programme.endMs + CONTINUE_MS))

    /**
     * @param playing the programme the replay session is showing
     * @param next the programme covering the playhead, else the first one after [playing]; null when unknown
     * @param playheadMs absolute (wall-clock) position of the player
     * @param windowEndMs the end of the window that was requested ([replayWindowEndMs])
     */
    fun decide(
        playing: Programme,
        next: Programme?,
        playheadMs: Long,
        windowEndMs: Long,
        nowMs: Long,
    ): Decision {
        if (playheadMs >= windowEndMs) {
            // The window ran out: start a new one right where we are, if there is a programme there
            // and the spot is not at the live edge.
            val covered = (playheadMs >= playing.startMs && playheadMs < playing.endMs) ||
                (next != null && playheadMs >= next.startMs && playheadMs < next.endMs)
            return if (covered && playheadMs <= nowMs - LIVE_EDGE_MS) Decision.StartNewReplay(playheadMs) else Decision.Stay
        }
        if (playheadMs < playing.endMs) return Decision.Stay
        if (next == null || next.startMs == playing.startMs) return Decision.Stay
        return Decision.MoveToNext(next)
    }
}
