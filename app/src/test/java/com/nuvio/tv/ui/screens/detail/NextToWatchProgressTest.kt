package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class NextToWatchProgressTest {

    @Test
    fun `resume half watched earlier season wins over completed later season mark`() {
        val s4Completed = progress(
            videoId = "s4e1",
            season = 4,
            episode = 1,
            position = 1000L,
            duration = 1000L,
            lastWatched = 1_000L,
            progressPercent = 100f
        )
        val s1Half = progress(
            videoId = "s1e2",
            season = 1,
            episode = 2,
            position = 500L,
            duration = 1000L,
            lastWatched = 2_000L,
            progressPercent = 50f
        )

        val anchored = resolveNextToWatchLatestProgress(
            latestProgress = s4Completed,
            progressEntries = listOf(s4Completed, s1Half),
            isResumable = { !it.isCompleted() && it.progressPercentage >= 0.02f }
        )

        assertEquals(s1Half, anchored)
    }

    @Test
    fun `furthest completed stays when there is no resumable progress`() {
        val s4Completed = progress(
            videoId = "s4e1",
            season = 4,
            episode = 1,
            position = 1000L,
            duration = 1000L,
            lastWatched = 1_000L,
            progressPercent = 100f
        )

        val anchored = resolveNextToWatchLatestProgress(
            latestProgress = s4Completed,
            progressEntries = listOf(s4Completed),
            isResumable = { !it.isCompleted() && it.progressPercentage >= 0.02f }
        )

        assertEquals(s4Completed, anchored)
    }

    // Sync-12 device pass (2026-10-04, Onn): after finishing S6E2 the detail hero offered
    // "Resume S1 E2" — an old half-watched episode — while Continue Watching said "Next Up S6 E3".
    // A stale partial must not override a more recent completed episode.
    @Test
    fun `completed episode newer than an old partial keeps next up on the completed one`() {
        val s6e2Completed = progress(
            videoId = "s6e2",
            season = 6,
            episode = 2,
            position = 1000L,
            duration = 1000L,
            lastWatched = 9_000L,
            progressPercent = 100f
        )
        val s1e2OldHalf = progress(
            videoId = "s1e2",
            season = 1,
            episode = 2,
            position = 500L,
            duration = 1000L,
            lastWatched = 1_000L,
            progressPercent = 50f
        )

        val anchored = resolveNextToWatchLatestProgress(
            latestProgress = s6e2Completed,
            progressEntries = listOf(s6e2Completed, s1e2OldHalf),
            isResumable = { !it.isCompleted() && it.progressPercentage >= 0.02f }
        )

        assertEquals("completed S6E2 is the most recent activity", s6e2Completed, anchored)
    }

    private fun progress(
        videoId: String,
        season: Int,
        episode: Int,
        position: Long,
        duration: Long,
        lastWatched: Long,
        progressPercent: Float
    ) = WatchProgress(
        contentId = "tt123",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = progressPercent
    )
}
