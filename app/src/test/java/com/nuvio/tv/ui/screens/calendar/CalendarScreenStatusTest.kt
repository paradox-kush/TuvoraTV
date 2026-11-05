package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarLoad
import com.nuvio.tv.core.calendar.CalendarLoadStatus
import com.nuvio.tv.core.calendar.CalendarRunResult
import com.nuvio.tv.core.calendar.CalendarState
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fix 1 (failed is never "all quiet") and fix 2 (the deadline ends the wait) at the status function. */
class CalendarScreenStatusTest {
    private val title = CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"))
    private val failedRun = CalendarRunResult(finishedAtMs = 0, attempted = 4, succeeded = 0)
    private val goodRun = CalendarRunResult(finishedAtMs = 0, attempted = 4, succeeded = 4)

    private fun status(state: CalendarState, hasRows: Boolean, load: CalendarLoadStatus = CalendarLoadStatus.Idle, nowMs: Long = 0) =
        calendarScreenStatus(state, settingsLoaded = true, load = load, hasRows = hasRows, nowMs = nowMs)

    @Test fun allFetchesFailedWithNothingCachedIsAFullErrorNotAllQuiet() {
        val state = CalendarState(loaded = true, titles = listOf(title), lastRun = failedRun)
        assertEquals("full error with Retry", CalendarScreenStatus.Failed(timedOut = false), status(state, hasRows = false))
    }

    @Test fun allFetchesFailedWithCachedRowsShowsThemWithAnInlineRetry() {
        val state = CalendarState(loaded = true, titles = listOf(title), lastRun = failedRun)
        assertEquals("rows + Couldn't update", CalendarScreenStatus.Ready(updateFailed = true, refreshing = false), status(state, hasRows = true))
    }

    @Test fun aGoodRunWithNothingAheadIsAllQuietAndNoTitlesIsNothingFollowed() {
        assertEquals("all quiet", CalendarScreenStatus.Empty(nothingFollowed = false),
            status(CalendarState(loaded = true, titles = listOf(title), lastRun = goodRun), hasRows = false))
        assertEquals("nothing followed", CalendarScreenStatus.Empty(nothingFollowed = true),
            status(CalendarState(loaded = true, lastRun = goodRun), hasRows = false))
    }

    @Test fun loadingBeforeTheFirstRunFinishesAndTimedOutAfterTheDeadline() {
        val waiting = CalendarState(loaded = true, refreshing = true, remaining = 3)
        val load = CalendarLoad.begin(nowMs = 0)
        assertEquals("loading", CalendarScreenStatus.Loading(3), status(waiting, hasRows = false, load = load, nowMs = 29_999))
        assertEquals("timed out", CalendarScreenStatus.Failed(timedOut = true), status(waiting, hasRows = false, load = load, nowMs = 30_000))
        assertEquals("cached rows + inline note when timed out", CalendarScreenStatus.Ready(updateFailed = true, refreshing = true),
            status(waiting, hasRows = true, load = load, nowMs = 30_000))
    }
}
