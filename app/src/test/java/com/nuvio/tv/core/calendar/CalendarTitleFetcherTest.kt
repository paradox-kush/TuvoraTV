// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTitleFetcherTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")

    private open class Remote : CalendarRemoteSources {
        val asked = mutableListOf<String>()
        override suspend fun tvMazeLookup(ids: CalendarIds): TvMazeLookup { asked += "lookup"; return TvMazeLookup.NotFound }
        override suspend fun tvMazeShow(id: Int, now: Instant): CalendarShowDates { asked += "show"; return CalendarShowDates() }
        override suspend fun traktNextEpisode(ids: CalendarIds): CalendarShowDates? { asked += "traktNext"; return null }
        override suspend fun traktMyShows(today: LocalDate): List<TraktCalendarItem>? = null
        override suspend fun tmdbFind(imdb: String, type: CalendarTitleType): Long? { asked += "find"; return 55L }
        override suspend fun tmdbShow(tmdbId: Long): CalendarShowDates {
            asked += "tmdbShow"
            return CalendarShowDates(status = CalendarShowStatus.RUNNING, entries = listOf(CalendarEntry(season = 2, episode = 1, airDate = "2026-10-20", source = CalendarSource.TMDB)))
        }
        override suspend fun tmdbSeason(tmdbId: Long, season: Int): List<CalendarEntry> { asked += "season"; return emptyList() }
        override suspend fun tmdbFilm(tmdbId: Long, region: String): CalendarShowDates { asked += "film"; return CalendarShowDates() }
    }

    private val record = CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"))

    @Test fun aShowTvMazeDoesNotKnowFallsBackToTraktThenTmdb() = runBlocking {
        val remote = Remote()
        val outcome = CalendarTitleFetcher(remote, "US", ZoneOffset.UTC).refresh(record, emptyMap(), coveredByTrakt = false, now = now)
        assertEquals("order of asking", listOf("lookup", "traktNext", "find", "tmdbShow"), remote.asked)
        assertEquals("TMDB date", listOf("2026-10-20"), outcome.record.entries.map { it.airDate })
        assertEquals("learned the TMDB id", 55L, outcome.record.ids.tmdb)
        assertTrue("a TVmaze miss is remembered", outcome.tvMazeIds.containsKey("imdb:tt1") && outcome.tvMazeIds["imdb:tt1"]?.id == null)
        assertTrue("attempted", outcome.attempted)
        assertFalse("not failed", outcome.failed)
        assertEquals("next release in 15 days: daily", now.plus(Duration.ofHours(24)).toEpochMilli(), outcome.record.nextRefreshAtMs)
    }

    @Test fun aTitleTheTraktCalendarCoversIsNotAskedAndNotCounted() = runBlocking {
        val remote = Remote()
        val outcome = CalendarTitleFetcher(remote, "US", ZoneOffset.UTC).refresh(record, emptyMap(), coveredByTrakt = true, now = now)
        assertTrue("nothing asked", remote.asked.isEmpty())
        assertFalse("not an attempt", outcome.attempted)
    }

    @Test fun aFailureKeepsTheOldDatesAndBacksOff() = runBlocking {
        val remote = object : Remote() {
            override suspend fun tvMazeLookup(ids: CalendarIds): TvMazeLookup = throw IOException("offline")
        }
        val kept = record.copy(sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 1, airDate = "2026-10-07"))))
        val outcome = CalendarTitleFetcher(remote, "US", ZoneOffset.UTC).refresh(kept, emptyMap(), coveredByTrakt = false, now = now)
        assertTrue("failed", outcome.failed)
        assertEquals("one failure", 1, outcome.record.failures)
        assertEquals("old dates kept", listOf("2026-10-07"), outcome.record.entries.map { it.airDate })
        assertEquals("1 h back-off", now.plus(Duration.ofHours(1)).toEpochMilli(), outcome.record.nextRefreshAtMs)
    }
}
