package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress

/**
 * Adapter between Continue Watching items and [ResumeRankPolicy]. Kept apart from the policy so the
 * policy stays free of app types.
 */
internal fun resumeFirstOrder(
    items: List<ContinueWatchingItem>,
    nowMs: Long
): List<ContinueWatchingItem> {
    if (items.size < 2) return items
    // The index makes the key unique even for blank content ids; it only ever breaks exact ties.
    val keyed = items.mapIndexed { index, item -> resumeRankKey(item, index) to item }
    val byKey = keyed.toMap()
    val order = ResumeRankPolicy.rank(
        keyed.map { (key, item) -> item.toResumeCandidate(key) },
        nowMs
    )
    return order.map { byKey.getValue(it) }
}

private fun resumeRankKey(item: ContinueWatchingItem, index: Int): String = when (item) {
    is ContinueWatchingItem.InProgress -> "r|${item.progress.contentId}|${item.progress.videoId}|$index"
    is ContinueWatchingItem.NextUp -> "n|${item.info.contentId}|${item.info.videoId}|$index"
}

internal fun ContinueWatchingItem.toResumeCandidate(key: String): ResumeRankPolicy.Candidate = when (this) {
    is ContinueWatchingItem.InProgress -> ResumeRankPolicy.Candidate(
        key = key,
        kind = ResumeRankPolicy.Kind.Resume,
        lastEngagedAtMs = progress.lastWatched,
        progressFraction = progress.resumeFraction(),
        // WatchProgressEntry has no device field on TV; locally written progress is the closest proxy.
        playedOnThisDevice = progress.source == WatchProgress.SOURCE_LOCAL
    )
    is ContinueWatchingItem.NextUp -> ResumeRankPolicy.Candidate(
        key = key,
        kind = if (info.isReleaseAlert) ResumeRankPolicy.Kind.NewEpisode else ResumeRankPolicy.Kind.NextUp,
        lastEngagedAtMs = info.lastWatched,
        releasedAtMs = info.releaseTimestamp,
        aired = info.hasAired
    )
}

internal fun WatchProgress.resumeFraction(): Double? {
    val fraction = when {
        duration > 0L && position > 0L -> position.toDouble() / duration.toDouble()
        progressPercent != null -> progressPercent / 100.0
        else -> null
    }
    return fraction?.coerceIn(0.0, 1.0)
}
