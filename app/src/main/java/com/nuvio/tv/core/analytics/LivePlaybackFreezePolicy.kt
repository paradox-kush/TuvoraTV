package com.nuvio.tv.core.analytics

import kotlin.math.abs

/**
 * Pure freeze/recovery decisions for live channels, extracted from
 * [LivePlaybackFreezeReporter] so JVM tests mirror production 1:1.
 *
 * A live channel can wedge without the player ever reporting an error, so a frozen picture
 * is only observable as "the playhead stopped moving while we were supposed to be playing".
 * Two shapes matter and this policy separates them:
 *
 *  - [Kind.ENDED]  — the player reached ENDED. A live channel has no end, so this is always a
 *    fault: the upstream connection closed cleanly and Media3 treated the clean EOF as the
 *    content finishing (`ProgressiveMediaPeriod.onLoadCompleted` sets `loadingFinished`).
 *  - [Kind.STALLED] — the playhead stopped and so did the buffered edge. Both matter: a
 *    rebuffer where the buffered edge still advances is a live source the player simply has
 *    not caught up with, and reconnecting that would throw away a stream that was about to
 *    resume. Only when neither moves is the pipe actually dead.
 */
internal object LivePlaybackFreezePolicy {

    /**
     * How long both the playhead and the buffered edge must stand still before this counts as
     * a freeze. Also gates the reconnect in [LivePlaybackRecoveryPolicy], so it is the time a
     * viewer spends looking at a frozen picture before the app acts — short enough not to be
     * endured, long enough to clear an ordinary rebuffer first.
     */
    const val FREEZE_THRESHOLD_MS = 6_000L

    /** Position jitter (either direction) that does not count as movement. */
    const val POSITION_TOLERANCE_MS = 250L

    enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

    enum class Kind { ENDED, STALLED }

    data class Input(
        val state: PlaybackState,
        /** playWhenReady and the user has not paused: the picture is meant to be moving. */
        val wantsToPlay: Boolean,
        val positionMs: Long,
        /** Position at the last tick where the playhead actually moved. */
        val lastAdvancedPositionMs: Long,
        /** Time since the playhead last moved. */
        val sinceLastAdvanceMs: Long,
        val bufferedPositionMs: Long,
        /** Buffered edge at the last tick where it actually moved. */
        val lastAdvancedBufferedPositionMs: Long,
        /** Time since the buffered edge last moved. */
        val sinceBufferedAdvanceMs: Long,
        /** Whether a freeze is already open and awaiting resolution. */
        val freezeActive: Boolean,
        val thresholdMs: Long = FREEZE_THRESHOLD_MS,
        val positionToleranceMs: Long = POSITION_TOLERANCE_MS,
    )

    sealed class Decision {
        /** Playing normally, or not in a state where a freeze is meaningful. */
        data object Idle : Decision()

        /** A freeze just began. */
        data class Start(val kind: Kind) : Decision()

        /** A freeze is open and still unresolved. */
        data class Continue(val kind: Kind) : Decision()

        /** An open freeze cleared. */
        data object Recover : Decision()
    }

    fun evaluate(input: Input): Decision {
        // Either direction counts as movement: Media3's deferred retry for an unknown-length
        // source resets the playhead to 0 and signals a discontinuity, so a backwards jump is
        // a reconnect, not a stall. Treating it as "no movement" would wedge this policy in a
        // permanent false freeze for the rest of the channel.
        val moved = abs(input.positionMs - input.lastAdvancedPositionMs) > input.positionToleranceMs
        val bufferMoved =
            abs(input.bufferedPositionMs - input.lastAdvancedBufferedPositionMs) > input.positionToleranceMs

        if (input.freezeActive) {
            // ENDED never resolves by itself; only a re-prepare can clear it.
            if (input.state == PlaybackState.ENDED) return Decision.Continue(Kind.ENDED)
            return if (moved) Decision.Recover else Decision.Continue(Kind.STALLED)
        }
        if (!input.wantsToPlay) return Decision.Idle
        if (input.state == PlaybackState.ENDED) return Decision.Start(Kind.ENDED)
        if (moved) return Decision.Idle
        // Buffered edge still advancing: the connection is alive and the player is filling up.
        // Reconnecting here would discard a stream that was about to resume on its own.
        if (bufferMoved) return Decision.Idle
        if (input.sinceLastAdvanceMs >= input.thresholdMs &&
            input.sinceBufferedAdvanceMs >= input.thresholdMs
        ) {
            return Decision.Start(Kind.STALLED)
        }
        return Decision.Idle
    }
}
