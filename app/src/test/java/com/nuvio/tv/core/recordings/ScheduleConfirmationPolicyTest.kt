package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.ArmKind
import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.ConfirmationKind
import com.nuvio.tv.core.recordings.api.RecordingCapabilities
import com.nuvio.tv.core.recordings.api.ScheduleConfirmationPolicy
import com.nuvio.tv.core.recordings.api.ScheduleResult
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ScheduleConfirmationPolicyTest {
    private val s = schedule(id = "s", mode = CaptureMode.CATCH_UP, ch = channel(catchUp = xtream(3)))

    private fun scheduled(arm: ArmKind, clashes: List<com.nuvio.tv.core.recordings.api.RecordingSchedule> = emptyList()) =
        ScheduleResult.Scheduled(s, arm, clashes)

    @Test
    fun `an exact alarm says the recording is set and never spends the prompt turn`() {
        var asked = 0
        val c = ScheduleConfirmationPolicy.forResult(scheduled(ArmKind.EXACT)) { asked++; true }
        assertEquals(ConfirmationKind.SET, c.kind)
        assertFalse(c.offerExactAlarmSettings)
        assertEquals(0, asked)
    }

    @Test
    fun `an inexact alarm says it may start late and offers the settings on the first turn only`() {
        val first = ScheduleConfirmationPolicy.forResult(scheduled(ArmKind.INEXACT)) { true }
        assertEquals(ConfirmationKind.MAY_START_LATE, first.kind)
        assertTrue(first.offerExactAlarmSettings)
        val later = ScheduleConfirmationPolicy.forResult(scheduled(ArmKind.INEXACT)) { false }
        assertEquals(ConfirmationKind.MAY_START_LATE, later.kind)
        assertFalse(later.offerExactAlarmSettings)
    }

    @Test
    fun `no alarm means it saves on the next open and never offers exact alarms`() {
        var asked = 0
        val c = ScheduleConfirmationPolicy.forResult(scheduled(ArmKind.NONE)) { asked++; true }
        assertEquals(ConfirmationKind.SAVES_ON_NEXT_OPEN, c.kind)
        assertFalse(c.offerExactAlarmSettings)
        assertEquals(0, asked)
    }

    @Test
    fun `clashes are named as a warning and do not change the kind`() {
        val other = schedule(id = "o", title = "Other show")
        val c = ScheduleConfirmationPolicy.forResult(scheduled(ArmKind.EXACT, listOf(other))) { false }
        assertEquals(ConfirmationKind.SET, c.kind)
        assertEquals(listOf("Other show"), c.clashTitles)
    }

    @Test
    fun `duplicate and cannot-record map to their own kinds`() {
        assertEquals(ConfirmationKind.ALREADY_SCHEDULED, ScheduleConfirmationPolicy.forResult(ScheduleResult.Duplicate(s)) { true }.kind)
        assertEquals(ConfirmationKind.CANNOT_RECORD, ScheduleConfirmationPolicy.forResult(ScheduleResult.CannotRecord) { true }.kind)
    }

    @Test
    fun `a series with nothing to schedule yet says so`() {
        val c = ScheduleConfirmationPolicy.forSeries(emptyList(), RecordingCapabilities.ANDROID, exactAllowed = true) { true }
        assertEquals(ConfirmationKind.SERIES_NOTHING_YET, c.kind)
        assertEquals(0, c.count)
    }

    @Test
    fun `a series reports how many airings were set and the platform's honesty`() {
        val two = listOf(s, s.copy(id = "t"))
        val android = ScheduleConfirmationPolicy.forSeries(two, RecordingCapabilities.ANDROID, exactAllowed = true) { true }
        assertEquals(ConfirmationKind.SET, android.kind)
        assertEquals(2, android.count)
        assertFalse(android.offerExactAlarmSettings)

        val inexact = ScheduleConfirmationPolicy.forSeries(two, RecordingCapabilities.ANDROID, exactAllowed = false) { true }
        assertEquals(ConfirmationKind.MAY_START_LATE, inexact.kind)
        assertTrue(inexact.offerExactAlarmSettings)

        val iphone = ScheduleConfirmationPolicy.forSeries(two, RecordingCapabilities.IPHONE, exactAllowed = true) { true }
        assertEquals(ConfirmationKind.SAVES_ON_NEXT_OPEN, iphone.kind)
        assertFalse(iphone.offerExactAlarmSettings)
    }

    @Test
    fun `space tight is carried to the confirmation on every arm`() {
        for (arm in ArmKind.values()) {
            val c = ScheduleConfirmationPolicy.forResult(ScheduleResult.Scheduled(s, arm, emptyList(), spaceTight = true)) { false }
            assertTrue("arm $arm", c.spaceTight)
        }
        assertFalse(ScheduleConfirmationPolicy.forResult(scheduled(ArmKind.EXACT)) { false }.spaceTight)
    }
}
