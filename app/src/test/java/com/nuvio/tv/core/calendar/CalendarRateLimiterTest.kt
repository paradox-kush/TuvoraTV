// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarRateLimiterTest {

    @Test fun neverMoreThanFifteenCallsInAnyTenSeconds() = runTest {
        val limiter = CalendarRateLimiter(nowMs = { testScheduler.currentTime })
        val times = mutableListOf<Long>()
        repeat(80) {
            limiter.acquire()
            times += testScheduler.currentTime
        }
        times.forEach { start ->
            val inWindow = times.count { it >= start && it < start + 10_000L }
            assertTrue("$inWindow calls in the 10 s from $start", inWindow <= 15)
        }
        assertEquals("burst of five is immediate", 0L, times[4])
        assertTrue("the sixth waits", times[5] > 0L)
        assertTrue("80 calls took ${times.last()} ms", times.last() in 74_000L..76_000L)
    }

    @Test fun concurrentCallersShareOneBucket() = runTest {
        val limiter = CalendarRateLimiter(nowMs = { testScheduler.currentTime })
        val times = mutableListOf<Long>()
        repeat(4) {
            launch {
                repeat(10) {
                    limiter.acquire()
                    synchronized(times) { times += testScheduler.currentTime }
                }
            }
        }
        testScheduler.advanceUntilIdle()
        assertEquals("every call went through", 40, times.size)
        times.forEach { start -> assertTrue("window from $start", times.count { it >= start && it < start + 10_000L } <= 15) }
    }

    @Test fun aLookupTakesTwoPermits() = runTest {
        val limiter = CalendarRateLimiter(nowMs = { testScheduler.currentTime })
        repeat(2) { limiter.acquire(2) }
        assertEquals("two lookups fit the burst", 0L, testScheduler.currentTime)
        limiter.acquire(2)
        assertEquals("the third waits for refill", 1_000L, testScheduler.currentTime)
    }

    @Test fun retryAfterHoldsEveryoneBack() = runTest {
        val limiter = CalendarRateLimiter(nowMs = { testScheduler.currentTime })
        limiter.acquire()
        limiter.pauseFor(5_000L)
        limiter.acquire()
        assertTrue("held for the Retry-After", testScheduler.currentTime >= 5_000L)
    }

    @Test fun retryAfterHeader() {
        assertEquals("seconds", 3_000L, parseRetryAfterMs("3"))
        assertEquals("floor 1 s", 1_000L, parseRetryAfterMs("0"))
        assertEquals("cap 2 min", 120_000L, parseRetryAfterMs("3600"))
        assertNull("absent", parseRetryAfterMs(null))
        assertNull("http date", parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
    }
}
