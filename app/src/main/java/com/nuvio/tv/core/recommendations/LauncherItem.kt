package com.nuvio.tv.core.recommendations

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ResumeRankPolicy

/**
 * One item bound for the TV launcher: the progress row, what kind of item it is (decides the Play
 * Next row type) and the engagement time the launcher should sort by.
 *
 * The Watch Next row is ordered by the launcher from last-engagement time, so a rank that differs
 * from recency is expressed through [engagementTimeMs] (see [LauncherItemsPlanner]).
 */
data class LauncherItem(
    val progress: WatchProgress,
    val kind: ResumeRankPolicy.Kind,
    val engagementTimeMs: Long = progress.lastWatched
)
