package com.nuvio.tv.core.recommendations

import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.ui.screens.home.ResumeRankPolicy

/**
 * Which Android TV "Play Next" row type an item is published as.
 *
 * - A resume with a real position and duration is `CONTINUE`.
 * - An aired next episode is `NEXT`.
 * - A new episode of a show the viewer follows ([CachedNextUpItem.isReleaseAlert]) is `NEW`.
 * - Never `WATCHLIST`: nothing here is a saved-for-later item.
 *
 * `CONTINUE` requires a playback position and duration. A resume without them (a percent-only sync
 * from a remote source) is published as `NEXT` instead of with an invented duration.
 *
 * The values are the verified `TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_*` constants
 * (CONTINUE 0, NEXT 1, NEW 2, WATCHLIST 3 in tvprovider 1.0.0 and android.media.tv.TvContract).
 */
object WatchNextTypePolicy {

    enum class WatchNextType(val contractValue: Int) {
        CONTINUE(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE),
        NEXT(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT),
        NEW(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW)
    }

    fun typeFor(kind: ResumeRankPolicy.Kind, positionMs: Long, durationMs: Long): WatchNextType = when (kind) {
        ResumeRankPolicy.Kind.Resume ->
            if (durationMs > 0L && positionMs > 0L) WatchNextType.CONTINUE else WatchNextType.NEXT
        ResumeRankPolicy.Kind.NextUp -> WatchNextType.NEXT
        ResumeRankPolicy.Kind.NewEpisode -> WatchNextType.NEW
    }

    /**
     * Playback position to publish: the stored one, or derived from the percent when the duration is
     * real. 0 when it cannot be known.
     */
    fun resolvePositionMs(positionMs: Long, durationMs: Long, progressPercent: Float?): Long = when {
        durationMs <= 0L -> 0L
        positionMs > 0L -> positionMs
        progressPercent != null -> (progressPercent / 100f * durationMs).toLong().coerceAtLeast(0L)
        else -> 0L
    }
}
