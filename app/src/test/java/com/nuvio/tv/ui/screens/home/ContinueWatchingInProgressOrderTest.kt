package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B55: Continue Watching must not surface an earlier episode than the one actually being watched.
 * The per-show collapse in [deduplicateInProgress] picks the most-recently-watched episode, and on
 * a lastWatched tie (batch mark-as-watched, or a tracking provider's null-timestamp fallback) it
 * must prefer the highest season/episode — matching the collapser already used in
 * WatchProgressPreferences and the Mobile/Desktop SeriesContinuity comparator.
 */
class ContinueWatchingInProgressOrderTest {

    private fun ep(
        show: String,
        season: Int?,
        episode: Int?,
        lastWatched: Long,
        contentType: String = "series"
    ) = WatchProgress(
        contentId = show,
        contentType = contentType,
        name = show,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "${show}_s${season}e${episode}",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 10_000L,
        duration = 45_000L,
        lastWatched = lastWatched
    )

    private fun survivor(items: List<WatchProgress>): WatchProgress {
        val out = deduplicateInProgress(items)
        assertEquals("one row per show", 1, out.size)
        return out.first()
    }

    @Test
    fun `on equal lastWatched the higher episode wins regardless of input order`() {
        // Input order deliberately puts the earlier episode first — the old distinctBy kept it.
        val s = survivor(listOf(ep("tt1", 1, 2, 1000L), ep("tt1", 1, 5, 1000L)))
        assertEquals(1, s.season)
        assertEquals(5, s.episode)
    }

    @Test
    fun `on equal lastWatched the higher season wins across seasons`() {
        val s = survivor(listOf(ep("tt1", 3, 1, 1000L), ep("tt1", 1, 20, 1000L)))
        assertEquals(3, s.season)
        assertEquals(1, s.episode)
    }

    @Test
    fun `a genuinely newer timestamp still wins even on a lower episode`() {
        // Recency dominates; the tiebreak only applies when lastWatched ties.
        val s = survivor(listOf(ep("tt1", 1, 2, 2000L), ep("tt1", 1, 9, 1000L)))
        assertEquals(1, s.season)
        assertEquals(2, s.episode)
        assertEquals(2000L, s.lastWatched)
    }

    @Test
    fun `missing season and episode are treated as zero on a tie`() {
        // Existing semantics: null season/episode sort as 0, so a real S1E1 wins the tie.
        val s = survivor(listOf(ep("tt1", null, null, 1000L), ep("tt1", 1, 1, 1000L)))
        assertEquals(1, s.season)
        assertEquals(1, s.episode)
    }

    @Test
    fun `distinct shows are each represented`() {
        val out = deduplicateInProgress(listOf(ep("tt1", 1, 5, 1000L), ep("tt2", 1, 3, 900L)))
        assertEquals(2, out.size)
        assertEquals(setOf("tt1", "tt2"), out.map { it.contentId }.toSet())
    }
}
