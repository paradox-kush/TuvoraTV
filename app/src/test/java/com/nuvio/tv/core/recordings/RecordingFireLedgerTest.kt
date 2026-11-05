package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.internal.FireEntry
import com.nuvio.tv.core.recordings.internal.FireLedgerState
import com.nuvio.tv.core.recordings.internal.FireOutcome
import com.nuvio.tv.core.recordings.internal.FireState
import com.nuvio.tv.core.recordings.internal.RecordingFireLedger
import com.nuvio.tv.core.recordings.internal.RecordingFireLedger.Claim
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class RecordingFireLedgerTest {

    @Test
    fun `the first claim wins and every other trigger sees it running`() {
        val ledger = RecordingFireLedger()
        assertIs<Claim.Claimed>(ledger.claim("s1", 100))
        // A duplicate alarm, a boot re-arm, an overdue sweep and a manual start now.
        repeat(4) { assertEquals(Claim.AlreadyRunning, ledger.claim("s1", 200L + it)) }
    }

    @Test
    fun `different schedules claim independently`() {
        val ledger = RecordingFireLedger()
        val a = assertIs<Claim.Claimed>(ledger.claim("a", 1))
        val b = assertIs<Claim.Claimed>(ledger.claim("b", 1))
        assertNotEquals(a.token, b.token)
    }

    @Test
    fun `a finished fire is done and cannot be claimed again`() {
        val ledger = RecordingFireLedger()
        val c = assertIs<Claim.Claimed>(ledger.claim("s1", 1))
        assertTrue(ledger.release(c.token, FireOutcome.DONE))
        assertEquals(Claim.AlreadyDone, ledger.claim("s1", 2))
        assertEquals(FireState.DONE, ledger.stateOf("s1"))
    }

    @Test
    fun `a failed fire can be claimed again for the retry`() {
        val ledger = RecordingFireLedger()
        val first = assertIs<Claim.Claimed>(ledger.claim("s1", 1))
        assertTrue(ledger.release(first.token, FireOutcome.FAILED, RecordingProblem.NETWORK))
        assertEquals(FireState.FAILED, ledger.stateOf("s1"))
        val second = assertIs<Claim.Claimed>(ledger.claim("s1", 2))
        assertNotEquals(first.token, second.token)
    }

    @Test
    fun `a stale token cannot release a newer claim`() {
        val ledger = RecordingFireLedger()
        val first = assertIs<Claim.Claimed>(ledger.claim("s1", 1))
        ledger.release(first.token, FireOutcome.FAILED)
        val second = assertIs<Claim.Claimed>(ledger.claim("s1", 2))
        assertFalse("the old owner finishing late changes nothing", ledger.release(first.token, FireOutcome.DONE))
        assertEquals(FireState.RUNNING, ledger.stateOf("s1"))
        assertTrue(ledger.release(second.token, FireOutcome.DONE))
    }

    @Test
    fun `releasing an unknown token is a no-op`() {
        val ledger = RecordingFireLedger()
        assertFalse(ledger.release("nope#1", FireOutcome.DONE))
        assertTrue(ledger.snapshot().entries.isEmpty())
    }

    @Test
    fun `a token releases only once`() {
        val ledger = RecordingFireLedger()
        val c = assertIs<Claim.Claimed>(ledger.claim("s1", 1))
        assertTrue(ledger.release(c.token, FireOutcome.DONE))
        assertFalse("done stays done", ledger.release(c.token, FireOutcome.FAILED))
        assertEquals(FireState.DONE, ledger.stateOf("s1"))
    }

    @Test
    fun `recoverAfterRestart turns a running claim into a stalled failure that can be retried`() {
        val before = RecordingFireLedger()
        before.claim("running", 5)
        val done = assertIs<Claim.Claimed>(before.claim("finished", 5))
        before.release(done.token, FireOutcome.DONE)

        // The process died: only the snapshot survives.
        val after = RecordingFireLedger(before.snapshot())
        assertEquals(listOf("running"), after.recoverAfterRestart())
        assertEquals(FireState.FAILED, after.stateOf("running"))
        assertEquals(RecordingProblem.STALLED, after.snapshot().entries.first { it.scheduleId == "running" }.problem)
        assertEquals("a finished fire stays finished", FireState.DONE, after.stateOf("finished"))
        assertIs<Claim.Claimed>(after.claim("running", 1), "the retry can claim it")
        assertEquals(Claim.AlreadyDone, after.claim("finished", 1))
    }

    @Test
    fun `recoverAfterRestart on a clean ledger recovers nothing`() {
        assertTrue(RecordingFireLedger().recoverAfterRestart().isEmpty())
    }

    @Test
    fun `without recovery a claim from a dead process still blocks`() {
        val before = RecordingFireLedger()
        before.claim("s1", 5)
        val after = RecordingFireLedger(before.snapshot())
        assertEquals(Claim.AlreadyRunning, after.claim("s1", 1))
    }

    @Test
    fun `tokens stay unique across a restart`() {
        val before = RecordingFireLedger()
        val a = assertIs<Claim.Claimed>(before.claim("s1", 1))
        before.release(a.token, FireOutcome.FAILED)
        val after = RecordingFireLedger(before.snapshot())
        val b = assertIs<Claim.Claimed>(after.claim("s1", 1))
        assertNotEquals(a.token, b.token)
    }

    @Test
    fun `forget clears a schedule so a reused id starts clean`() {
        val ledger = RecordingFireLedger()
        val c = assertIs<Claim.Claimed>(ledger.claim("s1", 1))
        ledger.release(c.token, FireOutcome.DONE)
        ledger.forget("s1")
        assertNull(ledger.stateOf("s1"))
        assertIs<Claim.Claimed>(ledger.claim("s1", 2))
    }

    @Test
    fun `the snapshot is serializable and restores the same ledger`() {
        val ledger = RecordingFireLedger()
        ledger.claim("a", 1)
        val b = assertIs<Claim.Claimed>(ledger.claim("b", 2))
        ledger.release(b.token, FireOutcome.FAILED, RecordingProblem.PROVIDER_REFUSED)
        val text = Json.encodeToString(FireLedgerState.serializer(), ledger.snapshot())
        val back = RecordingFireLedger(Json.decodeFromString(FireLedgerState.serializer(), text))
        assertEquals(ledger.snapshot(), back.snapshot())
        assertEquals(Claim.AlreadyRunning, back.claim("a", 3))
        assertEquals(
            FireEntry("b", FireState.FAILED, b.token, 2, RecordingProblem.PROVIDER_REFUSED),
            back.snapshot().entries.first { it.scheduleId == "b" })
    }

    @Test
    fun `a failure with no stated problem is recorded as unknown`() {
        val ledger = RecordingFireLedger()
        val c = assertIs<Claim.Claimed>(ledger.claim("s1", 1))
        ledger.release(c.token, FireOutcome.FAILED)
        assertEquals(RecordingProblem.UNKNOWN, ledger.snapshot().entries.single().problem)
    }
}
