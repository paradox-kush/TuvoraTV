package com.nuvio.tv.core.multiview

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileOpenStaggerTest {
    @Test
    fun `the next tile starts at once when the previous one has settled`() {
        assertEquals(0L, TileOpenStaggerPolicy.remainingWaitMs(previousSettled = true, previousStartedAtMs = 0, nowMs = 10))
    }

    @Test
    fun `an unsettled previous tile is waited for up to two seconds then not`() {
        assertEquals(2_000L, TileOpenStaggerPolicy.remainingWaitMs(false, 1_000, 1_000))
        assertEquals(1_250L, TileOpenStaggerPolicy.remainingWaitMs(false, 1_000, 1_750))
        assertEquals(0L, TileOpenStaggerPolicy.remainingWaitMs(false, 1_000, 3_000))
        assertEquals("never negative", 0L, TileOpenStaggerPolicy.remainingWaitMs(false, 1_000, 9_000))
    }

    private fun TestScope.gate() = TileOpenGate(nowMs = { testScheduler.currentTime })

    @Test
    fun `the first tile opens immediately`() = runTest {
        val g = gate()
        g.awaitTurn()
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `the second tile waits for the first frame and goes the moment it arrives`() = runTest {
        val g = gate()
        val first = g.awaitTurn()
        val second = async { g.awaitTurn() }
        runCurrent()
        assertFalse("waits while the first has no frame", second.isCompleted)
        advanceTimeBy(700)
        first.settle()
        runCurrent()
        assertTrue(second.isCompleted)
        assertEquals("started on the first frame, not at 2 s", 700L, testScheduler.currentTime)
    }

    @Test
    fun `the second tile goes after two seconds if the first never shows a frame`() = runTest {
        val g = gate()
        g.awaitTurn()
        val second = async { g.awaitTurn() }
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(second.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(second.isCompleted)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun `four tiles open in order each behind the one before`() = runTest {
        val g = gate()
        val order = ArrayList<Int>()
        val tickets = ArrayList<TileOpenGate.Ticket>()
        val jobs = (0 until 4).map { i ->
            async { g.awaitTurn().also { order += i; tickets += it } }
        }
        runCurrent()
        assertEquals(listOf(0), order)
        tickets[0].settle(); runCurrent()
        assertEquals(listOf(0, 1), order)
        tickets[1].settle(); runCurrent()
        tickets[2].settle(); runCurrent()
        assertEquals(listOf(0, 1, 2, 3), order)
        assertTrue(jobs.all { it.isCompleted })
        assertEquals("no waiting once each settles", 0L, testScheduler.currentTime)
    }

    @Test
    fun `a released or failed tile settles its ticket twice harmlessly`() = runTest {
        val g = gate()
        val t = g.awaitTurn()
        t.settle()
        t.settle()
        val next = async { g.awaitTurn() }
        runCurrent()
        assertTrue(next.isCompleted)
    }
}
