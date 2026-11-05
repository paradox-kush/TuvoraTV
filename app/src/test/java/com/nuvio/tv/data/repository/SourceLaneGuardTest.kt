package com.nuvio.tv.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** B226 family: a provider lane must always end as a value or an ordinary exception the lane's catch records. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceLaneGuardTest {

    @Test
    fun providerSideCancellationBecomesAnOrdinaryException() = runTest {
        try {
            SourceLaneGuard.bounded<Unit> { throw CancellationException("Source choices changed") }
            fail("expected SourceLaneInterruptedException")
        } catch (e: SourceLaneInterruptedException) {
            assertEquals("Source choices changed", e.message)
        }
    }

    @Test
    fun aProviderThatNeverAnswersFailsAtTheLaneDeadline() = runTest {
        try {
            SourceLaneGuard.bounded<Unit> { delay(Long.MAX_VALUE) }
            fail("expected SourceLaneTimeoutException")
        } catch (e: SourceLaneTimeoutException) {
            assertEquals(SourceLaneGuard.LANE_DEADLINE_MS, currentTime)
        }
    }

    @Test
    fun valuesPassThrough() = runTest {
        assertEquals(7, SourceLaneGuard.bounded { 7 })
    }

    @Test
    fun theCallersOwnCancellationStillPropagates() = runTest {
        var reached = false
        val job = launch {
            SourceLaneGuard.bounded { delay(10_000) }
            reached = true
        }
        runCurrent()
        job.cancel()
        job.join()
        assertFalse(reached)
        assertTrue(job.isCancelled)
    }
}
