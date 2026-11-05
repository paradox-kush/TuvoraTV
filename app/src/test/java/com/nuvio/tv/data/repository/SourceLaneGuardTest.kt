package com.nuvio.tv.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** B226 family: a provider lane must always end exactly once, as a value or an ordinary exception the lane's catch records. */
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
    fun aProvidersOwnInnerTimeoutIsNotMislabelledAsTheLaneDeadline() = runTest {
        try {
            SourceLaneGuard.bounded<Unit> { withTimeout(10) { delay(1_000) } }
            fail("expected SourceLaneInterruptedException")
        } catch (e: SourceLaneInterruptedException) {
            assertEquals(10, currentTime)
        }
    }

    @Test
    fun noDeadlineLaneIsNeverCutButStillConvertsForeignCancellation() = runTest {
        assertEquals("ok", SourceLaneGuard.bounded(deadlineMs = null) { delay(500_000); "ok" })
        try {
            SourceLaneGuard.bounded<Unit>(deadlineMs = null) { throw CancellationException("x") }
            fail("expected SourceLaneInterruptedException")
        } catch (_: SourceLaneInterruptedException) {
        }
    }

    @Test
    fun theCallersOwnCancellationPropagatesAndStopsTheProvider() = runTest {
        var reportedValue = false
        var providerStopped = false
        val job = launch {
            SourceLaneGuard.bounded {
                try { delay(10_000) } finally { providerStopped = true }
            }
            reportedValue = true
        }
        runCurrent()
        job.cancel()
        job.join()
        assertFalse(reportedValue)
        assertTrue(providerStopped)
    }

    @Test
    fun aLaneIsCountedExactlyOnceHoweverManyPathsReportItDone() {
        val counted = AtomicInteger(0)
        val lane = OnceLane { counted.incrementAndGet() }
        lane.finish() // streamLocalPlugins' own completion callback
        lane.finish() // the outer catch
        lane.finish() // the finally
        assertEquals(1, counted.get())
    }

    /**
     * The aggregator's counting shape (completedJobs vs totalJobs, channel closed by the last lane):
     * add-on, server and plugin lanes each throw a provider-side cancellation; the channel must close
     * only after every lane has finished, even when one lane reports done several times.
     */
    @Test
    fun theListSettlesAfterEveryLaneAndNeverClosesEarly() = runTest {
        val total = 4
        val channel = Channel<String>(Channel.UNLIMITED)
        val done = AtomicInteger(0)
        fun lane() = OnceLane { if (done.incrementAndGet() >= total) channel.close() }
        val failures = mutableListOf<Throwable>()

        coroutineScope {
            val addon = lane()
            launch { // add-on lane
                try { SourceLaneGuard.bounded<Unit> { throw CancellationException("Source choices changed") } }
                catch (e: Exception) { if (e is CancellationException) throw e; failures += e }
                finally { addon.finish() }
            }
            val server = lane()
            launch { // server lane
                try { SourceLaneGuard.bounded<Unit> { throw CancellationException("Server scope changed") } }
                catch (e: Exception) { if (e is CancellationException) throw e; failures += e }
                finally { server.finish() }
            }
            val plugin = lane()
            launch { // the production plugin-lane runner: failure reported once, completion once
                runPluginLane("fallback", { failures += IllegalStateException(it) }, { plugin.finish() }) {
                    throw CancellationException("plugin side")
                }
                plugin.finish() // the outer path also reports done
            }
            val slow = lane()
            launch { // a healthy, slower lane: its result must not hit a channel closed early by double counting
                delay(5)
                assertTrue(channel.trySend("late").isSuccess)
                slow.finish()
            }
        }
        assertEquals(3, failures.size)
        assertTrue(failures.any { it.message == "plugin side" })
        assertEquals(total, done.get())
        assertEquals("late", channel.tryReceive().getOrNull())
        assertTrue(channel.trySend("x").isFailure) // closed once, after all lanes
    }

    @Test
    fun aPluginSideFailureIsReportedOnceAndACompleteLaneIsCountedOnce() = runTest {
        val details = mutableListOf<String>()
        var completed = 0
        runPluginLane("fallback", { details += it }, { completed++ }) { throw java.io.IOException("scraper blew up") }
        assertEquals(listOf("scraper blew up"), details)
        assertEquals(1, completed)
        details.clear(); completed = 0
        runPluginLane("fallback", { details += it }, { completed++ }) { throw IllegalStateException() }
        assertEquals(listOf("fallback"), details)
    }

    @Test
    fun aPluginLaneThatReturnsNoResultsIsNotAFailure() = runTest {
        val details = mutableListOf<String>()
        var completed = 0
        runPluginLane("fallback", { details += it }, { completed++ }) { /* every scraper answered empty */ }
        assertTrue(details.isEmpty())
        assertEquals(1, completed)
    }

    @Test
    fun theCallersCancellationLeavesAPluginLaneWithoutAFailureRecord() = runTest {
        val details = mutableListOf<String>()
        var completed = 0
        val job = launch {
            runPluginLane("fallback", { details += it }, { completed++ }) { delay(10_000) }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(details.isEmpty())
        assertEquals(1, completed)
    }
}
