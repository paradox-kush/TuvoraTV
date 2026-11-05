package com.nuvio.tv.core.calendar

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions behind fixes 1-3. */
class CalendarLoadPolicyTest {

    // ---- Fix 1: all fetches failed is a failure, never "all quiet".

    @Test fun aRunThatTriedAndGotNoAnswerFailed() {
        assertTrue("2 tried, 0 answered", calendarRunFailed(attempted = 2, succeeded = 0))
        assertTrue("result flag", CalendarRunResult(finishedAtMs = 0, attempted = 1, succeeded = 0).failed)
    }

    @Test fun aRunWithAnyAnswerOrNothingToAskDidNotFail() {
        assertFalse("1 of 3 answered", calendarRunFailed(attempted = 3, succeeded = 1))
        assertFalse("idle slate asked nothing", calendarRunFailed(attempted = 0, succeeded = 0))
    }

    // ---- Device finding: a run that asks nothing keeps the failure of the run before it.

    private fun failing() = CalendarTitleRecord(
        key = "series:imdb:tt1", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt1"), failures = 1
    )

    @Test fun aRunThatAskedNothingFailsOnlyWhenItCarriesAFailure() {
        assertTrue("carried", calendarRunFailed(attempted = 0, succeeded = 0, carriedFailure = true))
        assertTrue("result flag", CalendarRunResult(finishedAtMs = 0, attempted = 0, succeeded = 0, carriedFailure = true).failed)
        assertFalse("not carried", calendarRunFailed(attempted = 0, succeeded = 0, carriedFailure = false))
    }

    @Test fun aCarriedFailureNeverOverridesARunThatGotAnAnswer() {
        assertFalse("1 of 2 answered", calendarRunFailed(attempted = 2, succeeded = 1, carriedFailure = true))
    }

    @Test fun aFailureIsCarriedOnlyWhileAFollowedTitleIsStillFailing() {
        val healthy = failing().copy(failures = 0)
        assertTrue("still failing", calendarCarriesFailure(bad(0), listOf(healthy, failing())))
        assertFalse("nothing failing any more", calendarCarriesFailure(bad(0), listOf(healthy)))
        assertFalse("the bell was turned off", calendarCarriesFailure(bad(0), emptyList()))
        assertFalse("the last run did not fail", calendarCarriesFailure(ok(0), listOf(failing())))
        assertFalse("no last run", calendarCarriesFailure(null, listOf(failing())))
        assertTrue(
            "a kept failure keeps being carried",
            calendarCarriesFailure(CalendarRunResult(0, attempted = 0, succeeded = 0, carriedFailure = true), listOf(failing()))
        )
    }

    // ---- Fix 3: opening the Calendar refreshes only when it is worth it.

    private val min = Duration.ofMinutes(1).toMillis()
    private fun ok(at: Long) = CalendarRunResult(finishedAtMs = at, attempted = 3, succeeded = 3)
    private fun bad(at: Long) = CalendarRunResult(finishedAtMs = at, attempted = 3, succeeded = 0)

    @Test fun openingRefreshesWhenNeverRunThisProcess() {
        assertTrue("never ran", CalendarRefreshGate.onCalendarOpened(lastRun = null, running = false, nowMs = 0))
    }

    @Test fun openingRefreshesAfterAFailedRunAtOnce() {
        assertTrue("failed 1 min ago", CalendarRefreshGate.onCalendarOpened(bad(0), running = false, nowMs = 1 * min))
    }

    @Test fun openingWithinFifteenMinutesOfAGoodRunDoesNothing() {
        assertFalse("ok 14 min ago", CalendarRefreshGate.onCalendarOpened(ok(0), running = false, nowMs = 14 * min))
        assertTrue("ok 15 min ago", CalendarRefreshGate.onCalendarOpened(ok(0), running = false, nowMs = 15 * min))
    }

    @Test fun openingWhileARunIsGoingDoesNotQueueAnother() {
        assertFalse("running", CalendarRefreshGate.onCalendarOpened(lastRun = null, running = true, nowMs = 0))
    }

    @Test fun homeRefreshesOnlyWhenHomeUsesTheCalendarAndAtMostEverySixHours() {
        val hour = Duration.ofHours(1).toMillis()
        assertFalse("no bell, no row", CalendarRefreshGate.onHomeResumed(null, running = false, homeUsesCalendar = false, nowMs = 0))
        assertTrue("first time", CalendarRefreshGate.onHomeResumed(null, running = false, homeUsesCalendar = true, nowMs = 0))
        assertFalse("5 h after", CalendarRefreshGate.onHomeResumed(ok(0), running = false, homeUsesCalendar = true, nowMs = 5 * hour))
        assertFalse("failed 1 h ago is not re-asked from Home", CalendarRefreshGate.onHomeResumed(bad(0), running = false, homeUsesCalendar = true, nowMs = hour))
        assertTrue("6 h after", CalendarRefreshGate.onHomeResumed(ok(0), running = false, homeUsesCalendar = true, nowMs = 6 * hour))
    }

    // ---- Fix 2: the loading state carries a stall deadline.

    @Test fun loadingPastItsDeadlineShowsAsTimedOutWhateverTheWorkDoes() {
        val loading = CalendarLoad.begin(nowMs = 1_000)
        assertEquals("deadline", 31_000L, loading.deadlineAtMs)
        assertEquals("before", loading, CalendarLoad.effectiveAt(loading, 30_999))
        assertEquals("at deadline", CalendarLoadStatus.Failed(timedOut = true), CalendarLoad.effectiveAt(loading, 31_000))
    }

    @Test fun eachFinishedTitleRestartsTheStallDeadline() {
        val pushed = CalendarLoad.progressed(CalendarLoad.begin(nowMs = 0), nowMs = 20_000)
        assertEquals("pushed", CalendarLoadStatus.Loading(0, 50_000), pushed)
        assertEquals("not loading stays", CalendarLoadStatus.Loaded, CalendarLoad.progressed(CalendarLoadStatus.Loaded, 5))
    }
}
