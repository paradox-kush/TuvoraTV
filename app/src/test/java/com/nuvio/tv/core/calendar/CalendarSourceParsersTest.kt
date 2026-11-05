// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import com.nuvio.tv.data.remote.dto.calendar.CalendarTmdbEpisodeDto
import com.nuvio.tv.data.remote.dto.calendar.CalendarTmdbReleaseDatesDto
import com.nuvio.tv.data.remote.dto.calendar.CalendarTmdbShowDto
import com.nuvio.tv.data.remote.dto.calendar.TraktCalendarEpisodeDto
import com.nuvio.tv.data.remote.dto.calendar.TraktCalendarShowDto
import com.nuvio.tv.data.remote.dto.calendar.TvMazeEpisodeDto
import com.nuvio.tv.data.remote.dto.calendar.TvMazeShowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.squareup.moshi.Moshi
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSourceParsersTest {
    private val moshi = Moshi.Builder().build()

    private val releases = moshi.adapter(CalendarTmdbReleaseDatesDto::class.java).fromJson(
        """
        {"id":1,"results":[
          {"iso_3166_1":"AU","release_dates":[{"certification":"M","release_date":"2026-10-08T00:00:00.000Z","type":3}]},
          {"iso_3166_1":"US","release_dates":[
            {"release_date":"2026-09-30T00:00:00.000Z","type":1},
            {"release_date":"2026-10-02T00:00:00.000Z","type":3},
            {"release_date":"2026-12-20T00:00:00.000Z","type":4},
            {"release_date":"2026-12-01T00:00:00.000Z","type":4}]},
          {"iso_3166_1":"DE","release_dates":[{"release_date":"2026-10-15T00:00:00.000Z","type":2}]}
        ]}
        """.trimIndent()
    )!!.results!!

    @Test fun filmCinemaFromTheBoxRegionAndDigitalFromTheUsWhenTheRegionHasNone() {
        val entries = tmdbFilmReleaseEntries(releases, "AU")
        assertEquals(
            "AU cinema, US digital",
            listOf(CalendarEntryKind.MOVIE_THEATRICAL to "2026-10-08", CalendarEntryKind.MOVIE_DIGITAL to "2026-12-01"),
            entries.map { it.kind to it.airDate }
        )
        assertTrue("dates only, from TMDB", entries.all { !it.timeKnown && it.source == CalendarSource.TMDB })
        assertEquals("limited when no wide", "2026-10-15", tmdbFilmReleaseEntries(releases, "DE").first { it.kind == CalendarEntryKind.MOVIE_THEATRICAL }.airDate)
        assertEquals("unknown region uses US", listOf("2026-10-02", "2026-12-01"), tmdbFilmReleaseEntries(releases, "NZ").map { it.airDate })
    }

    @Test fun tmdbNextAndLastEpisodeToAirAreRead() {
        val details = moshi.adapter(CalendarTmdbShowDto::class.java).fromJson(
            """
            {"id":30,"name":"Night Shift","status":"Returning Series","networks":[{"id":49,"name":"HBO"}],
             "last_episode_to_air":{"id":1,"name":"Static","air_date":"2026-10-01","episode_number":5,"season_number":2,"episode_type":"standard"},
             "next_episode_to_air":{"id":2,"name":"Graveyard","air_date":"2026-10-08","episode_number":6,"season_number":2,"episode_type":"finale"}}
            """.trimIndent()
        )!!
        val next = tmdbEpisodeEntry(details.nextEpisodeToAir!!)!!
        assertEquals("slot", "S2E6", next.slot)
        assertEquals("date", "2026-10-08", next.airDate)
        assertNull("no time", next.airInstantMs)
        assertEquals("finale", CalendarEpisodeLabel.SEASON_FINALE, next.label)
        assertEquals("status", CalendarShowStatus.RUNNING, tmdbShowStatus(details.status))
        assertNull("no date, no entry", tmdbEpisodeEntry(CalendarTmdbEpisodeDto(airDate = null, seasonNumber = 2, episodeNumber = 6)))
        assertEquals("statuses", listOf(CalendarShowStatus.IN_PRODUCTION, CalendarShowStatus.CANCELLED, CalendarShowStatus.ENDED),
            listOf("In Production", "Canceled", "Ended").map(::tmdbShowStatus))
    }

    @Test fun tvMazeKeepsAnExactTimeOnlyWhenTheEpisodeHasAnAirTime() {
        val timed = tvMazeEpisodeEntry(TvMazeEpisodeDto(season = 3, number = 1, airdate = "2026-10-05", airtime = "21:00", airstamp = "2026-10-06T01:00:00+00:00"))!!
        assertEquals("exact", Instant.parse("2026-10-06T01:00:00Z").toEpochMilli(), timed.airInstantMs)
        assertNull("no plain date", timed.airDate)
        assertEquals("premiere", CalendarEpisodeLabel.SEASON_PREMIERE, timed.label)
        val streaming = tvMazeEpisodeEntry(TvMazeEpisodeDto(season = 1, number = 2, airdate = "2026-10-09", airtime = "", airstamp = "2026-10-09T12:00:00+00:00"))!!
        assertNull("date only", streaming.airInstantMs)
        assertEquals("plain date", "2026-10-09", streaming.airDate)
        assertNull("insignificant special", tvMazeEpisodeEntry(TvMazeEpisodeDto(season = 1, number = 3, type = "insignificant_special", airdate = "2026-10-09")))
        val dates = tvMazeShowDates(
            moshi.adapter(TvMazeShowDto::class.java).fromJson(
                """{"id":7,"name":"Harbour","status":"Running","network":{"name":"ABC"},"externals":{"imdb":"tt7","thetvdb":70},
                   "image":{"medium":"http://x/p.jpg"}}"""
            )!!,
            emptyList()
        )
        assertEquals("ids", CalendarIds(imdb = "tt7", tvdb = 70), dates.ids)
        assertEquals("https poster", "https://x/p.jpg", dates.poster)
        assertEquals("running", CalendarShowStatus.RUNNING, dates.status)
    }

    @Test fun traktCalendarRowsBecomeTimedEntriesOncePerShowAndEpisode() {
        val row = TraktCalendarShowDto(
            firstAired = "2026-10-06T01:00:00.000Z",
            episode = TraktCalendarEpisodeDto(season = 2, number = 10, title = "End", episodeType = "season_finale"),
            show = TraktShowDto(title = "Harbour", ids = TraktIdsDto(trakt = 9, imdb = "tt7", tmdb = 70), status = "returning series")
        )
        val items = traktCalendarItems(listOf(row, row, TraktCalendarShowDto(show = TraktShowDto(ids = TraktIdsDto()))))
        val item = items.single()
        assertEquals("ids", CalendarIds(imdb = "tt7", tmdb = 70, trakt = 9), item.ids)
        assertEquals("time", Instant.parse("2026-10-06T01:00:00Z").toEpochMilli(), item.entry.airInstantMs)
        assertEquals("label", CalendarEpisodeLabel.SEASON_FINALE, item.entry.label)
        assertEquals("status", CalendarShowStatus.RUNNING, item.status)
        assertEquals("two 33-day calls around today",
            listOf(LocalDate.of(2026, 9, 28) to 33, LocalDate.of(2026, 10, 31) to 33), traktCalendarRanges(LocalDate.of(2026, 10, 5)))
    }
}
