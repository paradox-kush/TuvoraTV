package com.nuvio.tv.ui.screens.player

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
     * The seek target clamped to the archived programme ([CatchUpPlaybackPolicy.clampSeekMs]: the
     * whole programme once it has finished, what has aired less the live-edge guard while it is still
     * airing). Unchanged for VOD, or when the guide did not pass both bounds.
     */
    fun seekTargetMs(targetMs: Long, nowMs: Long): Long {
        if (!isCatchUp) return targetMs
        val start = programmeStartMs ?: return targetMs
        val end = programmeEndMs ?: return targetMs
        return CatchUpPlaybackPolicy.clampSeekMs(targetMs, start, end, nowMs)
    }

    fun routeError(): ErrorRoute =
        if (isCatchUp && !played) ErrorRoute.DIALECT_WALK_FIRST else ErrorRoute.PLAYER_RECOVERY

    /** True exactly once per replay, on its first rendered frame: report the proven shape then. */
    fun onFirstFrame(): Boolean {
        if (!isCatchUp || played) return false
        played = true
        return true
    }
}
