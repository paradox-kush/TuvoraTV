package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.core.recommendations.LauncherAirPolicy
import com.nuvio.tv.core.recommendations.LauncherItem
import com.nuvio.tv.core.recommendations.LauncherRemovalPolicy
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ResumeRankPolicy

/** Everything [LauncherItemsPlanner] needs besides the cache snapshots. */
data class LauncherPlanSettings(
    val daysCap: Int,
    val dismissedNextUp: Set<String>,
    val useEpisodeThumbnails: Boolean,
    val sortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT,
    val includeNextUp: Boolean = true
)

/**
 * Decides what the TV home screen shows and in which order, from the Continue Watching cache alone
 * (no network). Pure over plain data classes so it tests without Android.
 *
 * - days cap, dismissed next-ups, de-duplication per content id (a resume wins over a next-up)
 * - aired state re-checked against the clock ([LauncherAirPolicy])
 * - items the viewer removed from the launcher stay out until played again ([LauncherRemovalPolicy])
 * - "Include next episodes" off drops next-ups and new episodes, keeping half-watched items
 * - order: "Pick up where I left off" ranks with [ResumeRankPolicy]; any other order keeps recency,
 *   because an explicit choice always wins
 */
object LauncherItemsPlanner {

    fun plan(
        inProgress: List<CachedInProgressItem>,
        nextUp: List<CachedNextUpItem>,
        settings: LauncherPlanSettings,
        nowMs: Long,
        removed: Map<String, Long> = emptyMap()
    ): List<LauncherItem> {
        val cutoffMs = if (settings.daysCap == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) {
            null
        } else {
            nowMs - settings.daysCap.toLong() * 24L * 60L * 60L * 1000L
        }

        val resumes = inProgress.filter { cutoffMs == null || it.lastWatched >= cutoffMs }
        val resumeContentIds = resumes.mapTo(mutableSetOf()) { it.contentId }

        val nextUps = if (!settings.includeNextUp) {
            emptyList()
        } else {
            nextUp
                .filter { LauncherAirPolicy.hasAired(it.releaseTimestamp, it.hasAired, nowMs) }
                .filter { cutoffMs == null || it.lastWatched >= cutoffMs }
                .filter { nextUpDismissKey(it) !in settings.dismissedNextUp }
                .filter { it.contentId !in resumeContentIds }
        }

        val candidates = buildList {
            resumes.forEach { item ->
                add(
                    Planned(
                        item = LauncherItem(item.toWatchProgress(settings.useEpisodeThumbnails), ResumeRankPolicy.Kind.Resume),
                        sortKey = item.lastWatched,
                        lastEngagedAtMs = item.lastWatched,
                        releasedAtMs = null,
                        fraction = item.resumeFraction()
                    )
                )
            }
            nextUps.forEach { item ->
                val alert = LauncherAirPolicy.isReleaseAlert(
                    cachedIsReleaseAlert = item.isReleaseAlert,
                    aired = true,
                    releaseTimestamp = item.releaseTimestamp,
                    lastWatchedMs = item.lastWatched,
                    nowMs = nowMs
                )
                add(
                    Planned(
                        item = LauncherItem(
                            progress = item.toWatchProgress(settings.useEpisodeThumbnails),
                            kind = if (alert) ResumeRankPolicy.Kind.NewEpisode else ResumeRankPolicy.Kind.NextUp
                        ),
                        sortKey = item.sortTimestamp,
                        // The seed episode's watch time, not the release time sortTimestamp may carry.
                        lastEngagedAtMs = item.lastWatched,
                        releasedAtMs = item.releaseTimestamp,
                        fraction = null
                    )
                )
            }
        }.filter { planned ->
            val progress = planned.item.progress
            LauncherRemovalPolicy.mayPublish(launcherKey(progress), removed, progress.lastWatched)
        }

        val ordered = if (settings.sortMode == ContinueWatchingSortMode.RESUME_FIRST) {
            orderByResumeRank(candidates, nowMs)
        } else {
            candidates.sortedByDescending { it.sortKey }.map { it.item }
        }

        val unique = ordered.distinctBy { it.progress.contentId }
        return if (settings.sortMode == ContinueWatchingSortMode.RESUME_FIRST) {
            withDescendingEngagement(unique)
        } else {
            unique
        }
    }

