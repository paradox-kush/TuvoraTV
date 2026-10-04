package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.WatchProgress

internal fun resolveNextToWatchLatestProgress(
    latestProgress: WatchProgress?,
    progressEntries: Collection<WatchProgress>,
    isResumable: (WatchProgress) -> Boolean
): WatchProgress? {
    val resumeOverride = progressEntries
        .filter(isResumable)
        .maxWithOrNull(
            compareByDescending<WatchProgress> { it.lastWatched }
                .thenByDescending { it.season ?: 0 }
                .thenByDescending { it.episode ?: 0 }
        )
    // A half-watched episode wins only when it is at least as recent as the furthest progress.
    // An older partial must not drag the hero back past an episode finished since (S6E2 done,
    // "Resume S1 E2" offered — Onn device pass 2026-10-04).
    return when {
        resumeOverride != null && (
            latestProgress == null ||
                resumeOverride.lastWatched >= latestProgress.lastWatched
            ) -> resumeOverride
        else -> latestProgress
    }
}
