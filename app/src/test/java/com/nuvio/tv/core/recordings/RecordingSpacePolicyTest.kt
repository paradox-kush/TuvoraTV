package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.LowSpacePolicy
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.internal.CaptureSpaceDecision
import com.nuvio.tv.core.recordings.internal.RecordingSpacePolicy as P
import com.nuvio.tv.core.recordings.internal.SpaceDecision
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class RecordingSpacePolicyTest {
    private val GB = 1_000_000_000L
    private val floor = P.MIN_FREE_BYTES

    private fun refusal(d: SpaceDecision): SpaceDecision.Refuse {
        assertTrue("expected a Refuse but was $d", d is SpaceDecision.Refuse)
        return d as SpaceDecision.Refuse
    }

    private fun assertNoSpace(d: SpaceDecision) {
        assertEquals(RecordingProblem.NO_SPACE, refusal(d).problem)
    }

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
        assertNoSpace(P.check(3 * GB + floor - 1, 3 * GB, LowSpacePolicy.STOP, emptyList()))
    }

    @Test
    fun `stop policy refuses and never deletes`() {
        val old = recording("old", start = NOW - 10 * DAY)
        assertNoSpace(P.check(1 * GB, 3 * GB, LowSpacePolicy.STOP, listOf(old)))
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
        assertNoSpace(d)
    }

    @Test
    fun `scheduled failed and cancelled rows are not deleted for space`() {
        val rows = listOf(RecordingStatus.SCHEDULED, RecordingStatus.FAILED, RecordingStatus.CANCELLED)
            .mapIndexed { i, s -> recording("r$i", status = s, start = NOW - 30 * DAY, size = 9 * GB) }
        assertNoSpace(P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, rows))
    }

    @Test
    fun `the newest recording of a series is never deleted`() {
        val s1 = recording("s1", start = NOW - 30 * DAY, size = 5 * GB, seriesRuleId = "ser")
        val s2 = recording("s2", start = NOW - 20 * DAY, size = 5 * GB, seriesRuleId = "ser")
        val d = P.check(1 * GB, 8 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(s1, s2))
        // s2 is protected, s1 alone frees 5 GB: not enough, so it refuses rather than delete in vain.
        assertNoSpace(d)
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
        assertNoSpace(P.check(0, 5 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(a)))
    }

    @Test
    fun `an empty file is not worth deleting`() {
        val empty = recording("e", start = NOW - 30 * DAY, size = 0)
        assertNoSpace(P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(empty)))
    }

    @Test
    fun `equal start times delete in id order so the answer is stable`() {
        val b = recording("b", start = NOW - 30 * DAY, size = 5 * GB)
        val a = recording("a", start = NOW - 30 * DAY, size = 5 * GB)
        assertEquals(SpaceDecision.DeleteThenProceed(listOf("a")), P.check(1 * GB, 3 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(b, a)))
    }

    // --- space policy change: an ESTIMATE never refuses -------------------------------------------

    @Test
    fun `pre-start proceeds when only the estimate exceeds free space but the floor is fine`() {
        // 2 GB free (the Onn): a 30 min catch-up estimates ~2.2 GB at 8 Mbit/s with padding, yet it starts.
        assertEquals(SpaceDecision.Proceed, P.preStart(2 * GB, LowSpacePolicy.STOP, emptyList()))
        assertEquals(SpaceDecision.Proceed, P.preStart(floor + 1, LowSpacePolicy.STOP, emptyList()))
        assertEquals("the estimate (~2.2 GB) plus the floor exceeds 2 GB: tell the viewer", true, P.spaceTight(2 * GB, 37 * MIN))
    }

    @Test
    fun `pre-start refuses at or below the floor and nowhere else`() {
        assertNoSpace(P.preStart(floor, LowSpacePolicy.STOP, emptyList()))
        assertNoSpace(P.preStart(floor - 1, LowSpacePolicy.STOP, emptyList()))
        assertNoSpace(P.preStart(0, LowSpacePolicy.STOP, emptyList()))
    }

    @Test
    fun `pre-start under stop never deletes even when it refuses`() {
        val old = recording("old", start = NOW - 10 * DAY, size = 5 * GB)
        assertNoSpace(P.preStart(floor - 1, LowSpacePolicy.STOP, listOf(old)))
    }

    @Test
    fun `pre-start under delete oldest deletes only to get above the floor plus a small head start`() {
        val a = recording("a", start = NOW - 30 * DAY, size = 2 * GB)
        val b = recording("b", start = NOW - 20 * DAY, size = 2 * GB)
        // 100 MB below the floor: the oldest alone is plenty; the estimate is not consulted.
        val d = P.preStart(floor - 100_000_000L, LowSpacePolicy.DELETE_OLDEST, listOf(b, a))
        assertEquals(SpaceDecision.DeleteThenProceed(listOf("a")), d)
    }

    @Test
    fun `pre-start under delete oldest does not delete on an estimate once the floor is fine`() {
        val a = recording("a", start = NOW - 30 * DAY, size = 5 * GB)
        // 1 GB free: far less than an hour's estimate, but above the floor: nothing is deleted.
        assertEquals(SpaceDecision.Proceed, P.preStart(1 * GB, LowSpacePolicy.DELETE_OLDEST, listOf(a)))
    }

    @Test
    fun `pre-start under delete oldest spares captures and the newest of a series and refuses if that is not enough`() {
        val live = recording("live", status = RecordingStatus.RECORDING, start = NOW - 30 * DAY, size = 9 * GB)
        val newest = recording("n", start = NOW - 20 * DAY, size = 9 * GB, seriesRuleId = "ser")
        assertNoSpace(P.preStart(floor - 1, LowSpacePolicy.DELETE_OLDEST, listOf(live, newest)))
        val older = recording("o", start = NOW - 25 * DAY, size = 9 * GB, seriesRuleId = "ser")
        assertEquals(SpaceDecision.DeleteThenProceed(listOf("o")), P.preStart(floor - 1, LowSpacePolicy.DELETE_OLDEST, listOf(live, newest, older)))
    }

    @Test
    fun `pre-start under delete oldest refuses without deleting when everything deletable still leaves it at the floor`() {
        val small = recording("s", start = NOW - 30 * DAY, size = 10_000_000L)
        assertNoSpace(P.preStart(floor - 100_000_000L, LowSpacePolicy.DELETE_OLDEST, listOf(small)))
    }

    @Test
    fun `a known size larger than free minus the floor is refused with the numbers`() {
        val d = P.knownSize(freeBytes = 1 * GB, totalBytes = 1_100_000_000L, onDiskBytes = 0, policy = LowSpacePolicy.STOP, deletable = emptyList())
        val refuse = refusal(d)
        assertEquals(RecordingProblem.NO_SPACE, refuse.problem)
        assertEquals(1_100_000_000L, refuse.needBytes)
        assertEquals(1 * GB, refuse.freeBytes)
    }

    @Test
    fun `a known size that fits proceeds`() {
        assertEquals(SpaceDecision.Proceed, P.knownSize(2 * GB, 30_000_000L, 0, LowSpacePolicy.STOP, emptyList()))
        assertEquals("exactly fits", SpaceDecision.Proceed, P.knownSize(1_000_000_000L + floor, 1_000_000_000L, 0, LowSpacePolicy.STOP, emptyList()))
    }

    @Test
    fun `a resumed save compares only the remainder`() {
        // 3 GB file, 2.5 GB already on disk, 1 GB usable: 0.5 GB remain, it fits.
        assertEquals(SpaceDecision.Proceed, P.knownSize(1 * GB + floor, 3 * GB, 2_500_000_000L, LowSpacePolicy.STOP, emptyList()))
        val d = refusal(P.knownSize(100_000_000L + floor, 3 * GB, 2_500_000_000L, LowSpacePolicy.STOP, emptyList()))
        assertEquals("the remainder, not the whole file", 500_000_000L, d.needBytes)
    }

    @Test
    fun `a known size under delete oldest may delete the oldest finished recording and carry on`() {
        val a = recording("a", start = NOW - 30 * DAY, size = 5 * GB)
        val d = P.knownSize(1 * GB, 3 * GB, 0, LowSpacePolicy.DELETE_OLDEST, listOf(a))
        assertEquals(SpaceDecision.DeleteThenProceed(listOf("a")), d)
        assertNoSpace(P.knownSize(1 * GB, 30 * GB, 0, LowSpacePolicy.DELETE_OLDEST, listOf(a)))
    }

    @Test
    fun `space is not tight when there is plenty or nothing is known`() {
        assertEquals(false, P.spaceTight(100 * GB, 2 * HOUR))
        assertEquals("exactly enough is not tight", false, P.spaceTight(P.estimateBytes(HOUR) + floor, HOUR))
        assertEquals(true, P.spaceTight(P.estimateBytes(HOUR) + floor - 1, HOUR))
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
