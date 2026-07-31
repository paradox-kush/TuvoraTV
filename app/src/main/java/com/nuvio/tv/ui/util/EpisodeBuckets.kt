package com.nuvio.tv.ui.util

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.Video

/** A contiguous slice of one season's episodes, e.g. "801-850". */
@Immutable
data class EpisodeBucket(
    val label: String,
    val fromIndex: Int,
    val untilIndex: Int,
) {
    val size: Int get() = untilIndex - fromIndex
    fun contains(index: Int): Boolean = index in fromIndex until untilIndex
}

/** Seasons at or below this stay a single row — bucketing would only add noise. */
const val EPISODE_BUCKET_THRESHOLD = 60
const val EPISODE_BUCKET_SIZE = 50

/**
 * Splits a season into range buckets, or returns empty when it is short enough to browse whole.
 *
 * Slices are cut by POSITION, never by arithmetic on episode numbers: IPTV providers hand back
 * numbers that are sparse, duplicated, or quoted strings, so `episode / size` would produce ragged
 * and empty ranges. Labels still read from the real numbers at each slice's edges, falling back to
 * positions when an episode has no number at all.
 */
fun episodeBuckets(
    episodes: List<Video>,
    bucketSize: Int = EPISODE_BUCKET_SIZE,
    threshold: Int = EPISODE_BUCKET_THRESHOLD,
): List<EpisodeBucket> {
    if (bucketSize < 1 || episodes.size <= threshold) return emptyList()
    return episodes.indices.step(bucketSize).map { start ->
        val end = minOf(start + bucketSize, episodes.size)
        val first = episodes[start].episode ?: (start + 1)
        val last = episodes[end - 1].episode ?: end
        EpisodeBucket(
            label = if (first == last) first.toString() else "$first-$last",
            fromIndex = start,
            untilIndex = end,
        )
    }
}

/** The bucket holding [episodeIndex], defaulting to the first so a picker always has a selection. */
fun List<EpisodeBucket>.bucketContaining(episodeIndex: Int): EpisodeBucket? =
    firstOrNull { it.contains(episodeIndex) } ?: firstOrNull()

/** The slice of [episodes] the given bucket covers, or everything when there is no bucketing. */
fun List<Video>.sliceForBucket(bucket: EpisodeBucket?): List<Video> =
    if (bucket == null) this else subList(bucket.fromIndex, bucket.untilIndex)
