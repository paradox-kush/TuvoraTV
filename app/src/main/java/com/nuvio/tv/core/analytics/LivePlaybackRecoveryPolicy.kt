package com.nuvio.tv.core.analytics

/**
 * When to re-prepare a frozen live channel.
 *
 * Nothing in Media3 re-prepares a live source: a clean upstream close ends the stream for good
 * (`ProgressiveMediaPeriod.onLoadCompleted` marks loading finished), and a dead socket that
 * never errors just stops advancing. Both leave a frozen picture that only a manual channel
 * change clears, which is the reconnect this policy automates.
 *
 * Callers may choose an indefinitely retrying live-feed lane. It still caps the cadence at the
 * final backoff and keeps one in-flight connection; only the attempt count is unbounded. Catch-up
 * and other finite playback keep the terminal budget.
 */
internal object LivePlaybackRecoveryPolicy {

    const val MAX_ATTEMPTS = 5

    /**
     * Even the indefinitely-retrying live lane stops after this many reconnects IN A ROW that never
     * rendered a frame. The attempt count alone cannot tell "flaky but playing" (must never give
     * up) from "the provider closes every stream within a second" (will never come back): the
     * caller counts attempts since the last real playback and passes it as
     * [Input.attemptsWithoutPlayback]. Giving up ends in a visible Failed state with Retry;
     * only the viewer (or a fresh tune) starts a new ladder.
     */
    const val MAX_ATTEMPTS_WITHOUT_PLAYBACK = 5

    /**
     * How many attempts try the cheap video-only reset before escalating to a full re-resolve.
     *
     * Only [LivePlaybackFreezePolicy.Kind.VIDEO_STALLED] uses these: audio is still arriving, so
     * the connection is demonstrably alive and the fault is downstream of it. Re-resolving the
     * source there is both useless and expensive — live links carry single-use tokens (Stalker
     * `create_link`) and providers cap concurrent connections, so a reconnect can cost the viewer
     * the stream that was still half-working. StreamVault reaches the same conclusion from the
     * other direction: once a live provider session is playing, it refuses every automatic
     * recovery except a surface swap.
     */
    const val VIDEO_RESET_ATTEMPTS = 2

    /**
     * Delay before attempt N (0-based). The first reconnect is immediate — the freeze has
     * already been visible for [LivePlaybackFreezePolicy.FREEZE_THRESHOLD_MS], and an upstream
     * that closed cleanly is usually ready to serve again straight away. Later attempts back
     * off so a provider that is rate-limiting or briefly down is not hammered.
     */
    private val BACKOFF_MS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 20_000L)

    data class Input(
        val attempts: Int,
        val sinceLastAttemptMs: Long,
        /** Which freeze is being recovered; decides whether the connection is worth touching. */
        val kind: LivePlaybackFreezePolicy.Kind = LivePlaybackFreezePolicy.Kind.STALLED,
        val maxAttempts: Int = MAX_ATTEMPTS,
        val videoResetAttempts: Int = VIDEO_RESET_ATTEMPTS,
        /** Live linear TV has no legitimate EOF; keep reconnecting at the capped cadence. */
        val retryIndefinitely: Boolean = false,
        /** Reconnects since a frame last rendered; see [MAX_ATTEMPTS_WITHOUT_PLAYBACK]. */
        val attemptsWithoutPlayback: Int = 0,
    )

    sealed class Decision {
        /** Backoff has not elapsed; leave the current attempt alone. */
        data object Wait : Decision()

        /**
         * Reinitialise the video pipeline only, leaving the connection untouched — mpv's
         * `video-reload`, or re-attaching the renderer's surface on ExoPlayer. Costs the provider
         * nothing, so it is always tried first when audio proves the stream is still arriving.
         */
        data object ResetVideo : Decision()

        /** Re-prepare the stream now. */
        data object Reconnect : Decision()

        /** Out of attempts — surface the failure rather than spinning forever. */
        data object GiveUp : Decision()
    }

    /** Delay before attempt N (0-based), capped at the last step. Lets callers schedule the next try. */
    fun backoffMs(attempts: Int): Long = BACKOFF_MS.getOrElse(attempts.coerceAtLeast(0)) { BACKOFF_MS.last() }

    fun evaluate(input: Input): Decision {
        if (!input.retryIndefinitely && input.attempts >= input.maxAttempts) return Decision.GiveUp
        if (input.retryIndefinitely && input.attemptsWithoutPlayback >= MAX_ATTEMPTS_WITHOUT_PLAYBACK) {
            return Decision.GiveUp
        }
        val backoffMs = backoffMs(input.attempts)
        if (input.sinceLastAttemptMs < backoffMs) return Decision.Wait
        // A dead pipe (ENDED / STALLED) can only be fixed upstream, so it goes straight to a
        // re-resolve. A live picture with live audio is the opposite case — see VIDEO_RESET_ATTEMPTS.
        if (input.kind == LivePlaybackFreezePolicy.Kind.VIDEO_STALLED &&
            input.attempts < input.videoResetAttempts
        ) {
            return Decision.ResetVideo
        }
        return Decision.Reconnect
    }
}
