package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.iptv.BoundedLoad
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LivePauseRetryBudgetTest {
    private val saved = BoundedLoad.sink
    @Before fun quiet() { BoundedLoad.sink = { _, _ -> } }
    @After fun restore() { BoundedLoad.sink = saved }

    @Test
    fun `the budget hands out its mints and refills on progress`() {
        val budget = LiveLinkBudget(max = 2)
        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse("third mint refused", budget.tryAcquire())
        assertFalse(budget.hasRemaining())
        budget.refill()
        assertTrue("a piece landed: the count starts over", budget.tryAcquire())
    }

    @Test
    fun `Retry restarts the capture without spending a second single use link on a retune`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        rig.singleUse = true
        startCapture(rig)
        rig.capture.flow.emit(LiveCaptureEvent.Failed(LivePauseFailure.PROVIDER_REFUSED))
        runCurrent()
        assertEquals(LivePauseState.Failed(LivePauseFailure.PROVIDER_REFUSED), rig.state)

        rig.controller.retry()
        runCurrent()

        assertFalse("the player's link is not spent: the capture mints its own", rig.engine.calls.contains("retune"))
        assertEquals("a fresh capture", 2, rig.captures.size)
        assertTrue(rig.state is LivePauseState.Preparing)
    }

    @Test
    fun `Retry is bounded by the mints already spent and then just goes live`() = runTest {
        val rig = KitRig(backgroundScope, StandardTestDispatcher(testScheduler))
        rig.singleUse = true
        startCapture(rig)
        // Mint 1 was the first capture. Retries take mints 2, 3 and 4 (the budget is 4).
        repeat(3) {
            rig.capture.flow.emit(LiveCaptureEvent.Failed(LivePauseFailure.PROVIDER_REFUSED))
            runCurrent()
            rig.controller.retry()
            runCurrent()
            advanceTimeBy(LivePauseController.CAPTURE_OPEN_DELAY_MS + 1)
            runCurrent()
        }
        assertEquals(4, rig.captures.size)
        rig.capture.flow.emit(LiveCaptureEvent.Failed(LivePauseFailure.PROVIDER_REFUSED))
        runCurrent()

        rig.controller.retry()
        runCurrent()

        assertEquals("no fifth capture: the budget is spent", 4, rig.captures.size)
        assertEquals("Retry falls back to Live", LivePauseState.Off, rig.state)
        assertEquals("retune", rig.engine.calls.last())
    }
}
