package com.nuvio.tv.core.calendar

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSportsPolicyTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")

    private fun game(id: String, kickoffIn: Duration, state: CalendarGameState = CalendarGameState.SCHEDULED) =
        CalendarGameItem(id = id, home = "Arsenal", away = "Chelsea", league = "Premier League",
            kickoffMs = now.plus(kickoffIn).toEpochMilli(), state = state)

    @Test fun theFilterIsMultiSelectAndKeepsAtLeastOne() {
        val all = CALENDAR_ALL_FILTERS
        assertEquals("off", setOf(CalendarFilter.SHOWS, CalendarFilter.MOVIES), CalendarSportsPolicy.toggle(all, CalendarFilter.SPORTS))
        assertEquals("back on", all, CalendarSportsPolicy.toggle(setOf(CalendarFilter.SHOWS, CalendarFilter.MOVIES), CalendarFilter.SPORTS))
        assertEquals("the last one stays", setOf(CalendarFilter.SPORTS), CalendarSportsPolicy.toggle(setOf(CalendarFilter.SPORTS), CalendarFilter.SPORTS))
        assertTrue("shows kept", CalendarSportsPolicy.keepsTitle(setOf(CalendarFilter.SHOWS), CalendarTitleType.SERIES))
        assertFalse("films filtered", CalendarSportsPolicy.keepsTitle(setOf(CalendarFilter.SHOWS), CalendarTitleType.MOVIE))
        assertTrue("games filtered", CalendarSportsPolicy.games(setOf(CalendarFilter.SHOWS), listOf(game("a", Duration.ZERO))).isEmpty())
    }

    @Test fun filtersArePersistedAndDefaultToAll() {
        assertEquals("default all", CALENDAR_ALL_FILTERS, emptyPreferences().toCalendarSettings().filters)
        assertEquals("stored", setOf(CalendarFilter.SPORTS),
            mutablePreferencesOf(CalendarSettingsKeys.filters to setOf("SPORTS", "BOGUS")).toCalendarSettings().filters)
        assertEquals("nothing readable falls back to all", CALENDAR_ALL_FILTERS,
            mutablePreferencesOf(CalendarSettingsKeys.filters to setOf("BOGUS")).toCalendarSettings().filters)
    }

    @Test fun watchNowWhileLiveOrFromFifteenMinutesBeforeKickoff() {
        assertTrue("live", CalendarSportsPolicy.watchable(game("a", Duration.ofMinutes(-30), CalendarGameState.LIVE), now))
        assertTrue("15 min before", CalendarSportsPolicy.watchable(game("b", Duration.ofMinutes(15)), now))
        assertFalse("16 min before", CalendarSportsPolicy.watchable(game("c", Duration.ofMinutes(16)), now))
        assertFalse("final", CalendarSportsPolicy.watchable(game("d", Duration.ofHours(-3), CalendarGameState.FINAL), now))
        assertFalse("postponed", CalendarSportsPolicy.watchable(game("e", Duration.ofMinutes(5), CalendarGameState.POSTPONED), now))
    }

    @Test fun aGameIsPlacedByItsKickoffInTheCalendarZone() {
        // 2026-11-05 03:00 UTC is 4 Nov 19:00 in Los Angeles.
        val evening = game("a", Duration.ofHours(15))
        val la = ZoneId.of("America/Los_Angeles")
        assertEquals("UTC: tomorrow 03:00", CalendarGamePlacement(evening, LocalDate.of(2026, 11, 5), LocalTime.of(3, 0)),
            CalendarSportsPolicy.placement(evening, now, ZoneOffset.UTC))
        assertEquals("LA: today 19:00", CalendarGamePlacement(evening, LocalDate.of(2026, 11, 4), LocalTime.of(19, 0)),
            CalendarSportsPolicy.placement(evening, now, la))
        val liveFromYesterday = game("b", Duration.ofHours(-13), CalendarGameState.LIVE)
        assertEquals("live from yesterday sits on today", LocalDate.of(2026, 11, 4), CalendarSportsPolicy.placement(liveFromYesterday, now, ZoneOffset.UTC)!!.date)
        assertNull("an old final is dropped", CalendarSportsPolicy.placement(game("c", Duration.ofDays(-9), CalendarGameState.FINAL), now, ZoneOffset.UTC))
        assertNull("a stale scheduled game is dropped", CalendarSportsPolicy.placement(game("d", Duration.ofDays(-2)), now, ZoneOffset.UTC))
    }

    @Test fun gamesBecomeSectionItemsAndFinalsOfTheWeekAreJustAired() {
        val sections = buildCalendarSections(
            emptyList(), now, ZoneOffset.UTC, 30,
            games = listOf(
                game("final", Duration.ofDays(-2), CalendarGameState.FINAL),
                game("tonight", Duration.ofHours(8)),
                game("far", Duration.ofDays(40))
            )
        )
        assertEquals("sections", listOf(CalendarSectionKind.JUST_AIRED, CalendarSectionKind.TODAY), sections.map { it.kind })
        assertEquals("just aired final", listOf("final"), sections[0].games.map { it.game.id })
        assertEquals("today", listOf("tonight"), sections[1].games.map { it.game.id })
    }
}
