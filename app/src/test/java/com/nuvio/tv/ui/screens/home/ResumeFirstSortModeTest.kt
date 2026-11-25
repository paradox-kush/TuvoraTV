package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** JUnit argument order: assertEquals(message, expected, actual). */
class ResumeFirstSortModeTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun inProgress(id: String, ageMs: Long, percent: Float) = ContinueWatchingItem.InProgress(
        WatchProgress(
            contentId = id,
            contentType = "movie",
            name = id,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = id,
            season = null,
            episode = null,
            episodeTitle = null,
            position = (percent * 1_000f).toLong(),
            duration = 100_000L,
            lastWatched = now - ageMs,
            progressPercent = percent
        )
    )

    private fun nextUp(id: String, ageMs: Long, hasAired: Boolean = true) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = id,
            contentType = "series",
            name = id,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$id:1:2",
            season = 1,
            episode = 2,
            episodeTitle = null,
            thumbnail = null,
            hasAired = hasAired,
            lastWatched = now - ageMs,
            sortTimestamp = now - ageMs
        )
    )

    private fun ids(items: List<ContinueWatchingItem>) = items.map {
        when (it) {
            is ContinueWatchingItem.InProgress -> it.progress.contentId
            is ContinueWatchingItem.NextUp -> it.info.contentId
        }
    }

    private val mixed = listOf(
        nextUp("next-2h", 2 * hour),
        inProgress("film-1d", day, 50f),
        inProgress("nearly-done", 3 * hour, 97f),
        nextUp("next-30h", 30 * hour)
    )

    // Case 11: "Most recent first" is exactly today's order, newest engagement first.
    @Test
    fun `most recent first is exactly the old recency order`() {
        val sorted = sortContinueWatchingItems(mixed, ContinueWatchingSortMode.DEFAULT, now)
        assertEquals(
            "recency only",
            listOf("next-2h", "nearly-done", "film-1d", "next-30h"),
            ids(sorted)
        )
    }

    @Test
    fun `split upcoming keeps the recency order too`() {
        val sorted = sortContinueWatchingItems(mixed, ContinueWatchingSortMode.SPLIT_UPCOMING, now)
        assertEquals(
            "recency only",
            listOf("next-2h", "nearly-done", "film-1d", "next-30h"),
            ids(sorted)
        )
    }

    @Test
    fun `pick up where I left off puts the half-watched film first and sinks the nearly done one`() {
        val sorted = sortContinueWatchingItems(mixed, ContinueWatchingSortMode.RESUME_FIRST, now)
        assertEquals(
            "resume first",
            listOf("film-1d", "next-2h", "next-30h", "nearly-done"),
            ids(sorted)
        )
        assertNotEquals(
            "differs from recency",
            ids(sortContinueWatchingItems(mixed, ContinueWatchingSortMode.DEFAULT, now)),
            ids(sorted)
        )
    }

    @Test
    fun `unaired next-ups stay after aired items in resume first`() {
        val items = listOf(
            nextUp("unaired", hour, hasAired = false),
            inProgress("old-film", 20 * day, 40f)
        )
        val sorted = sortContinueWatchingItems(items, ContinueWatchingSortMode.RESUME_FIRST, now)
        assertEquals("aired before unaired", listOf("old-film", "unaired"), ids(sorted))
    }

    @Test
    fun `resume first keeps every item and drops none`() {
        val sorted = sortContinueWatchingItems(mixed, ContinueWatchingSortMode.RESUME_FIRST, now)
        assertEquals("same items", mixed.toSet(), sorted.toSet())
        assertEquals("same count", mixed.size, sorted.size)
    }
}
