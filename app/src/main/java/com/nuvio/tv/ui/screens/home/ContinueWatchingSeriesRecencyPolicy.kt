package com.nuvio.tv.ui.screens.home

/**
 * B55: when a show has both a resume row and a next-up card, which one represents it in Continue
 * Watching. The fresher activity wins; a tie keeps the resume row (an explicit position beats a
 * derived suggestion). Mirrors Mobile/Desktop `buildHomeContinueWatchingItems`, which dedupes by
 * recency with progress entries first on a tie.
 */
internal object ContinueWatchingSeriesRecencyPolicy {
    fun nextUpSupersedesResume(resumeLastWatched: Long, nextUpSeedLastWatched: Long): Boolean =
        nextUpSeedLastWatched > resumeLastWatched
}
