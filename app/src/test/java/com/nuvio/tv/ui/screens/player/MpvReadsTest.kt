package com.nuvio.tv.ui.screens.player

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * C1 (ANR rule): a libmpv property read takes the core lock and can wedge for seconds, so the player
 * reads mpv on one serial background lane ([MpvReads]) and posts the result back, never on Main.
 */
class MpvReadsTest {

    @Test
    fun anMpvReadNeverRunsOnTheCallingMainThread() = runBlocking {
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
        try {
            val (caller, reader) = withContext(main) {
                val caller = Thread.currentThread()
                caller to MpvReads.read { Thread.currentThread() }
            }
            assertNotSame("the fake mpv reader ran on the caller's (main) thread", caller, reader)
        } finally {
            main.close()
        }
    }

    @Test
    fun mpvReadsAreSerialisedOnOneLane() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        (1..16).map {
            async {
                MpvReads.read {
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    Thread.sleep(2)
                    inFlight.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals("libmpv reads must not overlap", 1, maxInFlight.get())
    }

    /** Sync 17: upstream's applySubtitleStyle reads the subtitle codec; its non-suspending callers post it. */
    @Test
    fun aPostedMpvCallNeverRunsOnTheCallerAndKeepsTheLaneSerial() {
        val caller = Thread.currentThread()
        val ran = java.util.concurrent.CountDownLatch(2)
        val threads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        repeat(2) {
            MpvReads.post {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                threads += Thread.currentThread()
                Thread.sleep(5)
                inFlight.decrementAndGet()
                ran.countDown()
            }
        }
        org.junit.Assert.assertTrue("posted mpv work never ran", ran.await(5, java.util.concurrent.TimeUnit.SECONDS))
        threads.forEach { assertNotSame("posted mpv work ran on the caller (main) thread", caller, it) }
        assertEquals("posted mpv work must not overlap", 1, maxInFlight.get())
    }
}
