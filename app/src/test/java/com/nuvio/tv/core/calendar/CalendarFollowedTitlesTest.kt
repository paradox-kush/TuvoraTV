// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFollowedTitlesTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    private fun show(origin: CalendarOrigin, ids: CalendarIds, at: Instant? = null, name: String? = null) =
        CalendarTitleCandidate(ids, CalendarTitleType.SERIES, origin, at, name)

    private fun film(origin: CalendarOrigin, ids: CalendarIds, at: Instant? = null) =
        CalendarTitleCandidate(ids, CalendarTitleType.MOVIE, origin, at, "Film")

    @Test fun sourcesSharingAnyIdBecomeOneTitleWithAllIdsAndOrigins() {
        val title = followCalendarTitles(
            listOf(
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt1"), daysAgo(40), "Night Shift"),
                show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt1", trakt = 7), daysAgo(3)),
                show(CalendarOrigin.WATCHED, CalendarIds(trakt = 7, tmdb = 500), daysAgo(10)),
                show(CalendarOrigin.TRAKT_CALENDAR, CalendarIds(tmdb = 500, tvdb = 42))
            ),
            CalendarFollowRules(),
            now
        ).single()
        assertEquals("merged ids", CalendarIds(imdb = "tt1", tmdb = 500, tvdb = 42, trakt = 7), title.ids)
        assertEquals("all origins", setOf(CalendarOrigin.LIBRARY, CalendarOrigin.PROGRESS, CalendarOrigin.WATCHED, CalendarOrigin.TRAKT_CALENDAR), title.origins)
        assertEquals("latest activity", daysAgo(3), title.lastActivity)
        assertEquals("name", "Night Shift", title.name)
        assertEquals("key", "series:imdb:tt1", title.key)
        assertEquals("content id", "tt1", title.contentId)
    }

    @Test fun aFilmAndAShowWithTheSameTmdbNumberStayApart() {
        val titles = followCalendarTitles(
            listOf(show(CalendarOrigin.LIBRARY, CalendarIds(tmdb = 1)), film(CalendarOrigin.LIBRARY, CalendarIds(tmdb = 1))),
            CalendarFollowRules(),
            now
        )
        assertEquals("two titles", 2, titles.size)
    }

    @Test fun startedShowsUntouchedForAYearAreLeftOutUnlessTheBellIsOnOrTheyAreInTheLibrary() {
        val keys = followCalendarTitles(
            listOf(
                show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt1"), daysAgo(400)),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt2"), daysAgo(400)),
                show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt3"), daysAgo(300)),
                show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt4"), daysAgo(500)),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt4"), daysAgo(900))
            ),
            CalendarFollowRules(bellOn = setOf("series:imdb:tt2")),
            now
        ).map { it.key }.toSet()
        assertEquals("idle tt1 left out", setOf("series:imdb:tt2", "series:imdb:tt3", "series:imdb:tt4"), keys)
    }

    @Test fun hiddenTitlesAndWatchedOnlyFilmsAreLeftOut() {
        val keys = followCalendarTitles(
            listOf(
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt1")),
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt2", tmdb = 22)),
                film(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt3"), daysAgo(1)),
                film(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt4"))
            ),
            CalendarFollowRules(hiddenTitleKeys = setOf("series:tmdb:22")),
            now
        ).map { it.key }.toSet()
        assertEquals("hidden by another id; watched film not followed", setOf("series:imdb:tt1", "movie:imdb:tt4"), keys)
    }

    @Test fun aBellAloneFollowsATitleAndBellsComeFirstWithinTheCapOfThreeHundred() {
        val many = (1..310).map { show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt$it"), daysAgo(it.toLong())) }
        val titles = followCalendarTitles(many, CalendarFollowRules(bellOn = setOf("series:imdb:tt999")), now)
        assertEquals("cap", CALENDAR_MAX_TITLES, titles.size)
        assertEquals("bell first", "series:imdb:tt999", titles.first().key)
        assertTrue("most recent kept", titles.any { it.key == "series:imdb:tt1" })
        assertTrue("oldest dropped", titles.none { it.key == "series:imdb:tt310" })
    }

    @Test fun aStoredRecordKeepsItsKeyAndDatesWhenFollowedAgain() {
        val stored = CalendarTitleRecord(
            "series:tmdb:5", CalendarTitleType.SERIES, CalendarIds(tmdb = 5), nextRefreshAtMs = 123L,
            sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 1, airDate = "2026-10-10")))
        )
        val followed = followCalendarTitles(listOf(show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt5", tmdb = 5))), CalendarFollowRules(), now)
        val record = reconcileCalendarRecords(followed, listOf(stored)).single()
        assertEquals("key kept", "series:tmdb:5", record.key)
        assertEquals("due time kept", 123L, record.nextRefreshAtMs)
        assertEquals("ids learned", CalendarIds(imdb = "tt5", tmdb = 5), record.ids)
        val fresh = reconcileCalendarRecords(followCalendarTitles(listOf(show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt8"))), CalendarFollowRules(), now), listOf(stored)).single()
        assertEquals("a new title is due at once", 0L, fresh.nextRefreshAtMs)
    }

    @Test fun unreadSourcesCarryTheirStoredTitles() {
        val stored = listOf(
            CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"), origins = setOf(CalendarOrigin.LIBRARY)),
            CalendarTitleRecord("series:imdb:tt2", CalendarTitleType.SERIES, CalendarIds(imdb = "tt2"), origins = setOf(CalendarOrigin.WATCHED))
        )
        assertEquals("library unread", listOf(CalendarIds(imdb = "tt1")), carriedCalendarCandidates(stored, setOf(CalendarOrigin.LIBRARY)).map { it.ids })
        assertTrue("all read", carriedCalendarCandidates(stored, emptySet()).isEmpty())
    }

    @Test fun watchedIndexMatchesByAnyId() {
        val index = CalendarWatchedIndex.Builder().add("tt1:2:3", 2, 3).add("tmdb:9", 1, 1).add("kitsu:4", 1, 1).build()
        assertTrue("imdb", index.isWatched(CalendarIds(imdb = "tt1", tmdb = 5), 2, 3))
        assertTrue("tmdb", index.isWatched(CalendarIds(imdb = "tt7", tmdb = 9), 1, 1))
        assertTrue("unwatched", !index.isWatched(CalendarIds(imdb = "tt1"), 2, 4))
    }
}
