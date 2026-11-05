package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.RecordOffer
import com.nuvio.tv.core.recordings.api.ScheduleConfirmation
import com.nuvio.tv.core.recordings.api.SeriesOffer

/** Stub (red): bodies are wrong on purpose until the tests are written against them. */
internal object GuideRecordSheetPolicy {
    enum class Primary { RECORD, CANCEL, STOP }
    enum class Note { ALREADY_SAVED, SERIES_ACTIVE, BAD_TIMES, NEEDS_CATCH_UP, PROGRAMME_OVER }
    data class Plan(
        val primary: Primary?,
        val scheduleId: String?,
        val mode: CaptureMode?,
        val recordSeries: Boolean,
        val notes: List<Note>,
    )

    fun plan(offer: RecordOffer, series: SeriesOffer): Plan = Plan(null, null, null, false, emptyList())
    fun showsStartOver(action: ProgrammeAction): Boolean = false
    fun showsWatchLive(action: ProgrammeAction): Boolean = false

    enum class NoticeAction { UNDO, OPEN_EXACT_SETTINGS, DISMISS }

    fun noticeAction(confirmation: ScheduleConfirmation, isSeries: Boolean): NoticeAction = NoticeAction.DISMISS
    fun isUpcoming(programme: XtreamProgram, nowMs: Long): Boolean = false
}
