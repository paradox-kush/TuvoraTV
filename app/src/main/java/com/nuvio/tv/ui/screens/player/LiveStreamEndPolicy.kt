package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.player.mpv.MpvEndFileReason

/**
 * What a typed mpv END_FILE means for the channel on screen, and how the live reconnect ladder
 * books it. Pure, so the decision is tested without mpv, the player, or the network.
 */
internal object LiveStreamEndPolicy {

    enum class Action {
        /** Zapping, teardown, redirect — or a VOD EOF, which the progress loop owns. */
        IGNORE,

        /** Enter the live reconnect ladder ([onLiveStreamEnded]). */
        RECONNECT_LIVE,

        /** The ordinary failure path: fresh-link retry, engine failover, then error + Retry. */
        FAIL_PLAYBACK,
    }

    enum class Bookkeeping {
        /** Start a fresh freeze watch and attempt budget for a new incident. */
        ARM_WATCHER,

        /** A freeze is already open: keep its record and attempt count spanning this end. */
        KEEP_INCIDENT,

        /** A reconnect's rebuilt player ended without rendering: that attempt is spent. */
        END_UNRENDERED_ATTEMPT,
    }

    fun onMpvEndFile(
        reason: MpvEndFileReason,
        isLiveFeed: Boolean,
        hasRenderedFirstFrame: Boolean,
        liveRecoveryInFlight: Boolean,
    ): Action = when (reason) {
        MpvEndFileReason.STOP,
        MpvEndFileReason.QUIT,
        MpvEndFileReason.REDIRECT,
        MpvEndFileReason.UNKNOWN -> Action.IGNORE

        // "Playback started" is a real first frame (TV's hasRenderedFirstFrame), never the end
        // event itself. A channel that never rendered has nothing to reconnect TO: the live ladder
        // retries indefinitely, so routing it there spun "Reconnecting…" forever instead of
        // showing the startup error + Retry. A reconnect's own rebuild is the exception — that
        // channel already played this dwell, so its rebuilt player ending stays on the ladder.
        MpvEndFileReason.EOF -> when {
            !isLiveFeed -> Action.IGNORE
            hasRenderedFirstFrame || liveRecoveryInFlight -> Action.RECONNECT_LIVE
            else -> Action.FAIL_PLAYBACK
        }

        MpvEndFileReason.ERROR ->
            if (isLiveFeed && hasRenderedFirstFrame) Action.RECONNECT_LIVE else Action.FAIL_PLAYBACK
    }

    /**
     * The freeze watcher arms only after real playback started. Arming also resets the attempt
     * count and backoff, so arming on an unrendered end let every failed rebuild restart the
     * ladder at attempt 0 with no delay — the reconnect never backed off and never stopped.
     */
    fun bookkeepingOnLiveEnd(hasRenderedFirstFrame: Boolean, isFreezeOpen: Boolean): Bookkeeping = when {
        !hasRenderedFirstFrame -> Bookkeeping.END_UNRENDERED_ATTEMPT
        isFreezeOpen -> Bookkeeping.KEEP_INCIDENT
        else -> Bookkeeping.ARM_WATCHER
    }
}
