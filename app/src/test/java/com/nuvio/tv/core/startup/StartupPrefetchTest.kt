package com.nuvio.tv.core.startup

import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupPrefetchTest {

    @Test
    fun `a ready value is returned`() {
        assertEquals("value from the prefetch thread", true, StartupPrefetch("t") { true }.await(5_000))
    }

    @Test
    fun `a value that misses the deadline is null, so the caller takes its fallback`() {
        val release = CountDownLatch(1)
        val prefetch = StartupPrefetch("t") { release.await(); true }
        assertNull("main never waits past the deadline", prefetch.await(50))
        release.countDown()
        assertEquals("a later await still gets it", true, prefetch.await(5_000))
    }

    @Test
    fun `a failed read is null, never a guessed value`() {
        assertNull("failure = unknown", StartupPrefetch<Boolean>("t") { error("disk") }.await(5_000))
    }
}
