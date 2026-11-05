// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRefreshPolicyTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private val utc: ZoneId = ZoneOffset.UTC

    private fun record(
        nextIn: Duration?,
        status: CalendarShowStatus = CalendarShowStatus.RUNNING,
        failures: Int = 0,
        pastIn: Duration? = Duration.ofDays(-2)
    ) = CalendarTitleRecord(
        key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
        status = status, failures = failures,
        sources = mapOf(
            CalendarSource.TVMAZE to listOfNotNull(
                pastIn?.let { CalendarEntry(season = 1, episode = 1, airInstantMs = now.plus(it).toEpochMilli()) },
                nextIn?.let { CalendarEntry(season = 1, episode = 2, airInstantMs = now.plus(it).toEpochMilli()) }
            )
        )
    )

    private fun wait(record: CalendarTitleRecord, zone: ZoneId = utc) = Duration.between(now, nextRefreshAt(record, now, zone))

    @Test fun nextReleaseWithinAWeekEverySixHours() {
        assertEquals("6 days ahead", Duration.ofHours(6), wait(record(Duration.ofDays(6))))
        assertEquals("1 hour ahead", Duration.ofHours(6), wait(record(Duration.ofHours(1))))
    }

    @Test fun nextReleaseWithinAMonthDaily() {
        assertEquals("7 days ahead", Duration.ofHours(24), wait(record(Duration.ofDays(7))))
        assertEquals("29 days ahead", Duration.ofHours(24), wait(record(Duration.ofDays(29))))
    }

    @Test fun laterOrUnknownWeeklyWhileRunning() {
        assertEquals("45 days ahead", Duration.ofDays(7), wait(record(Duration.ofDays(45))))
        assertEquals("nothing ahead, running", Duration.ofDays(7), wait(record(null)))
        assertEquals("nothing ahead, unknown", Duration.ofDays(7), wait(record(null, CalendarShowStatus.UNKNOWN)))
    }

    @Test fun endedOrCancelledMonthly() {
        assertEquals("ended", Duration.ofDays(30), wait(record(null, CalendarShowStatus.ENDED)))
        assertEquals("cancelled", Duration.ofDays(30), wait(record(null, CalendarShowStatus.CANCELLED)))
        assertEquals("ended but one more ahead", Duration.ofHours(6), wait(record(Duration.ofDays(2), CalendarShowStatus.ENDED)))
    }

    @Test fun aPlainDateCountsFromMidnightInTheGivenZone() {
        val dated = CalendarTitleRecord(
            key = "k", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
            sources = mapOf(CalendarSource.TMDB to listOf(CalendarEntry(season = 1, episode = 1, airDate = "2026-10-13")))
        )
        // 13 Oct 00:00 UTC is 7 d 1 h away; 13 Oct 00:00 at UTC+14 is 6 d 11 h away.
        assertEquals("UTC zone", Duration.ofHours(24), wait(dated, utc))
        assertEquals("override zone UTC+14", Duration.ofHours(6), wait(dated, ZoneId.of("Pacific/Kiritimati")))
    }

    @Test fun failuresBackOffOneSixAndTwentyFourHours() {
        assertEquals("1st failure", Duration.ofHours(1), wait(record(Duration.ofDays(1), failures = 1)))
        assertEquals("2nd failure", Duration.ofHours(6), wait(record(Duration.ofDays(1), failures = 2)))
        assertEquals("3rd failure", Duration.ofHours(24), wait(record(Duration.ofDays(1), failures = 3)))
        assertEquals("9th failure", Duration.ofHours(24), wait(record(Duration.ofDays(1), failures = 9)))
    }

    @Test fun dueWhenTheRefreshTimeHasCome() {
        assertTrue("never refreshed", record(null).copy(nextRefreshAtMs = 0L).isDue(now))
        assertTrue("exactly now", record(null).copy(nextRefreshAtMs = now.toEpochMilli()).isDue(now))
        assertFalse("one ms ahead", record(null).copy(nextRefreshAtMs = now.toEpochMilli() + 1).isDue(now))
    }

    @Test fun traktCalendarIsAskedAtMostEverySixHours() {
        assertTrue("never fetched", calendarTraktCalendarDue(0L, now))
        assertFalse("fetched 5 h ago", calendarTraktCalendarDue(now.minus(Duration.ofHours(5)).toEpochMilli(), now))
        assertTrue("fetched 6 h ago", calendarTraktCalendarDue(now.minus(Duration.ofHours(6)).toEpochMilli(), now))
    }
}
