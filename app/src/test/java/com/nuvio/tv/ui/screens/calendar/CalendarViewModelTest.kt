package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarRefreshReason
import com.nuvio.tv.core.calendar.CalendarRunResult
import com.nuvio.tv.core.calendar.CalendarSectionKind
import com.nuvio.tv.core.calendar.CalendarSettings
import com.nuvio.tv.core.calendar.CalendarSource
import com.nuvio.tv.core.calendar.CalendarState
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarViewMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @get:Rule val mainRule = MainDispatcherRule(dispatcher)

    /** Wall time = [base] + virtual time, so deadlines run on the test scheduler. */
    private val base = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()

    private inner class FakeData(state: CalendarState, settings: CalendarSettings? = CalendarSettings(profileId = 1)) : CalendarScreenData {
        override val state = MutableStateFlow(state)
        override val settings = MutableStateFlow(settings)
        val refreshes = mutableListOf<CalendarRefreshReason>()
        override fun nowMs(): Long = base + dispatcher.scheduler.currentTime
        override fun systemZone(): ZoneId = ZoneOffset.UTC
        override fun requestRefresh(reason: CalendarRefreshReason) { refreshes += reason }
        override suspend fun setBell(titleKey: String, on: Boolean) {}
        override suspend fun setHidden(titleKey: String) {}
        override suspend fun unhideAll() {}
        override suspend fun setWindowDays(days: Int) {}
        override suspend fun setZoneOverride(zone: String?) {}
        override suspend fun setCornerMessage(enabled: Boolean) {}
        override suspend fun setComingUpRow(enabled: Boolean) {}
        override suspend fun setViewMode(mode: CalendarViewMode) {}
        override suspend fun setFilters(filters: Set<com.nuvio.tv.core.calendar.CalendarFilter>) {
            settings.value = settings.value?.copy(filters = filters)
        }
        var heldGames: List<com.nuvio.tv.core.calendar.CalendarGameItem> = emptyList()
        val watched = mutableListOf<String>()
        override suspend fun games() = heldGames
        override val gameChanges = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        override fun watchGame(gameId: String) { watched += gameId }
    }

    private fun viewModel(data: FakeData) = CalendarViewModel(data, data::nowMs, stallMs = 30_000L)

    // ---- Fix 2: a refresh that never returns still ends the wait.

    @Test fun aRefreshThatNeverReturnsShowsFailedTimedOutAfterThirtySeconds() = runTest(dispatcher) {
        val data = FakeData(CalendarState(loaded = true, refreshing = true, remaining = 5))
        val vm = viewModel(data)
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals("still waiting at 29.999 s", CalendarScreenStatus.Loading(5), vm.uiState.value.status)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("ended at the deadline though the work never returned", CalendarScreenStatus.Failed(timedOut = true), vm.uiState.value.status)
    }

    @Test fun eachFinishedTitleRestartsTheDeadline() = runTest(dispatcher) {
        val data = FakeData(CalendarState(loaded = true, refreshing = true, remaining = 5))
        val vm = viewModel(data)
        runCurrent()
        advanceTimeBy(20_000)
        data.state.value = data.state.value.copy(progressTicks = 1, remaining = 4)
        runCurrent()
        advanceTimeBy(25_000) // 45 s since start, 25 s since the last title
        runCurrent()
        assertEquals("progress pushed the deadline", CalendarScreenStatus.Loading(4), vm.uiState.value.status)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals("30 s without progress", CalendarScreenStatus.Failed(timedOut = true), vm.uiState.value.status)
    }

    @Test fun retryRestartsTheWaitAndAlwaysRuns() = runTest(dispatcher) {
        val data = FakeData(CalendarState(loaded = true, refreshing = true))
        val vm = viewModel(data)
        runCurrent()
        advanceTimeBy(31_000)
        runCurrent()
        vm.retry()
        runCurrent()
        assertEquals("loading again", CalendarScreenStatus.Loading(0), vm.uiState.value.status)
        assertEquals("retry asked", listOf(CalendarRefreshReason.RETRY), data.refreshes)
    }

    // ---- Fix 3: opening the Calendar is throttled.

    @Test fun openingRefreshesOnlyWhenNeverRunFailedOrFifteenMinutesOld() = runTest(dispatcher) {
        val data = FakeData(CalendarState(loaded = true))
        val vm = viewModel(data)
        runCurrent()
        vm.onScreenShown()
        assertEquals("never ran", listOf(CalendarRefreshReason.CALENDAR_OPENED), data.refreshes)

        data.state.value = CalendarState(loaded = true, lastRun = CalendarRunResult(data.nowMs(), attempted = 2, succeeded = 2))
        advanceTimeBy(Duration.ofMinutes(10).toMillis())
        vm.onScreenShown()
        assertEquals("10 min after a good run: nothing", 1, data.refreshes.size)

        advanceTimeBy(Duration.ofMinutes(5).toMillis())
        vm.onScreenShown()
        assertEquals("15 min after: refresh", 2, data.refreshes.size)

        data.state.value = CalendarState(loaded = true, lastRun = CalendarRunResult(data.nowMs(), attempted = 2, succeeded = 0))
        vm.onScreenShown()
        assertEquals("right after a failed run: refresh", 3, data.refreshes.size)
    }

    // ---- Sports: games join the list, the filter hides them, Watch now hands the id to Sports.

    @Test fun gamesJoinTheListAndTheSportsFilterHidesThem() = runTest(dispatcher) {
        val data = FakeData(CalendarState(loaded = true, lastRun = CalendarRunResult(0, 1, 1)))
        data.heldGames = listOf(
            com.nuvio.tv.core.calendar.CalendarGameItem(
                id = "e1", league = "Premier League", home = "Arsenal", away = "Chelsea",
                kickoffMs = base + Duration.ofMinutes(10).toMillis()
            )
        )
        val vm = viewModel(data)
        runCurrent()
        val row = vm.uiState.value.list.entries.filterIsInstance<CalendarRowEntry>().single().row
        assertEquals("a game row", CalendarRowKind.GAME, row.kind)
        assertEquals("both teams", "Arsenal v Chelsea", row.name)
        assertEquals("10 min to kick-off: watchable", true, row.watchable)
        vm.watchGame(row)
        assertEquals("handed to Sports", listOf("e1"), data.watched)
        vm.toggleFilter(com.nuvio.tv.core.calendar.CalendarFilter.SPORTS)
        runCurrent()
        assertEquals("Sports filter off: no rows", true, vm.uiState.value.list.isEmpty)
    }

    // ---- Fix 4: every day and time follows the zone override, not the box's zone.

    @Test fun theOverrideZoneDecidesTodayAndTheSection() = runTest(dispatcher) {
        // 2026-11-05 05:00 UTC is Wed 4 Nov 21:00 in Los Angeles: tomorrow in UTC, tonight in LA.
        val show = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"), name = "Harbour",
            sources = mapOf(CalendarSource.TVMAZE to listOf(CalendarEntry(season = 1, episode = 1,
                airInstantMs = Instant.parse("2026-11-05T05:00:00Z").toEpochMilli())))
        )
        val state = CalendarState(loaded = true, titles = listOf(show), lastRun = CalendarRunResult(0, 1, 1))
        val inUtc = viewModel(FakeData(state))
        val inLa = viewModel(FakeData(state, CalendarSettings(profileId = 1, zoneOverride = "America/Los_Angeles")))
        runCurrent()
        fun firstSection(vm: CalendarViewModel) =
            vm.uiState.value.list.entries.filterIsInstance<CalendarHeaderEntry>().first().kind
        assertEquals("box zone (UTC): tomorrow", CalendarSectionKind.TOMORROW, firstSection(inUtc))
        assertEquals("override zone (LA): today", CalendarSectionKind.TODAY, firstSection(inLa))
        assertEquals("today in LA", LocalDate.of(2026, 11, 4), inLa.uiState.value.today)
        assertEquals("resolved zone", ZoneId.of("America/Los_Angeles"), inLa.uiState.value.zone)
    }
}
