package com.nuvio.tv.ui.screens.player

/**
 * How far audio can be shifted on each engine.
 *
 * ExoPlayer delays audio by re-timing samples in [AudioDelayMediaSource], so it has to buffer
 * |delay| of media ahead of the playhead before it can start. Its buffer is capped by time
 * (45–120 s by device tier and mode) and, below that, by bytes — at high bitrates only a few
 * tens of seconds fit. Past that, ExoPlayer never becomes ready and sits on "Starting stream…".
 * The cap keeps the delay inside what the smallest buffer budget holds at typical bitrates;
 * real-world lip-sync needs (Bluetooth, a mis-muxed stream) are well under it.
 *
 * libmpv applies `audio-delay` natively, without buffering ahead, so it keeps the full range.
 */
internal object AudioDelayRangePolicy {
    private const val EXOPLAYER_MAX_ABS_DELAY_MS = 10_000

    fun maxAbsDelayMs(usingMpv: Boolean): Int =
        if (usingMpv) AUDIO_DELAY_MAX_MS else EXOPLAYER_MAX_ABS_DELAY_MS

    fun effectiveDelayMs(requestedMs: Int, usingMpv: Boolean): Int {
        val max = maxAbsDelayMs(usingMpv)
        return requestedMs.coerceIn(-max, max)
    }
}
