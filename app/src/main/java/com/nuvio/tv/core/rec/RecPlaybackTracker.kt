package com.nuvio.tv.core.rec

import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton

/** Bound on remembered playbacks, so a long session cannot grow this without limit. */
private const val MAX_TRACKED_PLAYBACKS = 64

/**
 * Turns the watch-progress stream into the recommendation stream's playback events.
 *
 * WHY THIS MATTERS MORE THAN IMPRESSIONS: the recommender's strongest variant is the one trained
 * with a like/dislike signal, and MovieLens supplies that as star ratings. Tuvora has no ratings
 * — but how far someone watched is the same information. Bailing at 25% is a low rating;
 * reaching 90% is a high one. These four crossings are what let a Tuvora-trained model use the
 * architecture's best lever at all.
 *
 * Derived, never re-measured: percentages come from the position/duration the player already
 * reports to [WatchProgress], so the events can never disagree with Continue Watching.
 *
 * Progress is monotonic per playback: a bucket fires once, and seeking backwards then forwards
 * does not re-fire it. Rewatching after leaving and returning is a new playback and does.
 */
@Singleton
class RecPlaybackTracker @Inject constructor(
    private val logger: RecEventLogger,
) {
    private data class PlaybackState(
        var started: Boolean = false,
        var highestBucket: Int = 0,
        var completed: Boolean = false,
    )

    private val states = LinkedHashMap<String, PlaybackState>()

    /**
     * Call on every watch-progress save. Cheap and idempotent — the vast majority of calls do
     * nothing but compare an integer.
     */
    fun onProgress(progress: WatchProgress) {
        runCatching { track(progress) }
    }

    /** A playback finished or the user left the player: the next play of this item starts fresh. */
    fun onPlaybackEnded(progress: WatchProgress?) {
        progress ?: return
        runCatching {
            synchronized(states) { states.remove(key(progress)) }
        }
    }

    private fun track(progress: WatchProgress) {
        val percent = percentOf(progress) ?: return
        val key = key(progress)

        val state = synchronized(states) {
            if (states.size >= MAX_TRACKED_PLAYBACKS && !states.containsKey(key)) {
                states.remove(states.keys.first())
            }
            states.getOrPut(key) { PlaybackState() }
        }

        val contentType = recContentTypeOf(progress)
        val itemId = progress.contentId

        val fireStart: Boolean
        val crossed: List<Int>
        val fireComplete: Boolean
        synchronized(state) {
            fireStart = !state.started
            state.started = true

            crossed = REC_PROGRESS_BUCKETS.filter { it > state.highestBucket && percent >= it }
            if (crossed.isNotEmpty()) state.highestBucket = crossed.max()

            fireComplete = !state.completed && percent >= 90
            if (fireComplete) state.completed = true
        }

        if (fireStart) {
            logger.log(playbackEvent(RecEventType.PLAY_START, itemId, contentType, progress))
        }
        for (bucket in crossed) {
            logger.log(
                playbackEvent(RecEventType.PLAY_PROGRESS, itemId, contentType, progress)
                    .copy(progressPct = bucket)
            )
        }
        if (fireComplete) {
            logger.log(playbackEvent(RecEventType.PLAY_COMPLETE, itemId, contentType, progress))
        }
    }

    private fun playbackEvent(
        eventType: String,
        itemId: String,
        contentType: String,
        progress: WatchProgress,
    ): RecEvent = RecEvent(
        eventType = eventType,
        // Playback has no shelf behind it by the time it reaches here; the row context that led
        // to it lives on the click event, joinable by (session, item).
        surface = RecSurface.DETAILS,
        contentType = contentType,
        itemId = itemId,
        tmdbId = itemId.removePrefix("tmdb:").takeIf { it != itemId }?.toIntOrNull(),
        season = progress.season,
        episode = progress.episode,
    )

    /**
     * Prefer the remote-reported percentage (Trakt/Simkl playback) when present, because for
     * those sources position/duration may be zero.
     */
    private fun percentOf(progress: WatchProgress): Int? {
        progress.progressPercent?.let { return it.toInt().coerceIn(0, 100) }
        if (progress.duration <= 0L || progress.position < 0L) return null
        return ((progress.position * 100) / progress.duration).toInt().coerceIn(0, 100)
    }

    /**
     * An episode is logged as `episode` carrying the SHOW's id plus season/episode, so training
     * can roll up to the show while keeping per-episode completion.
     */
    private fun recContentTypeOf(progress: WatchProgress): String = when {
        progress.season != null || progress.episode != null -> RecContentType.EPISODE
        progress.contentType.equals("series", ignoreCase = true) -> RecContentType.SERIES
        progress.contentType.equals("tv", ignoreCase = true) -> RecContentType.LIVE
        progress.contentType.equals("channel", ignoreCase = true) -> RecContentType.LIVE
        else -> RecContentType.MOVIE
    }

    private fun key(progress: WatchProgress): String =
        "${progress.contentId}|${progress.season ?: -1}|${progress.episode ?: -1}"
}