    private class Planned(
        val item: LauncherItem,
        val sortKey: Long,
        val lastEngagedAtMs: Long,
        val releasedAtMs: Long?,
        val fraction: Double?
    )

    private fun orderByResumeRank(candidates: List<Planned>, nowMs: Long): List<LauncherItem> {
        val keyed = candidates.mapIndexed { index, planned -> "${launcherKey(planned.item.progress)}#$index" to planned }
        val byKey = keyed.toMap()
        val order = ResumeRankPolicy.rank(
            keyed.map { (key, planned) ->
                ResumeRankPolicy.Candidate(
                    key = key,
                    kind = planned.item.kind,
                    lastEngagedAtMs = planned.lastEngagedAtMs,
                    progressFraction = planned.fraction,
                    releasedAtMs = planned.releasedAtMs,
                    // The cache is written by this device's own playback.
                    playedOnThisDevice = planned.item.kind == ResumeRankPolicy.Kind.Resume
                )
            },
            nowMs
        )
        return order.map { byKey.getValue(it).item }
    }

    /**
     * The launcher orders the Watch Next row by last-engagement time, not by insertion. To show the
     * rank, hand each item an engagement time that never exceeds its real one and strictly
     * decreases down the list. Derived only from real times, so it is stable between reconciles
     * (an unchanged list never re-writes the provider).
     */
    internal fun withDescendingEngagement(items: List<LauncherItem>): List<LauncherItem> {
        var previous: Long? = null
        return items.map { item ->
            val engagement = previous?.let { minOf(item.progress.lastWatched, it - 1) } ?: item.progress.lastWatched
            previous = engagement
            item.copy(engagementTimeMs = engagement)
        }
    }

    /** Same identity the preview channel and the Watch Next id are built from (without the `wn_`). */
    internal fun launcherKey(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null) {
            "${progress.contentId}_s${progress.season}e${progress.episode}"
        } else {
            progress.contentId
        }

    internal fun nextUpDismissKey(item: CachedNextUpItem): String = buildString {
        append(item.contentId)
        if (item.seedSeason != null) {
            append("_s${item.seedSeason}")
            if (item.seedEpisode != null) append("e${item.seedEpisode}")
        }
    }

    private fun CachedInProgressItem.resumeFraction(): Double? {
        val fraction = when {
            duration > 0L && position > 0L -> position.toDouble() / duration.toDouble()
            progressPercent != null -> progressPercent / 100.0
            else -> null
        }
        return fraction?.coerceIn(0.0, 1.0)
    }
}

/** Converts a [CachedInProgressItem] to [WatchProgress] for the launcher. */
internal fun CachedInProgressItem.toWatchProgress(useEpisodeThumbnails: Boolean): WatchProgress {
    val image = if (useEpisodeThumbnails) {
        episodeThumbnail ?: backdrop
    } else {
        backdrop ?: episodeThumbnail
    }
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = progressPercent
    )
}

/**
 * Converts a [CachedNextUpItem] to [WatchProgress] for the launcher. Next-up items have no playback
 * position, so they carry 0 / 0 and are published as NEXT or NEW, never CONTINUE.
 */
internal fun CachedNextUpItem.toWatchProgress(useEpisodeThumbnails: Boolean): WatchProgress {
    val image = if (useEpisodeThumbnails) {
        thumbnail ?: backdrop
    } else {
        backdrop ?: thumbnail
    }
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = 0,
        duration = 0,
        lastWatched = sortTimestamp
    )
}
