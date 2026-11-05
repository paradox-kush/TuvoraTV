package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.LowSpacePolicy
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.internal.CaptureSpaceDecision
import com.nuvio.tv.core.recordings.internal.RecordingSpacePolicy as P
import com.nuvio.tv.core.recordings.internal.SpaceDecision
import org.junit.Test
import org.junit.Assert.assertEquals

class RecordingSpacePolicyTest {
    private val GB = 1_000_000_000L
    private val floor = P.MIN_FREE_BYTES

    @Test
    fun `the floor is 500 MB`() {
        assertEquals(500L * 1024 * 1024, floor)
    }

    @Test
    fun `an hour at the default bitrate is about 3_6 GB`() {
        assertEquals(3_600_000_000L, P.estimateBytes(HOUR))
    }

    @Test
    fun `a known bitrate replaces the default`() {
        assertEquals(1_800_000_000L, P.estimateBytes(HOUR, 4_000_000L))
        assertEquals(3_600_000_000L, P.estimateBytes(HOUR, null))
        assertEquals("a zero bitrate is unknown", 3_600_000_000L, P.estimateBytes(HOUR, 0L))
    }

    @Test
    fun `a negative or zero duration needs nothing`() {
        assertEquals(0L, P.estimateBytes(0))
        assertEquals(0L, P.estimateBytes(-5))
    }

    @Test
    fun `enough room proceeds`() {
        assertEquals(SpaceDecision.Proceed, P.check(10 * GB, 3 * GB, LowSpacePolicy.STOP, emptyList()))
    }

    @Test
    fun `the floor is never consumed`() {
        // Exactly need + floor fits; one byte less does not.
        assertEquals(SpaceDecision.Proceed, P.check(3 * GB + floor, 3 * GB, LowSpacePolicy.STOP, emptyList()))
        assertEquals(SpaceDecision.Refuse(RecordingProblem.NO_SPACE), P.check(3 * GB + floor - 1, 3 * GB, LowSpacePolicy.STOP, emptyList()))
    }

    @Test
    fun `stop policy refuses and never deletes`() {
        val old = recording("old", start = NOW - 10 * DAY)
        assertEquals(SpaceDecision.Refuse(RecordingProblem.NO_SPACE), P.check(1 * GB, 3 * GB, LowSpacePolicy.STOP, listOf(old)))
    }

    @Test
    fun `delete oldest removes the oldest finished recordings first until it fits`() {
        val a = recording("a", start = NOW - 30 * DAY, size = 2 * GB)
        val b = recording("b", start = NOW - 20 * DAY, size = 2 * GB)
        val c = recording("c", start = NOW - 10 * DAY, size = 2 * GB)
        // free 1 GB, floor 0.52 GB, need 3.6 GB: must free 3.2 GB+, so two recordings.
        val d = P.check(1 * GB, 3_600_000_000L, LowSpacePolicy.DELETE_OLDEST, listOf(c, a, b))
        assertEquals(SpaceDecision.DeleteThenProceed(listOf("a", "b")), d)
    }

