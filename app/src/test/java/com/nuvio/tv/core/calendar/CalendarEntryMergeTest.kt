// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEntryMergeTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private val sixAmUtc = Instant.parse("2026-10-10T06:00:00Z").toEpochMilli()

    @Test fun anExactTimeBeatsAPlainDateFromAHigherSource() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 2, episode = 5, airDate = "2026-10-09", source = CalendarSource.TMDB, title = "From TMDB"),
                CalendarEntry(season = 2, episode = 5, airInstantMs = sixAmUtc, source = CalendarSource.TVMAZE)
            )
        ).single()
        assertEquals("time from TVmaze", sixAmUtc, merged.airInstantMs)
        assertNull("no plain date once timed", merged.airDate)
        assertEquals("base source", CalendarSource.TVMAZE, merged.source)
        assertEquals("title kept from TMDB", "From TMDB", merged.title)
    }

    @Test fun traktsEpisodeTypeBeatsEveryOtherLabelAndTraktsTimeBeatsTvMazes() {
        val trakt = CalendarEntry(
            season = 3, episode = 10, airInstantMs = sixAmUtc + 60_000, label = CalendarEpisodeLabel.SERIES_FINALE,
            source = CalendarSource.TRAKT
        )
        val tvMaze = CalendarEntry(season = 3, episode = 10, airInstantMs = sixAmUtc, source = CalendarSource.TVMAZE, title = "Last")
        val tmdb = CalendarEntry(season = 3, episode = 10, airDate = "2026-10-10", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB)
        val merged = mergeCalendarEntries(listOf(tvMaze, tmdb, trakt)).single()
        assertEquals("Trakt label", CalendarEpisodeLabel.SERIES_FINALE, merged.label)
        assertEquals("Trakt time on a tie of fetch times", sixAmUtc + 60_000, merged.airInstantMs)
        assertEquals("title", "Last", merged.title)
    }

    @Test fun aStandardTraktEpisodeTypeOverridesATmdbFinaleMark() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 8, airInstantMs = sixAmUtc, label = CalendarEpisodeLabel.NONE, source = CalendarSource.TRAKT),
                CalendarEntry(season = 1, episode = 8, airDate = "2026-10-10", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB)
            )
        ).single()
        assertEquals("Trakt says standard", CalendarEpisodeLabel.NONE, merged.label)
    }

    @Test fun tmdbFinaleMarksTvMazeEpisodesWithoutChangingTheirTime() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 8, airInstantMs = sixAmUtc, source = CalendarSource.TVMAZE),
                CalendarEntry(season = 1, episode = 8, airDate = "2026-10-10", label = CalendarEpisodeLabel.SEASON_FINALE, source = CalendarSource.TMDB)
            )
        ).single()
        assertEquals("finale label", CalendarEpisodeLabel.SEASON_FINALE, merged.label)
        assertEquals("TVmaze time", sixAmUtc, merged.airInstantMs)
    }

    @Test fun noSourceWithATimeMeansNoTime() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 2, airDate = "2026-10-11", source = CalendarSource.TMDB),
                CalendarEntry(season = 1, episode = 2, airDate = "2026-10-10", source = CalendarSource.TVMAZE)
            )
        ).single()
        assertNull("no time", merged.airInstantMs)
        assertEquals("higher-priority date", "2026-10-10", merged.airDate)
        assertFalse("time not known", merged.timeKnown)
    }

    @Test fun differentReleasesStaySeparateInDateOrder() {
        val merged = mergeCalendarEntries(
            listOf(
                CalendarEntry(season = 1, episode = 3, airDate = "2026-10-20"),
                CalendarEntry(season = 1, episode = 2, airDate = "2026-10-13"),
                CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-10-15")
            )
        )
        assertEquals("slots in date order", listOf("S1E2", "MOVIE_DIGITAL", "S1E3"), merged.map { it.slot })
    }

    @Test fun labels() {
        assertEquals("S1E1", CalendarEpisodeLabel.NEW_SHOW, deriveEpisodeLabel(1, 1))
        assertEquals("S4E1", CalendarEpisodeLabel.SEASON_PREMIERE, deriveEpisodeLabel(4, 1))
        assertEquals("S4E2", CalendarEpisodeLabel.NONE, deriveEpisodeLabel(4, 2))
        assertEquals("specials", CalendarEpisodeLabel.NONE, deriveEpisodeLabel(0, 1))
        assertEquals("trakt mid finale", CalendarEpisodeLabel.MID_SEASON_FINALE, traktEpisodeLabel("mid_season_finale", 2, 8))
        assertEquals("trakt standard", CalendarEpisodeLabel.NONE, traktEpisodeLabel("standard", 2, 1))
        assertEquals("trakt missing type", CalendarEpisodeLabel.SEASON_PREMIERE, traktEpisodeLabel(null, 2, 1))
        assertEquals("tmdb finale", CalendarEpisodeLabel.SEASON_FINALE, tmdbEpisodeLabel("finale"))
        assertEquals("tmdb mid season", CalendarEpisodeLabel.MID_SEASON_FINALE, tmdbEpisodeLabel("mid_season"))
        assertEquals("tmdb standard", CalendarEpisodeLabel.NONE, tmdbEpisodeLabel("standard"))
    }

    @Test fun aTitleKeepsFifteenDaysBackAYearAheadAndFortyReleasesAcrossAllSources() {
        fun daily(source: CalendarSource, label: CalendarEpisodeLabel = CalendarEpisodeLabel.NONE) = (-30..400).map { day ->
            CalendarEntry(
                season = 1, episode = day + 31, airInstantMs = now.plus(Duration.ofDays(day.toLong())).toEpochMilli(),
                label = label, source = source
            )
        }
        val record = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"),
            sources = mapOf(
                CalendarSource.TRAKT to daily(CalendarSource.TRAKT),
                CalendarSource.TVMAZE to daily(CalendarSource.TVMAZE),
                CalendarSource.TMDB to daily(CalendarSource.TMDB, CalendarEpisodeLabel.SEASON_FINALE).drop(100)
            )
        ).bounded(now)
        assertEquals("40 releases", CALENDAR_MAX_ENTRIES_PER_TITLE, record.entries.size)
        assertEquals("starts 15 days back", now.minus(Duration.ofDays(15)).toEpochMilli(), record.entries.first().airInstantMs)
        assertTrue("each source bounded", record.sources.values.all { it.size <= CALENDAR_MAX_ENTRIES_PER_TITLE })
        assertEquals("TMDB entries all beyond the kept 40 are dropped", setOf(CalendarSource.TRAKT, CalendarSource.TVMAZE), record.sources.keys)
        assertEquals("over a year ahead dropped", 0, keptCalendarEntries(listOf(CalendarEntry(airDate = "2028-01-01")), now).size)
    }

    @Test fun theTimeFetchedLastWinsWhenTwoSourcesGiveOne() {
        val traktOld = CalendarEntry(season = 1, episode = 5, airInstantMs = sixAmUtc, source = CalendarSource.TRAKT, label = CalendarEpisodeLabel.SEASON_FINALE)
        val tvMazeMoved = CalendarEntry(season = 1, episode = 5, airInstantMs = sixAmUtc + 86_400_000L, source = CalendarSource.TVMAZE)
        val merged = mergeCalendarEntries(
            listOf(traktOld, tvMazeMoved),
            mapOf(CalendarSource.TRAKT to 1_000L, CalendarSource.TVMAZE to 2_000L)
        ).single()
        assertEquals("TVmaze fetched later wins the time", sixAmUtc + 86_400_000L, merged.airInstantMs)
        assertEquals("Trakt still owns the label", CalendarEpisodeLabel.SEASON_FINALE, merged.label)
        val traktNewer = mergeCalendarEntries(
            listOf(traktOld, tvMazeMoved),
            mapOf(CalendarSource.TRAKT to 3_000L, CalendarSource.TVMAZE to 2_000L)
        ).single()
        assertEquals("Trakt fetched later wins", sixAmUtc, traktNewer.airInstantMs)
    }

    @Test fun replacingASourceDropsAnEpisodeThatMoved() {
        val record = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"),
            sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = sixAmUtc)))
        ).withSource(CalendarSource.TVMAZE, listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = sixAmUtc + 3_600_000L)), now)
        assertEquals("moved time only", listOf(sixAmUtc + 3_600_000L), record.entries.map { it.airInstantMs })
        assertEquals("fetch time stamped", now.toEpochMilli(), record.sourceFetchedAtMs[CalendarSource.TVMAZE])
        assertTrue("an empty answer clears the source", record.withSource(CalendarSource.TVMAZE, emptyList(), now).sources.isEmpty())
    }
}
