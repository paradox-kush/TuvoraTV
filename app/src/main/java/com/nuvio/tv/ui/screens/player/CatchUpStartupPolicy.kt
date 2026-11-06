package com.nuvio.tv.ui.screens.player

/**
 * How long a catch-up replay may wait for its first frame, per link and in total.
 *
 * A replay link that accepts the connection and never answers used to hold "Starting stream…" for
 * as long as the viewer waited: ExoPlayer's own retries re-ask the same dead link every ~17 s, and
 * the M3U/Xtream ladder only advanced on an ERROR, which never came. Every load ends Loaded, Empty
 * or Failed within a deadline (repo CLAUDE.md), so each link gets [LINK_DEADLINE_MS], a timeout is
 * treated as a transport failure (the ladder moves to the next candidate link), and the walk as a
 * whole is bounded by [TOTAL_BUDGET_MS] / [MAX_TIMEOUTS] — counted on the replay, never keyed on the
 * URL (each rung is a different string; see tuvora-playback 2.1).
 *
 * There is no live startup watchdog to reuse here: the replay runs in the VOD player, and the live
 * pipeline's policies (LivePlaybackFreezePolicy, the zap settle) guard a stream that is already playing.
 * mpv's own 30 s absolute watchdog stays as the engine-level backstop.
 */
internal object CatchUpStartupPolicy {
    const val LINK_DEADLINE_MS = 15_000L
    const val TOTAL_BUDGET_MS = 45_000L
    const val MAX_TIMEOUTS = 3

    /** What the player does when a link's deadline passes with no frame. */
    enum class Decision { ADVANCE, GIVE_UP }

    /** The wait for the link now loading, trimmed so the whole walk never overruns the budget. */
    fun deadlineMs(elapsedMs: Long): Long =
        minOf(LINK_DEADLINE_MS, TOTAL_BUDGET_MS - elapsedMs).coerceAtLeast(MIN_DEADLINE_MS)

    /** [timeoutCount] counts the timeout that just fired. */
    fun onTimeout(timeoutCount: Int, elapsedMs: Long): Decision =
        if (timeoutCount >= MAX_TIMEOUTS || elapsedMs >= TOTAL_BUDGET_MS) Decision.GIVE_UP else Decision.ADVANCE

    private const val MIN_DEADLINE_MS = 1_000L
}
