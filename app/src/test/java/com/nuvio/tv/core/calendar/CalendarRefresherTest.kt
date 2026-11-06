package com.nuvio.tv.core.calendar

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresher end to end against fakes: fix 1 (a run whose every fetch failed is Failed) and the idle-slate
 * rule (nothing due and Trakt fresh: zero remote calls).
 */
class CalendarRefresherTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val dir: File = Files.createTempDirectory("calendar-test").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private class FakeInputs(
        @Volatile var candidates: List<CalendarTitleCandidate>,
        private val signedIn: Boolean = false,
        private val zone: ZoneId = ZoneOffset.UTC
    ) : CalendarRefreshInputs {
        override val activeProfileId = MutableStateFlow(1)
        override val settings: StateFlow<CalendarSettings?> = MutableStateFlow(CalendarSettings(profileId = 1))
        override suspend fun settings(profileId: Int) = CalendarSettings(profileId = profileId)
        override suspend fun followed(profileId: Int) = CalendarFollowedSnapshot(candidates)
        override fun traktSignedIn() = signedIn
        override fun region() = "US"
        override fun zone(): ZoneId = zone
    }

    /** Counts every remote call; [fail] makes every call throw as an unreachable network would. */
    private class FakeRemote(@Volatile var fail: Boolean) : CalendarRemoteSources {
        val calls = AtomicInteger(0)
        private fun tick() {
            calls.incrementAndGet()
            if (fail) throw IOException("offline")
        }
        override suspend fun tvMazeLookup(ids: CalendarIds): TvMazeLookup { tick(); return TvMazeLookup.Found(7, ids) }
        override suspend fun tvMazeShow(id: Int, now: Instant): CalendarShowDates {
            tick()
            return CalendarShowDates(
                status = CalendarShowStatus.RUNNING,
                entries = listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = now.plus(Duration.ofDays(2)).toEpochMilli()))
            )
        }
        override suspend fun traktNextEpisode(ids: CalendarIds): CalendarShowDates? { tick(); return null }
        override suspend fun traktMyShows(today: LocalDate): List<TraktCalendarItem>? = if (fail) { calls.incrementAndGet(); null } else { tick(); emptyList() }
        override suspend fun tmdbFind(imdb: String, type: CalendarTitleType): Long? { tick(); return null }
        override suspend fun tmdbShow(tmdbId: Long): CalendarShowDates { tick(); return CalendarShowDates() }
        override suspend fun tmdbSeason(tmdbId: Long, season: Int): List<CalendarEntry> { tick(); return emptyList() }
        override suspend fun tmdbFilm(tmdbId: Long, region: String): CalendarShowDates { tick(); return CalendarShowDates() }
    }

    private fun show(imdb: String) =
        CalendarTitleCandidate(CalendarIds(imdb = imdb), CalendarTitleType.SERIES, CalendarOrigin.LIBRARY, now, imdb, imdb)

    private fun storage() = CalendarStorage({ dir }, { now.toEpochMilli() })

    private fun runOnce(refresher: CalendarRefresher): CalendarRunResult = runBlocking {
        refresher.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        withTimeout(10_000) { refresher.state.first { it.lastRun != null && !it.refreshing } }.lastRun!!
    }

    @Test fun aRunWhoseEveryFetchFailedIsFailedNotAllQuiet() {
        val remote = FakeRemote(fail = true)
        val refresher = CalendarRefresher(storage(), FakeInputs(listOf(show("tt1"), show("tt2"))), remote, clock, scope)
        val result = runOnce(refresher)
        assertTrue("fetches were attempted", result.attempted >= 2)
        assertEquals("none answered", 0, result.succeeded)
        assertTrue("so the run FAILED", result.failed)
        assertTrue("published to the screen", refresher.state.value.lastRun?.failed == true)
    }

    @Test fun aRunWithAnswersIsNotFailed() {
        val refresher = CalendarRefresher(storage(), FakeInputs(listOf(show("tt1"))), FakeRemote(fail = false), clock, scope)
        val result = runOnce(refresher)
        assertFalse("answered", result.failed)
        assertEquals("dates landed", 1, refresher.state.value.titles.single().entries.size)
    }

    @Test fun anIdleSlateMakesZeroRemoteCalls() {
        val store = storage()
        val fresh = CalendarTitleRecord(
            key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
            origins = setOf(CalendarOrigin.LIBRARY),
            nextRefreshAtMs = now.plus(Duration.ofHours(5)).toEpochMilli(),
            sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = now.plus(Duration.ofDays(3)).toEpochMilli())))
        )
        runBlocking {
            assertTrue(
                "seeded",
                store.save(1, CalendarSnapshot(titles = listOf(fresh), traktCalendarFetchedAtMs = now.minus(Duration.ofHours(1)).toEpochMilli()), store.generation(), force = true)
            )
        }
        val remote = FakeRemote(fail = false)
        val refresher = CalendarRefresher(store, FakeInputs(listOf(show("tt1")), signedIn = true), remote, clock, scope)
        val result = runOnce(refresher)
        assertEquals("no title due and Trakt fresh: zero remote calls", 0, remote.calls.get())
        assertEquals("nothing attempted", 0, result.attempted)
        assertFalse("an idle slate is not a failure", result.failed)
        assertNotNull("stored dates still shown", refresher.state.value.titles.singleOrNull()?.entries?.singleOrNull())
    }

    @Test fun aTitleCoveredByTheFreshTraktCalendarIsNotFetchedAgain() {
        val store = storage()
        val covered = CalendarTitleRecord(
            key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"),
            origins = setOf(CalendarOrigin.LIBRARY), nextRefreshAtMs = 0L,
            sources = mapOf(CalendarSource.TRAKT to listOf(CalendarEntry(season = 1, episode = 2, airInstantMs = now.plus(Duration.ofDays(1)).toEpochMilli(), source = CalendarSource.TRAKT)))
        )
        runBlocking {
            store.save(1, CalendarSnapshot(titles = listOf(covered), traktCalendarFetchedAtMs = now.minus(Duration.ofHours(1)).toEpochMilli()), store.generation(), force = true)
        }
        val remote = FakeRemote(fail = false)
        val result = runOnce(CalendarRefresher(store, FakeInputs(listOf(show("tt1")), signedIn = true), remote, clock, scope))
        assertEquals("due but covered: zero calls", 0, remote.calls.get())
        assertEquals("not counted as a fetch", 0, result.attempted)
    }

    // ---- device finding: a failure survives a run that asks nothing; Retry re-asks the failed title ----

    private fun runAgain(refresher: CalendarRefresher, reason: CalendarRefreshReason): CalendarRunResult = runBlocking {
        val before = refresher.state.value.lastRun
        refresher.requestRefresh(reason)
        withTimeout(10_000) { refresher.state.first { it.lastRun !== before && !it.refreshing } }.lastRun!!
    }

    @Test fun openingTheCalendarWhileTheFailedTitleBacksOffKeepsTheFailureAndAsksNothing() {
        val remote = FakeRemote(fail = true)
        val refresher = CalendarRefresher(storage(), FakeInputs(listOf(show("tt1"))), remote, clock, scope)
        assertTrue("the first run failed", runOnce(refresher).failed)
        val calls = remote.calls.get()

        val second = runAgain(refresher, CalendarRefreshReason.CALENDAR_OPENED)
        assertEquals("the failed title is in its back-off: nothing asked", calls, remote.calls.get())
        assertEquals("asked nothing", 0, second.attempted)
        assertTrue("so the failure is kept, not replaced by an all-quiet page", second.failed)
        assertTrue("published to the screen", refresher.state.value.lastRun?.failed == true)
    }

    @Test fun theLastRunsFailureSurvivesARestart() {
        val store = storage()
        val remote = FakeRemote(fail = true)
        assertTrue("the first run failed", runOnce(CalendarRefresher(store, FakeInputs(listOf(show("tt1"))), remote, clock, scope)).failed)
        val calls = remote.calls.get()

        // A restart: a new refresher over the same storage; the failed title is still in its back-off.
        val restarted = CalendarRefresher(store, FakeInputs(listOf(show("tt1"))), remote, clock, scope)
        restarted.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        val result = runBlocking {
            withTimeoutOrNull(5_000) { restarted.state.first { it.loaded && !it.refreshing && it.lastRun?.attempted == 0 } }
        }?.lastRun
        assertNotNull("the run after the restart finished", result)
        assertEquals("nothing is due, nothing asked", calls, remote.calls.get())
        assertTrue("the failure still shows after the restart", result!!.failed)
        assertTrue("published to the screen", restarted.state.value.lastRun?.failed == true)
    }

    @Test fun retryAsksTheBackedOffFailedTitleAndTheCalendarLoads() {
        val remote = FakeRemote(fail = true)
        val refresher = CalendarRefresher(storage(), FakeInputs(listOf(show("tt1"))), remote, clock, scope)
        runOnce(refresher)
        runAgain(refresher, CalendarRefreshReason.CALENDAR_OPENED)
        val calls = remote.calls.get()

        remote.fail = false
        val retried = runAgain(refresher, CalendarRefreshReason.RETRY)
        assertTrue("Retry fetched the failed title again", remote.calls.get() > calls)
        assertFalse("the network is back: the run answered", retried.failed)
        assertEquals("dates landed", 1, refresher.state.value.titles.single().entries.size)
    }

    @Test fun theCarriedFailureClearsWhenTheFailingTitleIsNoLongerFollowed() {
        val remote = FakeRemote(fail = true)
        val inputs = FakeInputs(listOf(show("tt1")))
        val refresher = CalendarRefresher(storage(), inputs, remote, clock, scope)
        assertTrue(runOnce(refresher).failed)
        val calls = remote.calls.get()

        inputs.candidates = emptyList()
        val after = runAgain(refresher, CalendarRefreshReason.SETTINGS_CHANGED)
        assertEquals("nothing left to ask", calls, remote.calls.get())
        assertFalse("the bell is off, nothing is failing", after.failed)
    }
}
