package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingLiveFilterTest {

    private fun progress(contentId: String, contentType: String) = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = "",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 24_340L,
        duration = 51_500L,
        lastWatched = 1L
    )

    @Test
    fun `live progress is detected by content type and by xtream live id`() {
        assertTrue(isLiveProgress(progress("xtream:http://host|user:live:42", "live")))
        assertTrue(isLiveProgress(progress("some-live-channel", "live")))
        assertTrue(isLiveProgress(progress("xtream:http://host|user:live:42", "tv")))
        assertFalse(isLiveProgress(progress("tt6966692", "movie")))
        assertFalse(isLiveProgress(progress("xtream:http://host|user:vod:42", "movie")))
    }
}
