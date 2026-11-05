package com.nuvio.tv.ui.screens.player

/**
 * libmpv VOD end and failure decisions for the legacy controller, the fork's take on upstream
 * #3319 (eof-reached), #3796 (auto-retry) and #4263 (no retry on permanent HTTP statuses).
 *
 * The fork runs `keep-open=yes`, so a finished file parks at its last frame without an end-file
 * event: mpv's `eof-reached` flag is the end signal, alongside the position check.
 */
internal object MpvVodRecoveryPolicy {
    const val MAX_AUTO_RETRIES = 2
    const val RETRY_DELAY_MS = 1_500L
    /** Healthy playback this long past the retry's resume point earns the retries back. */
    const val STABLE_PLAYBACK_RESET_MS = 5_000L
    private const val NEAR_END_MS = 500L

    fun isAtEnd(positionMs: Long, durationMs: Long, eofReached: Boolean): Boolean =
        eofReached || (durationMs > 0L && positionMs >= durationMs - NEAR_END_MS)

    /**
     * Whether a libmpv failure is worth reloading the same stream for. Live feeds have their own
     * reconnect ladder; [httpStatus] is null when mpv's log did not name one.
     */
    fun shouldAutoRetry(isLiveFeed: Boolean, retriesUsed: Int, httpStatus: Int?): Boolean =
        !isLiveFeed &&
            retriesUsed < MAX_AUTO_RETRIES &&
            (httpStatus == null || !isPermanentHttpStatus(httpStatus))

    fun shouldResetRetries(retriesUsed: Int, isPlaying: Boolean, positionMs: Long, resumedFromMs: Long): Boolean =
        retriesUsed > 0 && isPlaying && positionMs >= resumedFromMs + STABLE_PLAYBACK_RESET_MS
}
