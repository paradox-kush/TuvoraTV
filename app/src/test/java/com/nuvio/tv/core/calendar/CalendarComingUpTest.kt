// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarComingUpTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun show(key: String, vararg at: Duration) = CalendarTitleRecord(
        "series:imdb:$key", CalendarTitleType.SERIES, CalendarIds(imdb = key), name = key,
        sources = mapOf(CalendarSource.TVMAZE to at.mapIndexed { i, offset ->
            CalendarEntry(season = 1, episode = i + 1, airInstantMs = now.plus(offset).toEpochMilli())
        })
    )

    @Test fun newAndUnwatchedFirstThenTheSoonestPerTitle() {
        val items = buildCalendarComingUp(
            listOf(
                show("ttLater", Duration.ofDays(3), Duration.ofDays(10)),
                show("ttNew", Duration.ofDays(-2), Duration.ofDays(5)),
                show("ttTonight", Duration.ofHours(8)),
                show("ttWatched", Duration.ofDays(-1))
            ),
            CalendarWatchedIndex.Builder().add("ttWatched", 1, 1).build(),
            now,
            zone
        )
        assertEquals("order", listOf("ttNew", "ttTonight", "ttLater"), items.map { it.title.name })
        assertEquals("kinds", listOf(CalendarComingUpWhen.NEW, CalendarComingUpWhen.TONIGHT, CalendarComingUpWhen.WEEKDAY), items.map { it.whenKind })
        assertEquals("one card per title, the soonest", 1, items.last().entry.episode)
    }

    @Test fun atMostFifteenCards() {
        val many = (1..20).map { show("tt$it", Duration.ofDays(it.toLong())) }
        assertEquals("cap", CALENDAR_COMING_UP_MAX, buildCalendarComingUp(many, null, now, zone).size)
    }
}
