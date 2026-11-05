package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.ConfirmationKind
import com.nuvio.tv.core.recordings.api.RecordOffer
import com.nuvio.tv.core.recordings.api.RecordUnavailable
import com.nuvio.tv.core.recordings.api.ScheduleConfirmation
import com.nuvio.tv.core.recordings.api.SeriesOffer

/**
 * What the guide's programme sheet shows and says about recording. Pure: the sheet draws the answer.
 * The facts come from `ProgrammeRecordPolicy`; this maps them to rows, one-line reasons and the
 * confirmation toast's button.
 */
internal object GuideRecordSheetPolicy {

    enum class Primary { RECORD, CANCEL, STOP }

    /** A one-line reason shown instead of a row that cannot work. */
    enum class Note { ALREADY_SAVED, BAD_TIMES, NEEDS_CATCH_UP, PROGRAMME_OVER }

    data class Plan(
        val primary: Primary?,
        val scheduleId: String?,
        val mode: CaptureMode?,
        val recordSeries: Boolean,
        /** The rule behind "Stop series recording", when one exists for this title on this channel. */
        val stopSeriesRuleId: String?,
        val notes: List<Note>,
    )

    fun plan(offer: RecordOffer, series: SeriesOffer): Plan {
        val notes = ArrayList<Note>(2)
        var primary: Primary? = null
        var scheduleId: String? = null
        var mode: CaptureMode? = null
        when (offer) {
            is RecordOffer.Record -> { primary = Primary.RECORD; mode = offer.mode }
            is RecordOffer.Scheduled -> {
                primary = if (offer.capturing) Primary.STOP else Primary.CANCEL
                scheduleId = offer.scheduleId
            }
            RecordOffer.AlreadySaved -> notes += Note.ALREADY_SAVED
            is RecordOffer.Unavailable -> notes += when (offer.reason) {
                RecordUnavailable.BAD_TIMES -> Note.BAD_TIMES
                RecordUnavailable.NEEDS_CATCH_UP -> Note.NEEDS_CATCH_UP
                RecordUnavailable.PROGRAMME_OVER -> Note.PROGRAMME_OVER
            }
        }
        return Plan(
            primary, scheduleId, mode,
            recordSeries = series is SeriesOffer.Record,
            stopSeriesRuleId = (series as? SeriesOffer.Active)?.ruleId,
            notes = notes,
        )
    }

    /** "Start over" is only for the airing programme on a channel that kept its beginning. */
    fun showsStartOver(action: ProgrammeAction): Boolean = action == ProgrammeAction.START_OVER

    /** "Watch live" is for an airing programme. */
    fun showsWatchLive(action: ProgrammeAction): Boolean =
        action == ProgrammeAction.START_OVER || action == ProgrammeAction.PLAY_LIVE

    enum class NoticeAction { UNDO, OPEN_EXACT_SETTINGS, DISMISS }

    fun noticeAction(confirmation: ScheduleConfirmation, isSeries: Boolean): NoticeAction = when {
        confirmation.offerExactAlarmSettings -> NoticeAction.OPEN_EXACT_SETTINGS
        isSeries -> NoticeAction.DISMISS
        confirmation.kind == ConfirmationKind.SET ||
            confirmation.kind == ConfirmationKind.MAY_START_LATE ||
            confirmation.kind == ConfirmationKind.SAVES_ON_NEXT_OPEN -> NoticeAction.UNDO
        else -> NoticeAction.DISMISS
    }

    /** A programme that has not started: nothing to play, but it can be recorded. */
    fun isUpcoming(programme: XtreamProgram, nowMs: Long): Boolean =
        programme.startMs > nowMs && programme.endMs > programme.startMs
}
