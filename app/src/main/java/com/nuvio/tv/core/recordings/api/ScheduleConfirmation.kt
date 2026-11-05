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
    fun forResult(result: ScheduleResult, takeExactPromptTurn: () -> Boolean): ScheduleConfirmation = when (result) {
        is ScheduleResult.Scheduled -> {
            val clashes = result.clashes.map { it.title }.distinct()
            when (result.arm) {
                ArmKind.EXACT -> ScheduleConfirmation(ConfirmationKind.SET, clashes)
                ArmKind.INEXACT -> ScheduleConfirmation(ConfirmationKind.MAY_START_LATE, clashes, offerExactAlarmSettings = takeExactPromptTurn())
                ArmKind.NONE -> ScheduleConfirmation(ConfirmationKind.SAVES_ON_NEXT_OPEN, clashes)
            }
        }
        is ScheduleResult.Duplicate -> ScheduleConfirmation(ConfirmationKind.ALREADY_SCHEDULED)
        ScheduleResult.CannotRecord -> ScheduleConfirmation(ConfirmationKind.CANNOT_RECORD)
    }

    fun forSeries(
        created: List<RecordingSchedule>,
        capabilities: RecordingCapabilities,
        exactAllowed: Boolean,
        takeExactPromptTurn: () -> Boolean,
    ): ScheduleConfirmation = when {
        created.isEmpty() -> ScheduleConfirmation(ConfirmationKind.SERIES_NOTHING_YET, count = 0)
        !capabilities.canWakeApp -> ScheduleConfirmation(ConfirmationKind.SAVES_ON_NEXT_OPEN, count = created.size)
        !exactAllowed -> ScheduleConfirmation(
            ConfirmationKind.MAY_START_LATE, offerExactAlarmSettings = takeExactPromptTurn(), count = created.size,
        )
        else -> ScheduleConfirmation(ConfirmationKind.SET, count = created.size)
    }
}
