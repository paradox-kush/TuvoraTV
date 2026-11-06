// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarEntryKind
import com.nuvio.tv.core.calendar.CalendarEpisodeLabel
import com.nuvio.tv.core.calendar.CalendarGameItem
import com.nuvio.tv.core.calendar.CalendarGamePlacement
import com.nuvio.tv.core.calendar.CalendarSportsPolicy
import com.nuvio.tv.core.calendar.CalendarLoad
import com.nuvio.tv.core.calendar.CalendarLoadStatus
import com.nuvio.tv.core.calendar.CalendarSection
import com.nuvio.tv.core.calendar.CalendarSectionKind
import com.nuvio.tv.core.calendar.CalendarState
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarWatchedIndex
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** What sits at the right of a row: the show's bell before release, a tick or a dot after it. */
enum class CalendarRowStatus { BELL_OFF, BELL_ON, WATCHED, NOT_WATCHED, NONE;

    val hasBell: Boolean get() = this == BELL_OFF || this == BELL_ON
}

enum class CalendarRowKind { EPISODE, FILM_CINEMA, FILM_DIGITAL, SHOW, GAME }

@Immutable
data class CalendarRow(
    val key: String,
    val titleKey: String,
    val name: String?,
    val poster: String?,
    val contentId: String?,
    val type: CalendarTitleType,
    val kind: CalendarRowKind,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val label: CalendarEpisodeLabel = CalendarEpisodeLabel.NONE,
    val status: CalendarRowStatus = CalendarRowStatus.NONE,
    val network: String? = null,
    val section: CalendarSectionKind = CalendarSectionKind.DAY,
    /** Set for [CalendarRowKind.GAME] rows only. */
    val game: CalendarGameItem? = null,
    /** "Watch now" shows (LIVE, or within 15 min of kick-off). */
    val watchable: Boolean = false
) {
    val isFilm: Boolean get() = type == CalendarTitleType.MOVIE
    val apiType: String get() = if (isFilm) "movie" else "series"
}

sealed interface CalendarListEntry {
    val key: String
}

@Immutable
data class CalendarHeaderEntry(val kind: CalendarSectionKind, val date: LocalDate?) : CalendarListEntry {
    override val key: String get() = "h:${kind.name}:${date ?: ""}"
}

@Immutable
data class CalendarRowEntry(val row: CalendarRow) : CalendarListEntry {
    override val key: String get() = row.key
}

