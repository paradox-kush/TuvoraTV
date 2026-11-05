// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val FAILURE_BACKOFF = listOf(Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(24))

/**
 * When a title is asked again: next release within 7 days every 6 hours, within 30 days daily, later or unknown and
 * still running weekly, ended or cancelled with nothing ahead monthly. After a failed fetch: 1 hour, then 6 hours,
 * then daily. "Next release" is counted in [zone] (the resolved Calendar zone).
 */
fun nextRefreshAt(record: CalendarTitleRecord, now: Instant, zone: ZoneId): Instant {
    if (record.failures > 0) return now.plus(FAILURE_BACKOFF[(record.failures - 1).coerceAtMost(FAILURE_BACKOFF.lastIndex)])
    val next = record.entries.mapNotNull { it.sortInstant(zone) }.filter { !it.isBefore(now) }.minOrNull()
    val wait = when {
        next != null && next.isBefore(now.plus(Duration.ofDays(7))) -> Duration.ofHours(6)
        next != null && next.isBefore(now.plus(Duration.ofDays(30))) -> Duration.ofHours(24)
        next == null && record.status.isOver -> Duration.ofDays(30)
        else -> Duration.ofDays(7)
    }
    return now.plus(wait)
}

fun CalendarTitleRecord.isDue(now: Instant): Boolean = nextRefreshAtMs <= now.toEpochMilli()

/** The signed-in Trakt calendar is asked at most this often. */
val CALENDAR_TRAKT_REFRESH: Duration = Duration.ofHours(6)

fun calendarTraktCalendarDue(fetchedAtMs: Long, now: Instant): Boolean =
    now.toEpochMilli() - fetchedAtMs >= CALENDAR_TRAKT_REFRESH.toMillis()
