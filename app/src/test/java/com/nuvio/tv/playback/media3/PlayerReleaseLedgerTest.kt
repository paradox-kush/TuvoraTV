package com.nuvio.tv.playback.media3

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B271: tile players now release off the main thread, so "the next player waits for the codecs" is no longer
 * implied by Main being blocked. The ledger makes it explicit and bounded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerReleaseLedgerTest {
    @Test
    fun `an idle ledger lets a new player start at once`() = runTest {
        assertTrue("nothing to wait for", PlayerReleaseLedger().awaitIdle(5_000))
        assertEquals("no virtual time spent", 0L, testScheduler.currentTime)
    }

    @Test
    fun `a new player waits for every release in flight`() = runTest {
        val ledger = PlayerReleaseLedger()
        val a = ledger.begin()
        val b = ledger.begin()
        var idle: Boolean? = null
        launch { idle = ledger.awaitIdle(5_000) }
        runCurrent()
        a.end()
        runCurrent()
        assertEquals("one release still pending", null, idle)
        b.end()
        runCurrent()
        assertEquals("both released", true, idle)
    }

    @Test
    fun `the wait is bounded and never ends a pending release for it`() = runTest {
        val ledger = PlayerReleaseLedger()
        ledger.begin()
        var idle: Boolean? = null
        launch { idle = ledger.awaitIdle(2_000) }
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals("deadline reached", false, idle)
        assertEquals(1, ledger.pending)
    }

    @Test
    fun `ending a token twice counts once`() = runTest {
        val ledger = PlayerReleaseLedger()
        val t = ledger.begin()
        t.end()
        t.end()
        assertEquals(0, ledger.pending)
        assertFalse(ledger.begin().let { ledger.pending != 1 })
    }
}
