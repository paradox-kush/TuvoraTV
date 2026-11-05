// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarStorageTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")

    @Test fun aSnapshotRoundTripsPerProfileAndAClearDropsAWriteThatStartedBefore() = runBlocking {
        val dir = Files.createTempDirectory("calendar-storage").toFile()
        try {
            val storage = CalendarStorage({ dir }, { now.toEpochMilli() })
            val record = CalendarTitleRecord(
                "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"), name = "Harbour",
                sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 2, airDate = "2026-11-10")))
            )
            val snapshot = CalendarSnapshot(
                titles = listOf(record),
                seenReminders = mapOf("old" to now.minus(Duration.ofDays(40)).toEpochMilli(), "new" to now.toEpochMilli())
            )
            assertTrue("written", storage.save(1, snapshot, storage.generation(), force = true))
            val loaded = storage.load(1)
            assertEquals("titles", listOf("Harbour"), loaded.titles.map { it.name })
            assertEquals("entries", listOf("S1E2"), loaded.titles.single().entries.map { it.slot })
            assertEquals("seen pruned after 30 days", setOf("new"), loaded.seenReminders.keys)
            assertTrue("other profile empty", storage.load(2).titles.isEmpty())

            val generation = storage.generation()
            storage.removeProfile(1)
            assertFalse("write after a clear is dropped", storage.save(1, snapshot, generation, force = true))
            assertTrue("cleared", storage.load(1).titles.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun bellTitlesSurviveATrim() {
        val titles = (1..5).map {
            CalendarTitleRecord("series:imdb:tt$it", CalendarTitleType.SERIES, CalendarIds(imdb = "tt$it"), lastActivityMs = it.toLong(),
                origins = if (it == 1) setOf(CalendarOrigin.BELL) else emptySet())
        }
        val trimmed = trimLeastActiveTitles(CalendarSnapshot(titles = titles), count = 2)
        assertEquals("oldest non-bell dropped", listOf("series:imdb:tt1", "series:imdb:tt4", "series:imdb:tt5"), trimmed.titles.map { it.key })
    }
}
