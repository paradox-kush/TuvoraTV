package com.nuvio.tv.core.calendar

import java.time.Duration

/**
 * How a finished refresh run ended. [attempted] counts every network fetch the run tried (the Trakt calendar and
 * each due title), [succeeded] those that answered. [finishedAtMs] is on the refresher's clock.
 */
data class CalendarRunResult(
    val finishedAtMs: Long,
    val attempted: Int,
    val succeeded: Int,
    val carriedFailure: Boolean = false
) {
    /**
     * Fix 1 over Arc: a run that tried at least one fetch and got no answer at all FAILED. Arc read it as
     * "all quiet" and showed an empty Calendar to someone whose network was down.
     */
    val failed: Boolean get() = calendarRunFailed(attempted, succeeded, carriedFailure)
}

/**
 * [carriedFailure]: the run asked nothing while a followed title still waits out the last run's failure, so the
 * failure stands (device finding: the next open's run, with nothing due, used to clear it to an all-quiet page).
 */
fun calendarRunFailed(attempted: Int, succeeded: Int, carriedFailure: Boolean = false): Boolean =
    (attempted > 0 && succeeded == 0) || (attempted == 0 && carriedFailure)

/**
 * Fix 3 over Arc: when opening the Calendar (or returning to Home) is worth a refresh. Arc refreshed on every
 * open; the per-title due times kept that cheap but each open still re-read every source and the Trakt calendar
 * gate. Retry and settings changes bypass this and always run.
 */
object CalendarRefreshGate {
    val OPEN_MIN_INTERVAL: Duration = Duration.ofMinutes(15)
    val HOME_MIN_INTERVAL: Duration = Duration.ofHours(6)

    /** Opening the Calendar: never run this process, the last run failed, or ≥ 15 min since it finished. */
    fun onCalendarOpened(lastRun: CalendarRunResult?, running: Boolean, nowMs: Long): Boolean = when {
        running -> false
        lastRun == null -> true
        lastRun.failed -> true
        else -> nowMs - lastRun.finishedAtMs >= OPEN_MIN_INTERVAL.toMillis()
    }

    /**
     * Back on Home: only when something on Home uses the Calendar (a bell title for reminders, or the Coming up
     * row), and then at most every 6 hours (or once, if it never ran). A failure is not re-asked from Home; the
     * Calendar's Retry and its next open do that.
     */
    fun onHomeResumed(lastRun: CalendarRunResult?, running: Boolean, homeUsesCalendar: Boolean, nowMs: Long): Boolean = when {
        !homeUsesCalendar || running -> false
        lastRun == null -> true
        else -> nowMs - lastRun.finishedAtMs >= HOME_MIN_INTERVAL.toMillis()
    }
}

/**
 * The Calendar screen's load, with a deadline (fix 2 over Arc, the TV mirror of the KMP apps' BoundedLoad with
 * `LoadSurface.CALENDAR`, cancelOnTimeout = false). The deadline is a STALL deadline: each finished title pushes it
 * out, because a big Calendar legitimately takes a while but reports progress. Past it the screen shows
 * Failed(timedOut) with Retry even if the refresh never returns; the refresh itself may keep going in the background.
 */
sealed interface CalendarLoadStatus {
    data object Idle : CalendarLoadStatus
    data class Loading(val startedAtMs: Long, val deadlineAtMs: Long) : CalendarLoadStatus
    data object Loaded : CalendarLoadStatus
    data object Empty : CalendarLoadStatus
    data class Failed(val timedOut: Boolean) : CalendarLoadStatus
}

object CalendarLoad {
    const val STALL_MS: Long = 30_000L

    fun begin(nowMs: Long, stallMs: Long = STALL_MS): CalendarLoadStatus.Loading =
        CalendarLoadStatus.Loading(nowMs, nowMs + stallMs)

    /** A finished title restarts the stall deadline; any other status is returned unchanged. */
    fun progressed(status: CalendarLoadStatus, nowMs: Long, stallMs: Long = STALL_MS): CalendarLoadStatus =
        if (status is CalendarLoadStatus.Loading) CalendarLoadStatus.Loading(status.startedAtMs, nowMs + stallMs) else status

    /** What the screen shows at [nowMs]: a Loading past its deadline is Failed(timedOut), whatever the work does. */
    fun effectiveAt(status: CalendarLoadStatus, nowMs: Long): CalendarLoadStatus =
        if (status is CalendarLoadStatus.Loading && nowMs >= status.deadlineAtMs) CalendarLoadStatus.Failed(timedOut = true) else status
}
