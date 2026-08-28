package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodResumeEligibilityTest {
    @Test
    fun `visible sub two-percent checkpoint is restored`() {
        assertTrue(shouldRestoreSavedProgress(progress(positionMs = 54_000, durationMs = 5_786_656)))
    }

    @Test
    fun `remote percentage checkpoint without a position is restored`() {
        assertTrue(
            shouldRestoreSavedProgress(
                progress(positionMs = 0, durationMs = 0, explicitPercent = 1f),
            ),
        )
    }

    @Test
    fun `unstarted and completed entries are not restored`() {
        assertFalse(shouldRestoreSavedProgress(progress(positionMs = 0, durationMs = 5_000)))
        assertFalse(shouldRestoreSavedProgress(progress(positionMs = 4_500, durationMs = 5_000)))
    }

    private fun progress(
        positionMs: Long,
        durationMs: Long,
        explicitPercent: Float? = null,
    ) = WatchProgress(
        contentId = "content",
        contentType = "movie",
        name = "Movie",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "video",
        season = null,
        episode = null,
        episodeTitle = null,
        position = positionMs,
        duration = durationMs,
        lastWatched = 1,
        progressPercent = explicitPercent,
    )
}
