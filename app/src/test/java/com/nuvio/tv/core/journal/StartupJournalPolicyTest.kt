package com.nuvio.tv.core.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit (TV) — assertEquals(message, expected, actual). Twin of the KMP StartupJournalPolicyTest. */
class StartupJournalPolicyTest {
    private val op = "xtream_index_build"
    private val subj = "acct#movie"
    private val build = "1.6.6"

    private fun freshRun(nowMs: Long = 1_000L) = StartupJournalPolicy.startRun(Journal(), nowMs)

    @Test
    fun `begin records an in-flight attempt with a fresh generation`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        assertEquals(1L, gen)
        val e = j1.entries.single()
        assertNull("an attempt starts in flight", e.outcome)
        assertEquals(run, e.runId)
    }

    @Test
    fun `a completed outcome clears the backoff state`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.COMPLETED, 3_000L)
        assertEquals(0, j2.entries.single().interruptCount)
        assertFalse(StartupJournalPolicy.shouldDefer(j2, op, subj, 4_000L))
    }

    @Test
    fun `an expected failure defers within the window and clears after it`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        val failAt = 3_000L
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.EXPECTED_FAILURE, failAt)
        assertTrue("within the window", StartupJournalPolicy.shouldDefer(j2, op, subj, failAt + 1_000L))
        assertFalse(
            "after the window",
            StartupJournalPolicy.shouldDefer(j2, op, subj, failAt + StartupJournalPolicy.EXPECTED_FAILURE_BACKOFF_MS + 1),
        )
    }

    @Test
    fun `a cancelled outcome is not a failure and does not defer`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.CANCELLED, 3_000L)
        assertEquals(JournalOutcome.CANCELLED, j2.entries.single().outcome)
        assertFalse(StartupJournalPolicy.shouldDefer(j2, op, subj, 3_100L))
    }

    @Test
    fun `a stale completion does not clobber a newer attempt`() {
        val (j0, run) = freshRun()
        val (j1, gen1) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        val (j2, gen2) = StartupJournalPolicy.begin(j1, op, subj, run, build, 2_500L)
        assertEquals(2L, gen2)
        val j3 = StartupJournalPolicy.record(j2, op, subj, gen1, JournalOutcome.COMPLETED, 3_000L)
        val e = j3.entries.single()
        assertEquals(2L, e.generation)
        assertNull("newer attempt still in flight; stale completion dropped", e.outcome)
    }

    @Test
    fun `startRun marks a prior run in-flight attempt as interrupted unknown`() {
        val (j0, run1) = freshRun(1_000L)
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run1, build, 2_000L)
        val (j2, run2) = StartupJournalPolicy.startRun(j1, 10_000L)
        assertTrue(run2 > run1)
        val e = j2.entries.single()
        assertEquals("missing evidence is UNKNOWN, not a crash", JournalOutcome.INTERRUPTED_UNKNOWN, e.outcome)
        assertEquals(1, e.interruptCount)
        assertEquals(gen, e.generation)
    }

    @Test
    fun `startRun leaves a recorded outcome untouched`() {
        val (j0, run1) = freshRun(1_000L)
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run1, build, 2_000L)
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.EXPECTED_FAILURE, 3_000L)
        val (j3, _) = StartupJournalPolicy.startRun(j2, 10_000L)
        assertEquals("a handled outcome is not re-swept", JournalOutcome.EXPECTED_FAILURE, j3.entries.single().outcome)
    }

    @Test
    fun `startRun does not sweep the current run in-flight attempt`() {
        val (j0, run) = freshRun(1_000L)
        val (j1, _) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        assertNull(j1.entries.single().outcome)
    }

    @Test
    fun `consecutive interruptions escalate the bounded backoff`() {
        assertEquals(0L, StartupJournalPolicy.interruptBackoffMs(0))
        assertEquals(60_000L, StartupJournalPolicy.interruptBackoffMs(1))
        assertEquals(5 * 60_000L, StartupJournalPolicy.interruptBackoffMs(2))
        assertEquals(StartupJournalPolicy.interruptBackoffMs(4), StartupJournalPolicy.interruptBackoffMs(99))
    }

    @Test
    fun `an interrupted attempt defers within its bounded window`() {
        val (j0, run1) = freshRun(1_000L)
        val (j1, _) = StartupJournalPolicy.begin(j0, op, subj, run1, build, 2_000L)
        val (j2, _) = StartupJournalPolicy.startRun(j1, 10_000L)
        assertTrue("within 60s", StartupJournalPolicy.shouldDefer(j2, op, subj, 10_000L + 30_000L))
        assertFalse("after 60s", StartupJournalPolicy.shouldDefer(j2, op, subj, 10_000L + 61_000L))
        assertEquals(1, StartupJournalPolicy.interruptCount(j2, op, subj))
    }

    @Test
    fun `decode rejects null oversized corrupt and foreign-version blobs`() {
        assertEquals(Journal(), StartupJournalPolicy.decode(null))
        assertEquals(Journal(), StartupJournalPolicy.decode("x".repeat(StartupJournalPolicy.MAX_BLOB_CHARS + 1)))
        assertEquals(Journal(), StartupJournalPolicy.decode("{ not json"))
        val decoded = StartupJournalPolicy.decode("""{"version":999,"lastRunId":7,"entries":[]}""")
        assertEquals(StartupJournalPolicy.VERSION, decoded.version)
        assertEquals(7L, decoded.lastRunId)
    }

    @Test
    fun `encode then decode round-trips a journal`() {
        val (j0, run) = freshRun()
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, 2_000L)
        val j2 = StartupJournalPolicy.record(j1, op, subj, gen, JournalOutcome.COMPLETED, 3_000L)
        assertEquals(j2, StartupJournalPolicy.decode(StartupJournalPolicy.encode(j2)))
    }

    @Test
    fun `decode caps the entry list to MAX_ENTRIES`() {
        val many = (1..(StartupJournalPolicy.MAX_ENTRIES + 20)).map {
            JournalEntry("op$it", "s", 1, 1, build, it.toLong())
        }
        val decoded = StartupJournalPolicy.decode(StartupJournalPolicy.encode(Journal(entries = many)))
        assertEquals(StartupJournalPolicy.MAX_ENTRIES, decoded.entries.size)
    }

    @Test
    fun `safe mode triggers only at the threshold of consecutive interruptions`() {
        assertFalse(StartupJournalPolicy.shouldEnterSafeMode(0))
        assertFalse("a single unfinished startup is not enough", StartupJournalPolicy.shouldEnterSafeMode(1))
        assertTrue(StartupJournalPolicy.shouldEnterSafeMode(StartupJournalPolicy.SAFE_MODE_THRESHOLD))
        assertTrue(StartupJournalPolicy.shouldEnterSafeMode(5))
    }

    @Test
    fun `startup interactive completion resets the loop counter across runs`() {
        var j = Journal()
        var run = 0L
        repeat(2) {
            val (jr, r) = StartupJournalPolicy.startRun(j, 1_000L)
            val (j1, _) = StartupJournalPolicy.begin(jr, "startup_interactive", "", r, build, 2_000L)
            j = j1; run = r
        }
        // After the 2nd startRun sweep the prior interrupted attempt, count reaches the threshold.
        val (jSweep, _) = StartupJournalPolicy.startRun(j, 3_000L)
        assertTrue(StartupJournalPolicy.shouldEnterSafeMode(StartupJournalPolicy.interruptCount(jSweep, "startup_interactive", "")))
        val (j3, gen) = StartupJournalPolicy.begin(jSweep, "startup_interactive", "", run, build, 4_000L)
        val j4 = StartupJournalPolicy.record(j3, "startup_interactive", "", gen, JournalOutcome.COMPLETED, 5_000L)
        assertFalse(StartupJournalPolicy.shouldEnterSafeMode(StartupJournalPolicy.interruptCount(j4, "startup_interactive", "")))
    }

    // --- clock anomalies against the PERSISTED backoff timestamp (rollback / forward jump /
    //     reboot / restored-from-backup) — the cooldown must never lock an op out indefinitely ---

    /** Builds a journal with one recorded failure at [failAtMs] for [op]/[subj]. */
    private fun failedAt(outcome: JournalOutcome, failAtMs: Long): Journal {
        val (j0, run) = freshRun(failAtMs - 1)
        val (j1, gen) = StartupJournalPolicy.begin(j0, op, subj, run, build, failAtMs - 1)
        return StartupJournalPolicy.record(j1, op, subj, gen, outcome, failAtMs)
    }

    @Test
    fun `a wall-clock rollback after a failure does not defer forever`() {
        val failAt = 10_000_000L
        val j = failedAt(JournalOutcome.EXPECTED_FAILURE, failAt)
        // Clock rolled BACKWARD to before the stored failure: elapsed is negative. The old code did
        // `negative < backoff` -> true and deferred indefinitely; a future timestamp must fail open.
        assertFalse("a rolled-back clock must not lock the op out", StartupJournalPolicy.shouldDefer(j, op, subj, failAt - 5_000L))
    }

    @Test
    fun `a restored-from-backup journal with a future failure timestamp fails open`() {
        // A journal restored onto a device whose wall-clock is BEHIND the one that wrote it: every
        // stored timestamp is in this device's future. The op must be allowed, not deferred forever.
        val j = failedAt(JournalOutcome.INTERRUPTED_UNKNOWN, failAtMs = 5_000_000_000L)
        assertFalse(StartupJournalPolicy.shouldDefer(j, op, subj, nowMs = 1_000L))
    }

    @Test
    fun `a large forward clock jump expires the backoff instead of extending it`() {
        val failAt = 1_000L
        val j = failedAt(JournalOutcome.EXPECTED_FAILURE, failAt)
        assertTrue("still within the window at real time", StartupJournalPolicy.shouldDefer(j, op, subj, failAt + 1_000L))
        // NTP corrects the clock far forward: the bounded backoff is simply past, so the op proceeds.
        assertFalse(StartupJournalPolicy.shouldDefer(j, op, subj, failAt + 10L * 365 * 24 * 60 * 60 * 1000L))
    }

    @Test
    fun `across a reboot the swept interruption backoff is measured on surviving wall-clock`() {
        // Attempt opens, process dies (reboot) mid-op; the next start sweeps it to INTERRUPTED_UNKNOWN
        // stamped at the post-reboot wall-clock. Wall-clock survives reboot, so the bounded backoff
        // measures correctly: deferred right after, cleared once the window elapses.
        val (j0, run) = freshRun(1_000L)
        val (jInflight, _) = StartupJournalPolicy.begin(j0, op, subj, run, build, 1_000L)
        val rebootAt = 2_000L
        val (jSwept, _) = StartupJournalPolicy.startRun(jInflight, rebootAt)
        assertEquals("swept to interrupted", JournalOutcome.INTERRUPTED_UNKNOWN, jSwept.entries.single { it.op == op }.outcome)
        val backoff = StartupJournalPolicy.interruptBackoffMs(1)
        assertTrue(StartupJournalPolicy.shouldDefer(jSwept, op, subj, rebootAt + backoff - 1))
        assertFalse(StartupJournalPolicy.shouldDefer(jSwept, op, subj, rebootAt + backoff + 1))
    }
}