    @Test
    fun `delete oldest takes only as many as it needs`() {
        val a = recording("a", start = NOW - 30 * DAY, size = 5 * GB)
        val b = recording("b", start = NOW - 20 * DAY, size = 5 * GB)
        val d = P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(a, b))
        assertEquals(SpaceDecision.DeleteThenProceed(listOf("a")), d)
    }

    @Test
    fun `a recording in progress is never deleted`() {
        val live = recording("live", status = RecordingStatus.RECORDING, start = NOW - 30 * DAY, size = 9 * GB)
        val d = P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(live))
        assertEquals(SpaceDecision.Refuse(RecordingProblem.NO_SPACE), d)
    }

    @Test
    fun `scheduled failed and cancelled rows are not deleted for space`() {
        val rows = listOf(RecordingStatus.SCHEDULED, RecordingStatus.FAILED, RecordingStatus.CANCELLED)
            .mapIndexed { i, s -> recording("r$i", status = s, start = NOW - 30 * DAY, size = 9 * GB) }
        assertEquals(SpaceDecision.Refuse(RecordingProblem.NO_SPACE), P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, rows))
    }

    @Test
    fun `the newest recording of a series is never deleted`() {
        val s1 = recording("s1", start = NOW - 30 * DAY, size = 5 * GB, seriesRuleId = "ser")
        val s2 = recording("s2", start = NOW - 20 * DAY, size = 5 * GB, seriesRuleId = "ser")
        val d = P.check(1 * GB, 8 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(s1, s2))
        // s2 is protected, s1 alone frees 5 GB: not enough, so it refuses rather than delete in vain.
        assertEquals(SpaceDecision.Refuse(RecordingProblem.NO_SPACE), d)
        assertEquals(SpaceDecision.DeleteThenProceed(listOf("s1")), P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(s1, s2)))
    }

    @Test
    fun `a series whose newest is still recording keeps it and may lose an older finished one`() {
        val old = recording("old", start = NOW - 30 * DAY, size = 5 * GB, seriesRuleId = "ser")
        val running = recording("run", status = RecordingStatus.RECORDING, start = NOW - DAY, size = 1 * GB, seriesRuleId = "ser")
        // The newest (still recording) is the protected one, so the older finished episode is fair game.
        assertEquals(SpaceDecision.DeleteThenProceed(listOf("old")), P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(old, running)))
    }

    @Test
    fun `refuses without deleting when everything deletable would still not be enough`() {
        val a = recording("a", start = NOW - 30 * DAY, size = 1 * GB)
        assertEquals(SpaceDecision.Refuse(RecordingProblem.NO_SPACE), P.check(0, 5 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(a)))
    }

    @Test
    fun `an empty file is not worth deleting`() {
        val empty = recording("e", start = NOW - 30 * DAY, size = 0)
        assertEquals(SpaceDecision.Refuse(RecordingProblem.NO_SPACE), P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(empty)))
    }

    @Test
    fun `equal start times delete in id order so the answer is stable`() {
        val b = recording("b", start = NOW - 30 * DAY, size = 5 * GB)
        val a = recording("a", start = NOW - 30 * DAY, size = 5 * GB)
        assertEquals(SpaceDecision.DeleteThenProceed(listOf("a")), P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(b, a)))
    }

    // --- during capture ---------------------------------------------------------------------

    @Test
    fun `during capture there is room so it continues`() {
        assertEquals(CaptureSpaceDecision.Continue, P.duringCapture(10 * GB, 500_000_000L, LowSpacePolicy.STOP, emptyList()))
    }

    @Test
    fun `during capture the floor stops a stop-policy capture`() {
        assertEquals(
            CaptureSpaceDecision.Stop(RecordingProblem.NO_SPACE),
            P.duringCapture(floor + 100, 500_000_000L, LowSpacePolicy.STOP, emptyList()))
    }

    @Test
    fun `during capture delete oldest frees room and continues`() {
        val a = recording("a", start = NOW - 30 * DAY, size = 5 * GB)
        val live = recording("live", status = RecordingStatus.RECORDING, start = NOW, size = 1 * GB)
        val d = P.duringCapture(floor + 100, 500_000_000L, LowSpacePolicy.DELETE_OLDEST, listOf(a, live))
        assertEquals(CaptureSpaceDecision.DeleteThenContinue(listOf("a")), d)
    }

    @Test
    fun `during capture nothing deletable means stop`() {
        val live = recording("live", status = RecordingStatus.RECORDING, start = NOW, size = 1 * GB)
        val d = P.duringCapture(floor, 500_000_000L, LowSpacePolicy.DELETE_OLDEST, listOf(live))
        assertIs<CaptureSpaceDecision.Stop>(d)
    }
}