@Immutable
data class CalendarListModel(
    val entries: List<CalendarListEntry> = emptyList(),
    /** The row the list opens on: the first of today or later, else the first row. */
    val startRowKey: String? = null
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

fun calendarRowStatus(
    title: CalendarTitleRecord,
    entry: CalendarEntry?,
    now: Instant,
    bells: Set<String>,
    watched: CalendarWatchedIndex?
): CalendarRowStatus {
    val bell = bells.any(title::matchesKey)
    if (entry == null) return if (bell) CalendarRowStatus.BELL_ON else CalendarRowStatus.BELL_OFF
    if (entry.isOut(now)) {
        return if (watched?.isWatched(title, entry) == true) CalendarRowStatus.WATCHED else CalendarRowStatus.NOT_WATCHED
    }
    if (entry.kind == CalendarEntryKind.MOVIE_THEATRICAL) return CalendarRowStatus.NONE
    return if (bell) CalendarRowStatus.BELL_ON else CalendarRowStatus.BELL_OFF
}

private fun rowKind(title: CalendarTitleRecord, entry: CalendarEntry?): CalendarRowKind = when {
    entry == null -> CalendarRowKind.SHOW
    entry.kind == CalendarEntryKind.MOVIE_THEATRICAL -> CalendarRowKind.FILM_CINEMA
    entry.kind == CalendarEntryKind.MOVIE_DIGITAL -> CalendarRowKind.FILM_DIGITAL
    title.type == CalendarTitleType.MOVIE -> CalendarRowKind.FILM_DIGITAL
    else -> CalendarRowKind.EPISODE
}

fun calendarRow(
    title: CalendarTitleRecord,
    entry: CalendarEntry?,
    date: LocalDate?,
    time: LocalTime?,
    section: CalendarSectionKind,
    now: Instant,
    bells: Set<String>,
    watched: CalendarWatchedIndex?
): CalendarRow {
    val kind = rowKind(title, entry)
    return CalendarRow(
        key = "${title.key}|${entry?.slot ?: "next"}",
        titleKey = title.key,
        name = title.name,
        poster = title.poster,
        contentId = title.contentId ?: title.ids.imdb ?: title.ids.tmdb?.let { "tmdb:$it" },
        type = title.type,
        kind = kind,
        season = entry?.season.takeIf { kind == CalendarRowKind.EPISODE },
        episode = entry?.episode.takeIf { kind == CalendarRowKind.EPISODE },
        episodeTitle = entry?.shownTitle?.takeIf { kind == CalendarRowKind.EPISODE },
        date = date,
        time = time,
        label = entry?.label ?: CalendarEpisodeLabel.NONE,
        status = calendarRowStatus(title, entry, now, bells, watched),
        network = title.network?.trim()?.takeIf(String::isNotEmpty),
        section = section
    )
}

/** Turns the sections into one flat list of headings and rows with keys that never repeat. */
fun buildCalendarList(
    sections: List<CalendarSection>,
    now: Instant,
    bells: Set<String>,
    watched: CalendarWatchedIndex?
): CalendarListModel {
    val entries = ArrayList<CalendarListEntry>()
    val used = HashSet<String>()
    var startRowKey: String? = null
    var firstRowKey: String? = null
    sections.forEach { section ->
        if (section.items.isEmpty() && section.games.isEmpty()) return@forEach
        entries += CalendarHeaderEntry(section.kind, section.date)
        val titleRows = section.items.map { item ->
            calendarRow(item.title, item.entry, item.date, item.time, section.kind, now, bells, watched)
        }
        val gameRows = section.games.map { calendarGameRow(it, section.kind, now) }
        // Games sit next to episodes by time; Just aired stays newest first.
        val merged = if (section.kind == CalendarSectionKind.JUST_AIRED) titleRows + gameRows
        else (titleRows + gameRows).sortedWith(compareBy<CalendarRow> { it.time == null }.thenBy { it.time })
        merged.forEach { base ->
            var key = base.key
            var n = 1
            while (!used.add(key)) key = "${base.key}#${n++}"
            val row = if (key == base.key) base else base.copy(key = key)
            entries += CalendarRowEntry(row)
            if (firstRowKey == null) firstRowKey = key
            if (startRowKey == null && section.kind != CalendarSectionKind.JUST_AIRED) startRowKey = key
        }
    }
    return CalendarListModel(entries, startRowKey ?: firstRowKey)
}

/** A game as a Calendar row: both teams, league, kick-off in the Calendar zone, score and state. */
fun calendarGameRow(placement: CalendarGamePlacement, section: CalendarSectionKind, now: Instant): CalendarRow {
    val game = placement.game
    return CalendarRow(
        key = "game|${game.id}",
        titleKey = "game:${game.id}",
        name = game.title,
        poster = game.leagueBadge,
        contentId = null,
        type = CalendarTitleType.SERIES,
        kind = CalendarRowKind.GAME,
        date = placement.date,
        time = placement.time,
        network = game.league,
        section = section,
        game = game,
        watchable = CalendarSportsPolicy.watchable(game, now)
    )
}

/** Every dated release and game by day in [zone], for the month view. A day's rows are in time order. */
fun buildCalendarDays(
    titles: List<CalendarTitleRecord>,
    now: Instant,
    zone: ZoneId,
    bells: Set<String>,
    watched: CalendarWatchedIndex?,
    games: List<CalendarGameItem> = emptyList()
): Map<LocalDate, List<CalendarRow>> {
    val byDay = HashMap<LocalDate, MutableList<CalendarRow>>()
    games.forEach { game ->
        val at = Instant.ofEpochMilli(game.kickoffMs).atZone(zone)
        val placement = CalendarGamePlacement(game, at.toLocalDate(), at.toLocalTime())
        byDay.getOrPut(placement.date) { mutableListOf() } += calendarGameRow(placement, CalendarSectionKind.DAY, now)
    }
    titles.forEach { title ->
        title.entries.forEach { entry ->
            val date = entry.localDate(zone) ?: return@forEach
            val time = entry.airInstantMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
            byDay.getOrPut(date) { mutableListOf() } +=
                calendarRow(title, entry, date, time, CalendarSectionKind.DAY, now, bells, watched)
        }
    }
    return byDay.mapValues { (_, rows) ->
        rows.sortedWith(compareBy<CalendarRow> { it.time == null }.thenBy { it.time }.thenBy { it.name.orEmpty().lowercase() })
            .distinctBy { it.key }
    }
}

/** Six full weeks starting on [firstDay], so the grid never changes height between months. */
fun calendarMonthGrid(month: YearMonth, firstDay: DayOfWeek): List<LocalDate> {
    val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay))
    return List(42) { start.plusDays(it.toLong()) }
}

/** What the screen shows. Derived in one pure function so every path ends in a terminal state. */
sealed interface CalendarScreenStatus {
    data class Loading(val remaining: Int) : CalendarScreenStatus
    data class Failed(val timedOut: Boolean) : CalendarScreenStatus
    data class Empty(val nothingFollowed: Boolean) : CalendarScreenStatus
    /** Rows to show; [updateFailed] adds the inline "Couldn't update · Retry". */
    data class Ready(val updateFailed: Boolean, val refreshing: Boolean) : CalendarScreenStatus
}

/**
 * Fix 1: a failed run is Failed (full error with Retry when nothing is cached, an inline note over cached rows),
 * never the "all quiet" empty state. Fix 2: while waiting, the [load] deadline decides; past it, Failed(timedOut)
 * even though the refresh is still going.
 */
fun calendarScreenStatus(
    state: CalendarState,
    settingsLoaded: Boolean,
    load: CalendarLoadStatus,
    hasRows: Boolean,
    nowMs: Long
): CalendarScreenStatus {
    val waiting = !state.loaded || !settingsLoaded || state.refreshing || state.lastRun == null
    val timedOut = waiting && CalendarLoad.effectiveAt(load, nowMs) is CalendarLoadStatus.Failed
    val lastFailed = state.lastRun?.failed == true
    return when {
        hasRows -> CalendarScreenStatus.Ready(
            updateFailed = timedOut || (!state.refreshing && lastFailed),
            refreshing = state.refreshing
        )
        timedOut -> CalendarScreenStatus.Failed(timedOut = true)
        waiting -> CalendarScreenStatus.Loading(state.remaining)
        lastFailed -> CalendarScreenStatus.Failed(timedOut = false)
        else -> CalendarScreenStatus.Empty(nothingFollowed = state.titles.isEmpty())
    }
}
