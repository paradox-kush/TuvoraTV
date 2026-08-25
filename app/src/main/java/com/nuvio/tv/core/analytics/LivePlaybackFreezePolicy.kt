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
 *  - [Kind.VIDEO_STALLED] — the picture died while audio kept playing. This is the shape users
 *    report most ("video freezes, audio keeps going, I have to leave the channel"), and it was
 *    invisible here until 2026-08-14: audio advances the playhead, so the `moved` check below
 *    read it as healthy playback and returned [Decision.Idle] every time. Detecting it needs a
 *    signal from the video output specifically, which is why [Input.videoProgressTicks] exists.
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

    /**
     * How long a channel must have been playing before a video-only freeze can be reported.
     *
     * Applies to [Kind.VIDEO_STALLED] alone. The video-output signal needs a few seconds of real
     * playback before it means anything — mpv's is an average over recent frame durations, and a
     * hardware decoder can legitimately render nothing while it warms up. Without this every
     * channel open reports a freeze.
     */
    const val VIDEO_STARTUP_GRACE_MS = 8_000L

    /**
     * Shorter video-stall threshold used when the buffer is demonstrably healthy (rev 3).
     *
     * Fleet data (30d, `video_stalled`) showed the buffered edge sitting 24-39s ahead of the playhead
     * at the moment of freeze on every platform — the bytes are already here, the render path just
     * stopped. When the buffer proves it is not a rebuffer, there is nothing to wait for: report the
     * freeze at ~2.5s so recovery starts sooner (ExoPlayer users were sitting ~8s on a frozen picture
     * before the reconnect even fired). The full [FREEZE_THRESHOLD_MS] still governs the thin-buffer
     * case, where a stall might yet be a rebuffer that resumes on its own.
     *
     * Lowered 2500→2000 (Fix 3, 2026-08-25): fleet data showed recovered ExoPlayer video-stalls
     * still sitting ~18s frozen before the picture returned, so with a healthy buffer proving it is
     * a render-path stall, 2s of a dead picture is already unambiguous — start recovery sooner. The
     * new gave_up / frozen_ms-by-device telemetry will guide any further tuning.
     */
    const val FAST_VIDEO_STALL_MS = 2_000L

    /**
     * How much buffered-ahead media counts as "healthy" for [FAST_VIDEO_STALL_MS]. Below this the
     * full threshold applies. Engines that do not expose a buffered edge (mpv reports the playhead as
     * the buffered position) compute zero here and so always take the full-threshold path — safe,
     * since without the signal we cannot prove it is not a rebuffer.
     */
    const val FAST_MIN_BUFFERED_AHEAD_MS = 3_000L

    enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

    enum class Kind { ENDED, STALLED, VIDEO_STALLED }

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
        /**
         * Which kind is open, when [freezeActive]. A video-only freeze cannot be resolved by the
         * playhead — that never stopped — so the resolution test depends on which shape is open.
         */
        val activeKind: Kind? = null,
        /**
         * Whether the current track actually has a picture. Live radio stations are a normal part
         * of every IPTV lineup and render no frames by design; without this gate they would all
         * report a permanent video freeze.
         */
        val hasVideoTrack: Boolean = false,
        /**
         * Monotonic evidence that the video output is alive — ExoPlayer's rendered-frame callback
         * and mpv's measured output FPS, normalised by each engine into a counter that stands
         * still exactly when the picture does. Unlike the playhead, audio cannot advance it.
         */
        val videoProgressTicks: Long = 0L,
        /** Ticks at the last sample where the video output actually advanced. */
        val lastAdvancedVideoTicks: Long = 0L,
        /** Time since the video output last advanced. */
        val sinceVideoAdvanceMs: Long = 0L,
        /** Time since this channel started playing; gates [VIDEO_STARTUP_GRACE_MS]. */
        val sincePlaybackStartMs: Long = Long.MAX_VALUE,
        val thresholdMs: Long = FREEZE_THRESHOLD_MS,
        val positionToleranceMs: Long = POSITION_TOLERANCE_MS,
        val videoStartupGraceMs: Long = VIDEO_STARTUP_GRACE_MS,
        val fastVideoStallMs: Long = FAST_VIDEO_STALL_MS,
        val fastMinBufferedAheadMs: Long = FAST_MIN_BUFFERED_AHEAD_MS,
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
        // Ticks are a plain counter, not a timeline: any change at all is a rendered frame.
        val videoMoved = input.videoProgressTicks != input.lastAdvancedVideoTicks

        if (input.freezeActive) {
            // ENDED never resolves by itself; only a re-prepare can clear it.
            if (input.state == PlaybackState.ENDED) return Decision.Continue(Kind.ENDED)
            // A video-only freeze is resolved by frames returning. Testing `moved` here would
            // clear it instantly and forever, because audio never stopped moving the playhead.
            if (input.activeKind == Kind.VIDEO_STALLED) {
                return if (videoMoved) Decision.Recover else Decision.Continue(Kind.VIDEO_STALLED)
            }
            return if (moved) Decision.Recover else Decision.Continue(Kind.STALLED)
        }
        if (!input.wantsToPlay) return Decision.Idle
        if (input.state == PlaybackState.ENDED) return Decision.Start(Kind.ENDED)

        // Audio alive, picture dead. This MUST be tested before the `moved` early-out below:
        // that check is precisely what hid this failure, since audio keeps the playhead moving.
        // Requiring `moved` is what separates it from a STALLED pipe, where nothing moves at all.
        //
        // A healthy buffered edge proves the bytes are already here and the render path — not the
        // network — is what stopped, so a dead picture can be called at the fast threshold instead
        // of waiting out a possible rebuffer. mpv reports the playhead as the buffered position, so
        // bufferedAheadMs is 0 there and it always takes the full-threshold path.
        val bufferedAheadMs = (input.bufferedPositionMs - input.positionMs).coerceAtLeast(0L)
        val videoStallThresholdMs =
            if (bufferedAheadMs >= input.fastMinBufferedAheadMs) input.fastVideoStallMs else input.thresholdMs
        if (moved &&
            input.hasVideoTrack &&
            !videoMoved &&
            input.sincePlaybackStartMs >= input.videoStartupGraceMs &&
            input.sinceVideoAdvanceMs >= videoStallThresholdMs
        ) {
            return Decision.Start(Kind.VIDEO_STALLED)
        }

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
