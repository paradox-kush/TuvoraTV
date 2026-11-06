// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarEntryKind
import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarSource
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarWatchedIndex
import com.nuvio.tv.core.calendar.buildCalendarSections
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarUiModelTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val zone = ZoneOffset.UTC

    private val show = CalendarTitleRecord(
        "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"), name = "Harbour",
        sources = mapOf(CalendarSource.TVMAZE to listOf(
            CalendarEntry(season = 1, episode = 1, airInstantMs = Instant.parse("2026-11-02T20:00:00Z").toEpochMilli()),
            CalendarEntry(season = 1, episode = 2, airInstantMs = Instant.parse("2026-11-09T20:00:00Z").toEpochMilli())
        ))
    )

    @Test fun aMonthGridIsSixFullWeeksFromTheLocalesFirstDay() {
        val grid = calendarMonthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals("42 days", 42, grid.size)
        assertEquals("starts on the Monday before 1 Nov", LocalDate.of(2026, 10, 26), grid.first())
        assertTrue("first column is Monday", grid.chunked(7).all { it.first().dayOfWeek == DayOfWeek.MONDAY })
    }

    @Test fun theListOpensOnTodayOrLaterAndRowKeysNeverRepeat() {
        val list = buildCalendarList(buildCalendarSections(listOf(show, show), now, zone, 30), now, emptySet(), null)
        val rows = list.entries.filterIsInstance<CalendarRowEntry>()
        assertEquals("unique keys", rows.size, rows.map { it.key }.toSet().size)
        assertEquals("start row is not Just aired", "series:imdb:tt1|S1E2", list.startRowKey)
    }

    @Test fun rowStatusIsTheBellBeforeReleaseAndWatchedAfter() {
        val watched = CalendarWatchedIndex.Builder().add("tt1", 1, 1).build()
        val (aired, next) = show.entries
        assertEquals("aired and watched", CalendarRowStatus.WATCHED, calendarRowStatus(show, aired, now, emptySet(), watched))
        assertEquals("aired not watched", CalendarRowStatus.NOT_WATCHED, calendarRowStatus(show, aired, now, emptySet(), null))
        assertEquals("ahead, bell on", CalendarRowStatus.BELL_ON, calendarRowStatus(show, next, now, setOf("series:imdb:tt1"), null))
        assertEquals("ahead, bell off", CalendarRowStatus.BELL_OFF, calendarRowStatus(show, next, now, emptySet(), null))
        val cinema = CalendarEntry(kind = CalendarEntryKind.MOVIE_THEATRICAL, airDate = "2026-12-01")
        assertEquals("cinema dates have no bell", CalendarRowStatus.NONE, calendarRowStatus(show, cinema, now, emptySet(), null))
    }

    @Test fun daysGroupEveryDatedReleaseInTheGivenZone() {
        val days = buildCalendarDays(listOf(show), now, ZoneOffset.ofHours(10), emptySet(), null)
        assertEquals("20:00 UTC is the next day at UTC+10", setOf(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 10)), days.keys)
    }
}
