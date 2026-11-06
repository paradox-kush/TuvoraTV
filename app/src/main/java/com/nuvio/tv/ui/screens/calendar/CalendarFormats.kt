// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Dates and times in the app's language and the box's 12 or 24 hour choice. */
@Immutable
class CalendarFormats(val locale: Locale, is24Hour: Boolean) {
    private val time = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val longDate = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)
    private val month = DateTimeFormatter.ofPattern("LLLL y", locale)

    val firstDayOfWeek: DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    fun time(value: LocalTime): String = time.format(value)
    fun shortDate(value: LocalDate): String = shortDate.format(value)
    fun longDate(value: LocalDate): String = longDate.format(value)
    fun month(value: YearMonth): String = month.format(value).replaceFirstChar { it.titlecase(locale) }
    fun weekday(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale)
}

/** The zone override choices the settings offer; null is "this TV". Small on purpose: a D-pad list of 600 zones is no choice. */
val CALENDAR_ZONE_CHOICES: List<String?> = listOf(
    null, "UTC", "America/Los_Angeles", "America/New_York", "Europe/London", "Europe/Berlin", "Asia/Kolkata", "Australia/Sydney"
)
