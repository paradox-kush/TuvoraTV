// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSettingsTest {

    @Test fun defaults() {
        assertEquals(
            "defaults: 30 days, box zone, corner note on, Coming up row OFF, list view",
            CalendarSettings(
                windowDays = 30,
                zoneOverride = null,
                cornerMessage = true,
                comingUpRow = false,
                hiddenTitleKeys = emptySet(),
                bellOn = emptySet(),
                viewMode = CalendarViewMode.LIST
            ),
            emptyPreferences().toCalendarSettings()
        )
    }

    @Test fun aBlankZoneMeansTheBoxsZone() {
        assertNull("blank override", mutablePreferencesOf(CalendarSettingsKeys.zoneOverride to "").toCalendarSettings().zoneOverride)
    }

    @Test fun storedValuesAreReadAndOddOnesFallBack() {
        val settings = mutablePreferencesOf(
            CalendarSettingsKeys.windowDays to 45,
            CalendarSettingsKeys.zoneOverride to "Mars/Olympus",
            CalendarSettingsKeys.bellOn to setOf("series:imdb:tt1"),
            CalendarSettingsKeys.viewMode to "MONTH",
            CalendarSettingsKeys.comingUpRow to true
        ).toCalendarSettings()
        assertEquals("unknown window falls back", 30, settings.windowDays)
        assertNull("unknown zone dropped", settings.zoneOverride)
        assertEquals("bells", setOf("series:imdb:tt1"), settings.bellOn)
        assertEquals("view", CalendarViewMode.MONTH, settings.viewMode)
        assertTrue("row on", settings.comingUpRow)
        assertEquals("14 allowed", 14, normalizeCalendarWindowDays(14))
        assertEquals("60 allowed", 60, normalizeCalendarWindowDays(60))
        assertEquals("trimmed zone", "Australia/Sydney", normalizeCalendarZoneOverride(" Australia/Sydney "))
        assertEquals("unknown view falls back", CalendarViewMode.LIST,
            mutablePreferencesOf(CalendarSettingsKeys.viewMode to "WEEK").toCalendarSettings().viewMode)
    }

    @Test fun turningABellOffRemovesItUnderAnyOfTheTitlesIds() {
        val bells = setOf("series:tmdb:5", "series:imdb:tt9", "movie:tmdb:5")
        assertEquals("off by id", setOf("series:imdb:tt9", "movie:tmdb:5"), bells.withTitleKey("series:tmdb:5", present = false))
        assertEquals("on adds", bells + "series:imdb:tt2", bells.withTitleKey("series:imdb:tt2", present = true))
    }

    @Test fun zoneFollowsTheBoxUnlessOverridden() {
        val box = ZoneId.of("Australia/Sydney")
        assertEquals("no override", box, resolveCalendarZone(null, box))
        assertEquals("bad override", box, resolveCalendarZone("nowhere", box))
        assertEquals("override", ZoneId.of("Europe/Berlin"), resolveCalendarZone("Europe/Berlin", box))
        assertEquals("settings helper", ZoneId.of("Europe/Berlin"), CalendarSettings(zoneOverride = "Europe/Berlin").resolvedZone(ZoneOffset.UTC))
        assertEquals("settings not read yet", ZoneOffset.UTC, (null as CalendarSettings?).resolvedZone(ZoneOffset.UTC))
    }

    @Test fun theBellKeyIsMadeFromTheTitlesReadableIds() {
        assertEquals("imdb series", "series:imdb:tt1", calendarBellKey("series", "tt1", null))
        assertEquals("tmdb film with imdb", "movie:imdb:tt5", calendarBellKey("movie", "tmdb:9", "tt5"))
        assertNull("kitsu id without imdb", calendarBellKey("series", "kitsu:1376", null))
        assertNull("a channel", calendarBellKey("tv", "iptv:abc", null))
        assertFalse("another title's bell", calendarBellOn(setOf("series:tmdb:4"), "series:imdb:tt1"))
        assertTrue("on by same key", calendarBellOn(setOf("series:imdb:tt1"), "series:imdb:tt1"))
        assertFalse("no key", calendarBellOn(setOf("series:imdb:tt1"), null))
    }
}
