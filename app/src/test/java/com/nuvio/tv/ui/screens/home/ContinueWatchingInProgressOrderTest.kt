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
        contentType: String = "series",
        position: Long = 10_000L
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
        position = position,
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

    // --- B55, the reporter's household: episodes stopped before the credits stay "in progress" ---

    /** 88% watched: below the 90% completion threshold, i.e. the credits were skipped. */
    private fun unfinished(show: String, episode: Int, lastWatched: Long) =
        ep(show, 1, episode, lastWatched, position = 39_600L)

    private fun finished(show: String, episode: Int, lastWatched: Long) =
        ep(show, 1, episode, lastWatched, position = 45_000L)

    private fun nextUp(show: String, episode: Int, seedLastWatched: Long) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = show, contentType = "series", name = show, poster = null, backdrop = null, logo = null,
            videoId = "${show}_s1e$episode", season = 1, episode = episode, episodeTitle = null, thumbnail = null,
            lastWatched = seedLastWatched, sortTimestamp = seedLastWatched,
        )
    )

    @Test
    fun `E1 to E6 all stopped before the credits surfaces E6`() {
        val rows = (1..6).map { unfinished("tt1", it, lastWatched = 1_000L * it) }.shuffled(java.util.Random(7))
        val out = continueWatchingInProgressRepresentatives(rows)
        assertEquals("one card for the show", 1, out.size)
        assertEquals("the latest episode being watched", 6, out.single().episode)
    }

    @Test
    fun `an earlier unfinished episode does not resurface after a later episode was finished`() {
        // E1 stopped before the credits, then E2 and E3 watched to the end.
        val rows = listOf(unfinished("tt1", 1, 1_000L), finished("tt1", 2, 2_000L), finished("tt1", 3, 3_000L))
        val out = continueWatchingInProgressRepresentatives(rows)
        assertEquals("no resume card for the show: next-up (E4) represents it", emptyList<WatchProgress>(), out)
    }

    @Test
    fun `a resume newer than the last finished episode still shows`() {
        val rows = listOf(finished("tt1", 2, 2_000L), unfinished("tt1", 3, 3_000L))
        val out = continueWatchingInProgressRepresentatives(rows)
        assertEquals(3, out.single().episode)
    }

    @Test
    fun `a stale resume on an earlier episode does not hide the newer next-up`() {
        // Provider path: the only in-progress row left is an old E1; E5 was finished later, so its
        // next-up (E6) is the show's real place.
        val stale = ContinueWatchingItem.InProgress(unfinished("tt1", 1, 1_000L))
        val out = mergeContinueWatchingItems(listOf(stale), listOf(nextUp("tt1", 6, seedLastWatched = 5_000L)))
        assertEquals("one card for the show", 1, out.size)
        val only = out.single()
        assertEquals("next-up E6 wins over the stale E1 resume", true, only is ContinueWatchingItem.NextUp && only.info.episode == 6)
    }

    @Test
    fun `a resume newer than the next-up seed keeps the resume card`() {
        val current = ContinueWatchingItem.InProgress(unfinished("tt1", 6, 6_000L))
        val out = mergeContinueWatchingItems(listOf(current), listOf(nextUp("tt1", 6, seedLastWatched = 5_000L)))
        assertEquals(listOf<ContinueWatchingItem>(current), out)
    }

    @Test
    fun `on a resume and next-up timestamp tie the resume card wins`() {
        val current = ContinueWatchingItem.InProgress(unfinished("tt1", 5, 5_000L))
        val out = mergeContinueWatchingItems(listOf(current), listOf(nextUp("tt1", 6, seedLastWatched = 5_000L)))
        assertEquals(listOf<ContinueWatchingItem>(current), out)
    }

    @Test
    fun `other shows keep their own cards`() {
        val a = ContinueWatchingItem.InProgress(unfinished("tt1", 1, 1_000L))
        val b = ContinueWatchingItem.InProgress(unfinished("tt2", 3, 4_000L))
        val out = mergeContinueWatchingItems(listOf(a, b), listOf(nextUp("tt1", 6, seedLastWatched = 5_000L)))
        assertEquals(2, out.size)
        assertEquals(setOf("tt1", "tt2"), out.map { item ->
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            }
        }.toSet())
    }
}
