package com.nuvio.tv.core.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 contract section 9: the TV code screen's bounded, lifecycle-bound wait, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupWaitPolicyTest {

    @Test
    fun `cadence is 3 s then 6 s and slows to 30 s after three failures`() {
        assertEquals(3_000L, SetupWaitPolicy.delayBeforeNextCall(callsMade = 0, consecutiveFailures = 0))
        assertEquals(6_000L, SetupWaitPolicy.delayBeforeNextCall(1, 0))
        assertEquals(6_000L, SetupWaitPolicy.delayBeforeNextCall(9, 2))
        assertEquals(30_000L, SetupWaitPolicy.delayBeforeNextCall(9, 3))
        assertEquals(30_000L, SetupWaitPolicy.delayBeforeNextCall(0, 5))
    }

    @Test
    fun `success is a key that was not in the snapshot`() {
        assertEquals(setOf("new"), SetupWaitPolicy.newKeys(setOf("a", "b"), setOf("a", "b", "new")))
        assertEquals(emptySet<String>(), SetupWaitPolicy.newKeys(setOf("a", "b"), setOf("a")))
        assertEquals("a removal is not success", emptySet<String>(), SetupWaitPolicy.newKeys(setOf("a", "b"), setOf("b")))
    }

    @Test
    fun `an unchanged answer never finishes and the wait stops at five minutes after at most 50 calls`() = runTest {
        var calls = 0
        val outcome = SetupWaitPolicy.run(setOf("a"), startedAtMs = currentTime, now = { currentTime }) {
            calls++; Result.success(setOf("a"))
        }
        assertEquals(SetupWaitPolicy.Outcome.TimedOut, outcome)
        assertEquals("one call per 6 s from 3 s: 3,9,...,297", 50, calls)
        assertTrue("no request past the cap (virtual time ${currentTime}ms)", currentTime <= SetupWaitPolicy.HARD_STOP_MS)
    }

    @Test
    fun `a new playlist key ends the wait at once with that key`() = runTest {
        var calls = 0
        val outcome = SetupWaitPolicy.run(setOf("a"), currentTime, { currentTime }) {
            calls++
            Result.success(if (calls < 3) setOf("a") else setOf("a", "k2"))
        }
        assertEquals(SetupWaitPolicy.Outcome.Found(listOf("k2")), outcome)
        assertEquals(3, calls)
        assertEquals("3 s + 6 s + 6 s", 15_000L, currentTime)
    }

    @Test
    fun `calls never overlap even when one is slower than the interval`() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        var calls = 0
        val job = launch {
            SetupWaitPolicy.run(setOf("a"), currentTime, { currentTime }) {
                inFlight++; maxInFlight = maxOf(maxInFlight, inFlight); calls++
                delay(20_000) // far slower than the 6 s interval
                inFlight--
                Result.success(setOf("a"))
            }
        }
        advanceTimeBy(200_000)
        assertEquals("at most one request in flight", 1, maxInFlight)
        assertTrue("slow calls mean fewer calls, never more: $calls", calls < 50)
        job.cancel()
    }

    @Test
    fun `three consecutive failures back the cadence off to 30 s`() = runTest {
        val times = mutableListOf<Long>()
        val job = launch {
            SetupWaitPolicy.run(setOf("a"), currentTime, { currentTime }) {
                times += currentTime
                Result.failure(java.io.IOException("offline"))
            }
        }
        advanceTimeBy(200_000)
        // 3 s, 6 s, 6 s (three failures) and then 30 s apart.
        assertEquals(listOf(3_000L, 9_000L, 15_000L, 45_000L, 75_000L, 105_000L), times.take(6))
        job.cancel()
    }

    @Test
    fun `a success after failures returns the cadence to 6 s`() = runTest {
        val times = mutableListOf<Long>()
        val job = launch {
            SetupWaitPolicy.run(setOf("a"), currentTime, { currentTime }) {
                times += currentTime
                if (times.size <= 3 || times.size == 5) Result.failure(RuntimeException("x")) else Result.success(setOf("a"))
            }
        }
        advanceTimeBy(150_000)
        // calls 1-3 fail (backoff), call 4 succeeds, call 5 fails (only one), then 6 s again.
        assertEquals(30_000L, times[3] - times[2])
        assertEquals(6_000L, times[4] - times[3])
        assertEquals(6_000L, times[5] - times[4])
        job.cancel()
    }

    @Test
    fun `leaving the screen cancels the wait and no request is made afterwards`() = runTest {
        var calls = 0
        val job = launch {
            SetupWaitPolicy.run(setOf("a"), currentTime, { currentTime }) { calls++; Result.success(setOf("a")) }
        }
        advanceTimeBy(10_000)
        val before = calls
        assertEquals("3 s and 9 s", 2, before)
        job.cancel() // the screen left RESUMED, or the code was typed on the TV
        advanceTimeBy(600_000)
        assertEquals("no request after cancel", before, calls)
    }

    @Test
    fun `cancelling during a request does not count it as a failure or issue another`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            SetupWaitPolicy.run(setOf("a"), currentTime, { currentTime }) { calls++; gate.await(); Result.success(setOf("a")) }
        }
        advanceTimeBy(3_500)
        assertEquals(1, calls)
        job.cancel()
        advanceTimeBy(100_000)
        assertEquals(1, calls)
    }

    @Test
    fun `when the opening snapshot failed the first answer becomes the baseline and is not a success`() = runTest {
        var calls = 0
        val outcome = SetupWaitPolicy.run(snapshot = null, startedAtMs = currentTime, now = { currentTime }) {
            calls++
            when (calls) {
                1 -> Result.success(setOf("old"))            // baseline: an existing managed playlist
                2 -> Result.success(setOf("old"))
                else -> Result.success(setOf("old", "new"))
            }
        }
        assertEquals(SetupWaitPolicy.Outcome.Found(listOf("new")), outcome)
        assertEquals(3, calls)
    }

    @Test
    fun `the cap counts from the screen's first open across a re-resume`() = runTest {
        // The screen opened 290 s ago (then left and came back): only 10 s of budget remain.
        val startedAt = -290_000L
        var calls = 0
        val outcome = SetupWaitPolicy.run(setOf("a"), startedAt, { currentTime }) { calls++; Result.success(setOf("a")) }
        assertEquals(SetupWaitPolicy.Outcome.TimedOut, outcome)
        assertEquals("3 s then 9 s fits, 15 s does not", 2, calls)
    }

    @Test
    fun `found keys come back sorted and a baseline taken from the first answer is reported`() = runTest {
        var baseline: Set<String>? = null
        var calls = 0
        val outcome = SetupWaitPolicy.run(null, currentTime, { currentTime }, onBaseline = { baseline = it }) {
            calls++
            when (calls) { 1 -> Result.success(setOf("old")); else -> Result.success(setOf("old", "z", "b")) }
        }
        assertEquals(setOf("old"), baseline)
        assertEquals(SetupWaitPolicy.Outcome.Found(listOf("b", "z")), outcome)
    }

    @Test
    fun `nothing is returned after the wait was cancelled`() = runTest {
        var result: SetupWaitPolicy.Outcome? = null
        val job = launch {
            result = SetupWaitPolicy.run(setOf("a"), currentTime, { currentTime }) { Result.success(setOf("a", "new")) }
        }
        job.cancel() // the screen left before the first call
        advanceTimeBy(600_000)
        assertEquals(null, result)
    }
}
