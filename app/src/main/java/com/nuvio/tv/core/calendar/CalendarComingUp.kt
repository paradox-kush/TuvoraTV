// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class CalendarComingUpWhen { NEW, TODAY, TONIGHT, TOMORROW, WEEKDAY, DATE }

data class CalendarComingUpItem(
    val title: CalendarTitleRecord,
    val entry: CalendarEntry,
    val whenKind: CalendarComingUpWhen,
    val date: LocalDate,
    val time: LocalTime?
)

const val CALENDAR_COMING_UP_MAX = 15
private const val NEW_DAYS = 7L
private const val WEEKDAY_DAYS = 6L
private val TONIGHT_FROM: LocalTime = LocalTime.of(17, 0)

/**
 * The Home row: one card per title. Anything out in the last 7 days and not watched comes first, newest first; then
 * the soonest release of every other title. A cinema date is only listed before it opens. Days in [zone].
 */
fun buildCalendarComingUp(
    titles: List<CalendarTitleRecord>,
    watched: CalendarWatchedIndex?,
    now: Instant,
    zone: ZoneId,
    max: Int = CALENDAR_COMING_UP_MAX
): List<CalendarComingUpItem> {
    val today = now.atZone(zone).toLocalDate()
    val oldest = today.minusDays(NEW_DAYS)
    val fresh = mutableListOf<CalendarComingUpItem>()
    val upcoming = mutableListOf<CalendarComingUpItem>()
    titles.forEach { title ->
        var newestOut: CalendarComingUpItem? = null
        var soonest: CalendarComingUpItem? = null
        title.entries.forEach { entry ->
            val date = entry.localDate(zone) ?: return@forEach
            val time = entry.airInstantMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            if (entry.isOut(now)) {
                if (date < oldest || entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return@forEach
                if (watched?.isWatched(title, entry) == true) return@forEach
                val item = CalendarComingUpItem(title, entry, CalendarComingUpWhen.NEW, date, time)
                if (newestOut == null || comingUpOrder.compare(item, newestOut) > 0) newestOut = item
            } else {
                val shownDate = maxOf(date, today)
                val item = CalendarComingUpItem(title, entry, comingUpWhen(shownDate, time, today), shownDate, time)
                if (soonest == null || comingUpOrder.compare(item, soonest) < 0) soonest = item
            }
        }
        newestOut?.let(fresh::add) ?: soonest?.let(upcoming::add)
    }
    return (fresh.sortedWith(comingUpOrder.reversed()) + upcoming.sortedWith(comingUpOrder)).take(max)
}

private fun comingUpWhen(date: LocalDate, time: LocalTime?, today: LocalDate): CalendarComingUpWhen = when {
    date == today -> if (time != null && time >= TONIGHT_FROM) CalendarComingUpWhen.TONIGHT else CalendarComingUpWhen.TODAY
    date == today.plusDays(1) -> CalendarComingUpWhen.TOMORROW
    date <= today.plusDays(WEEKDAY_DAYS) -> CalendarComingUpWhen.WEEKDAY
    else -> CalendarComingUpWhen.DATE
}

private val comingUpOrder: Comparator<CalendarComingUpItem> =
    compareBy<CalendarComingUpItem> { it.date }
        .thenBy { it.time == null }
        .thenBy { it.time }
        .thenBy { it.title.name.orEmpty().lowercase() }
        .thenBy { it.entry.season ?: 0 }
        .thenBy { it.entry.episode ?: 0 }

/** Ids to open a title by, best first. */
fun calendarContentIds(title: CalendarTitleRecord): List<String> =
    listOfNotNull(title.contentId, title.ids.imdb, title.ids.tmdb?.let { "tmdb:$it" }).distinct()
