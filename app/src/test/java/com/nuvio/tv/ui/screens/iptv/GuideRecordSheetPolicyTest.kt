package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.ConfirmationKind
import com.nuvio.tv.core.recordings.api.RecordOffer
import com.nuvio.tv.core.recordings.api.RecordUnavailable
import com.nuvio.tv.core.recordings.api.ScheduleConfirmation
import com.nuvio.tv.core.recordings.api.SeriesOffer
import com.nuvio.tv.ui.screens.iptv.GuideRecordSheetPolicy.Note
import com.nuvio.tv.ui.screens.iptv.GuideRecordSheetPolicy.NoticeAction
import com.nuvio.tv.ui.screens.iptv.GuideRecordSheetPolicy.Primary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideRecordSheetPolicyTest {

    private val noSeries = SeriesOffer.Unavailable

    @Test
    fun `a recordable programme offers Record with its capture mode`() {
        val plan = GuideRecordSheetPolicy.plan(RecordOffer.Record(CaptureMode.CATCH_UP), noSeries)
        assertEquals("primary", Primary.RECORD, plan.primary)
        assertEquals("mode", CaptureMode.CATCH_UP, plan.mode)
        assertTrue("no reason line when it can be recorded", plan.notes.isEmpty())
    }

    @Test
    fun `a waiting schedule offers Cancel recording and carries the schedule id`() {
        val plan = GuideRecordSheetPolicy.plan(RecordOffer.Scheduled("s1", capturing = false), noSeries)
        assertEquals(Primary.CANCEL, plan.primary)
        assertEquals("s1", plan.scheduleId)
    }

    @Test
    fun `a running capture offers Stop recording not Cancel`() {
        assertEquals(Primary.STOP, GuideRecordSheetPolicy.plan(RecordOffer.Scheduled("s1", capturing = true), noSeries).primary)
    }

    @Test
    fun `an already saved airing hides Record and says so`() {
        val plan = GuideRecordSheetPolicy.plan(RecordOffer.AlreadySaved, noSeries)
        assertNull("no record row", plan.primary)
        assertEquals(listOf(Note.ALREADY_SAVED), plan.notes)
    }

    @Test
    fun `each unavailable reason becomes its own one line note and no Record row`() {
        val expected = mapOf(
            RecordUnavailable.BAD_TIMES to Note.BAD_TIMES,
            RecordUnavailable.NEEDS_CATCH_UP to Note.NEEDS_CATCH_UP,
            RecordUnavailable.PROGRAMME_OVER to Note.PROGRAMME_OVER,
        )
        RecordUnavailable.entries.forEach { reason ->
            val plan = GuideRecordSheetPolicy.plan(RecordOffer.Unavailable(reason), noSeries)
            assertNull("$reason has no record row", plan.primary)
            assertEquals("$reason", listOf(expected.getValue(reason)), plan.notes)
        }
    }

    @Test
    fun `Record series shows whenever a rule can be made even if this airing cannot be saved`() {
        val plan = GuideRecordSheetPolicy.plan(RecordOffer.Unavailable(RecordUnavailable.PROGRAMME_OVER), SeriesOffer.Record)
        assertTrue(plan.recordSeries)
        assertNull(plan.primary)
    }

    @Test
    fun `Record series hides when the channel cannot be saved or the rule exists`() {
        assertFalse(GuideRecordSheetPolicy.plan(RecordOffer.Record(CaptureMode.CATCH_UP), SeriesOffer.Unavailable).recordSeries)
        val active = GuideRecordSheetPolicy.plan(RecordOffer.Record(CaptureMode.CATCH_UP), SeriesOffer.Active("series:x"))
        assertFalse(active.recordSeries)
        assertEquals(listOf(Note.SERIES_ACTIVE), active.notes)
    }

    @Test
    fun `Start over and Watch live follow the programme action`() {
        assertTrue(GuideRecordSheetPolicy.showsStartOver(ProgrammeAction.START_OVER))
        assertFalse(GuideRecordSheetPolicy.showsStartOver(ProgrammeAction.PLAY_LIVE))
        assertFalse(GuideRecordSheetPolicy.showsStartOver(ProgrammeAction.NONE))
        assertTrue(GuideRecordSheetPolicy.showsWatchLive(ProgrammeAction.START_OVER))
        assertTrue(GuideRecordSheetPolicy.showsWatchLive(ProgrammeAction.PLAY_LIVE))
        assertFalse("a programme not on air has no live", GuideRecordSheetPolicy.showsWatchLive(ProgrammeAction.NONE))
        assertFalse(GuideRecordSheetPolicy.showsWatchLive(ProgrammeAction.REPLAY))
    }

    @Test
    fun `the first inexact schedule offers the exact alarm settings on its button`() {
        val c = ScheduleConfirmation(ConfirmationKind.MAY_START_LATE, offerExactAlarmSettings = true)
        assertEquals(NoticeAction.OPEN_EXACT_SETTINGS, GuideRecordSheetPolicy.noticeAction(c, isSeries = false))
        assertEquals(NoticeAction.OPEN_EXACT_SETTINGS, GuideRecordSheetPolicy.noticeAction(c, isSeries = true))
    }

    @Test
    fun `a single schedule can be undone from its confirmation`() {
        listOf(ConfirmationKind.SET, ConfirmationKind.MAY_START_LATE, ConfirmationKind.SAVES_ON_NEXT_OPEN).forEach {
            assertEquals("$it", NoticeAction.UNDO, GuideRecordSheetPolicy.noticeAction(ScheduleConfirmation(it), isSeries = false))
        }
    }

    @Test
    fun `a series confirmation and a refusal only dismiss`() {
        assertEquals(NoticeAction.DISMISS, GuideRecordSheetPolicy.noticeAction(ScheduleConfirmation(ConfirmationKind.SET, count = 4), isSeries = true))
        assertEquals(NoticeAction.DISMISS, GuideRecordSheetPolicy.noticeAction(ScheduleConfirmation(ConfirmationKind.ALREADY_SCHEDULED), isSeries = false))
        assertEquals(NoticeAction.DISMISS, GuideRecordSheetPolicy.noticeAction(ScheduleConfirmation(ConfirmationKind.CANNOT_RECORD), isSeries = false))
    }
}
