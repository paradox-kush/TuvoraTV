// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class CalendarReminder(
    val title: CalendarTitleRecord,
    val entry: CalendarEntry,
    val dueAt: Instant
) {
    val episodeKey: String get() = title.episodeKey(entry)
}

data class CalendarDueReminders(val items: List<CalendarReminder> = emptyList()) {
    val count: Int get() = items.size

    fun moreThan(shown: Int): Int = (count - shown).coerceAtLeast(0)
}

private val REMINDER_DELAY: Duration = Duration.ofHours(1)
private val REMINDER_MAX_AGE: Duration = Duration.ofDays(14)
private val REMINDER_CHECK_MIN: Duration = Duration.ofMinutes(1)
private val REMINDER_CHECK_MAX: Duration = Duration.ofHours(6)

/**
 * An exact time is due an hour after it airs. A plain date is due at 01:00 UTC the next day, when that date has
 * begun everywhere.
 */
fun calendarReminderDueAt(entry: CalendarEntry): Instant? {
    entry.airInstantMs?.let { return Instant.ofEpochMilli(it).plus(REMINDER_DELAY) }
    val date = entry.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plus(REMINDER_DELAY)
}

/** Bell titles only, due within the last 14 days, newest first. A cinema date gives no reminder. */
fun dueReminders(
    titles: List<CalendarTitleRecord>,
    bells: Set<String>,
    seenEpisodeKeys: Set<String>,
    isWatched: (CalendarTitleRecord, CalendarEntry) -> Boolean,
    now: Instant
): CalendarDueReminders {
    val oldest = now.minus(REMINDER_MAX_AGE)
    val items = titles
        .filter { title -> bells.any(title::matchesKey) }
        .flatMap { title ->
            title.entries.mapNotNull { entry ->
                if (entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return@mapNotNull null
                val dueAt = calendarReminderDueAt(entry) ?: return@mapNotNull null
                if (dueAt.isAfter(now) || dueAt.isBefore(oldest)) return@mapNotNull null
                if (title.episodeKey(entry) in seenEpisodeKeys || isWatched(title, entry)) return@mapNotNull null
                CalendarReminder(title, entry, dueAt)
            }
        }
        .sortedWith(compareByDescending<CalendarReminder> { it.dueAt }.thenBy { it.title.name.orEmpty().lowercase() })
    return CalendarDueReminders(items)
}

/** The next moment a bell title's release becomes due, or null when nothing is waiting. */
fun calendarNextReminderAt(
    titles: List<CalendarTitleRecord>,
    bells: Set<String>,
    seenEpisodeKeys: Set<String>,
    now: Instant
): Instant? = titles
    .filter { title -> bells.any(title::matchesKey) }
    .flatMap { title ->
        title.entries.mapNotNull { entry ->
            if (entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return@mapNotNull null
            val dueAt = calendarReminderDueAt(entry) ?: return@mapNotNull null
            dueAt.takeIf { it.isAfter(now) && title.episodeKey(entry) !in seenEpisodeKeys }
        }
    }
    .minOrNull()

/** How long the foreground reminder check sleeps: never sooner than a minute, never later than 6 hours. */
fun calendarReminderCheckDelay(next: Instant?, now: Instant): Duration {
    val wait = next?.let { Duration.between(now, it) } ?: return REMINDER_CHECK_MAX
    return when {
        wait < REMINDER_CHECK_MIN -> REMINDER_CHECK_MIN
        wait > REMINDER_CHECK_MAX -> REMINDER_CHECK_MAX
        else -> wait
    }
}

/** Everything that became due since the last check is one corner message; each reminder is announced once. */
class CalendarReminderBatcher {
    private val announced = mutableSetOf<String>()

    @Synchronized
    fun nextBatch(due: CalendarDueReminders): List<CalendarReminder> {
        val batch = due.items.filter { it.episodeKey !in announced }
        batch.mapTo(announced) { it.episodeKey }
        return batch
    }

    @Synchronized
    fun reset() = announced.clear()
}

fun Map<String, Long>.withSeen(episodeKeys: Collection<String>, now: Instant): Map<String, Long> =
    this + episodeKeys.associateWith { now.toEpochMilli() }

/** Seen reminder keys are kept 30 days, then forgotten (the reminder itself only lives 14). */
val CALENDAR_SEEN_KEEP: Duration = Duration.ofDays(30)

fun Map<String, Long>.prunedSeen(now: Instant): Map<String, Long> {
    val since = now.minus(CALENDAR_SEEN_KEEP).toEpochMilli()
    return filterValues { it >= since }
}

/** The Home card: up to [shown] rows, the rest counted. Every key on it is marked seen when it shows. */
data class CalendarReminderCard(
    val items: List<CalendarReminder>,
    val more: Int,
    val episodeKeys: List<String>
)

fun calendarReminderCard(due: CalendarDueReminders, shown: Int = 3): CalendarReminderCard? {
    if (due.count == 0) return null
    return CalendarReminderCard(due.items.take(shown), due.moreThan(shown), due.items.map { it.episodeKey })
}

/** Everything that became due since the last check, shown together in the corner. */
data class CalendarCornerMessage(val id: Long, val items: List<CalendarReminder>) {
    val count: Int get() = items.size
    val names: List<String> get() = items.mapNotNull { it.title.name?.takeIf(String::isNotBlank) }.distinct()
}
