package com.nuvio.tv.ui.screens.player

/**
 * Detects a live stream that libmpv can seek inside its cache (upstream b4e47d6d6 / 583eae818).
 *
 * Such a stream reports `seekable`, so it never looks live, but its duration is only what has been
 * loaded so far and keeps growing while a VOD's stays put. Saving its position as resume progress
 * is wrong: resuming there seeks past the live window and nothing plays.
 *
 * Pure so it tests without mpv; the fork feeds it from the mpv progress tick (a lock-free shadow
 * read, never an mpv call on the main thread).
 */
internal object MpvLiveDurationPolicy {
    /** Growth past the first reading that marks a live stream (a VOD's duration only settles by a little). */
    const val LIVE_DURATION_GROWTH_MS = 10_000L

    /** First duration seen for [mediaKey] (0 = none yet) and whether it has grown since. */
    data class DurationGrowth(
        val mediaKey: String? = null,
        val firstDurationMs: Long = 0L,
        val grew: Boolean = false
    ) {
        /** True when [mediaKey] is the stream now playing and its duration has been growing. */
        fun isLiveFor(currentMediaKey: String?): Boolean = grew && mediaKey == currentMediaKey
    }

    /**
     * Readings taken before mpv reports the requested media are skipped ([fromRequestedMedia]
     * false): they can still be the previous file's. State starts over for a new [mediaKey].
     */
    fun nextDurationGrowth(
        previous: DurationGrowth,
        mediaKey: String?,
        durationMs: Long,
        fromRequestedMedia: Boolean
    ): DurationGrowth {
        val current = if (previous.mediaKey == mediaKey) previous else DurationGrowth(mediaKey)
        if (!fromRequestedMedia || durationMs <= 0L || current.grew) return current
        if (current.firstDurationMs == 0L) return current.copy(firstDurationMs = durationMs)
        return if (durationMs > current.firstDurationMs + LIVE_DURATION_GROWTH_MS) current.copy(grew = true) else current
    }
}
