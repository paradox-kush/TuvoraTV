package com.nuvio.tv.core.recordings.api

enum class ConfirmationKind {
    /** Exact wake-up: "Recording set". */
    SET,

    /** Android without exact alarms: it may start a little late. */
    MAY_START_LATE,

    /** iOS: nothing can wake the app, so it saves the next time Tuvora opens after the show ends. */
    SAVES_ON_NEXT_OPEN,
    ALREADY_SCHEDULED,
    CANNOT_RECORD,

    /** A series rule was saved but no airing needed scheduling yet. */
    SERIES_NOTHING_YET,
}

/**
 * What to tell the viewer after they asked to record. [clashTitles]: overlapping live captures (warn, never block).
 * [offerExactAlarmSettings]: show the "allow exact alarms" action - true at most once per install.
 * [count]: how many airings a series request scheduled (1 for a single programme).
 */
data class ScheduleConfirmation(
    val kind: ConfirmationKind,
    val clashTitles: List<String> = emptyList(),
    val offerExactAlarmSettings: Boolean = false,
    val count: Int = 1,
)

/** Picks the truthful confirmation for a scheduling result. Pure. */
object ScheduleConfirmationPolicy {
    /** [takeExactPromptTurn] is called only when an INEXACT alarm makes the prompt relevant, so the "once" is spent on a real need. */
    fun forResult(result: ScheduleResult, takeExactPromptTurn: () -> Boolean): ScheduleConfirmation = TODO("wave 3")

    fun forSeries(
        created: List<RecordingSchedule>,
        capabilities: RecordingCapabilities,
        exactAllowed: Boolean,
        takeExactPromptTurn: () -> Boolean,
    ): ScheduleConfirmation = TODO("wave 3")
}
