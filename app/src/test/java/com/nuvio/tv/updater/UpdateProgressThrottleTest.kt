package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The downloader reports on every 8 KB read: about 7,000 reports for the 58 MB APK, each one used to rebuild the
 * update state and recompose the app root (B274). JUnit order: assertEquals(message, expected, actual).
 */
class UpdateProgressThrottleTest {

    @Test
    fun `a full download publishes a few times a second at most, not per read`() {
        val throttle = UpdateProgressThrottle()
        val total = 58L * 1024 * 1024
        val chunk = 8192L
        var published = 0
        var downloaded = 0L
        var now = 0L
        // 1 ms per 8 KB read = a fast link: 58 MB in about 7 s.
        while (downloaded < total) {
            downloaded = (downloaded + chunk).coerceAtMost(total)
            now += 1
            if (throttle.accept(downloaded, total, now) != null) published++
        }
        // First + one per 250 ms + final; the old code published once per read.
        assertTrue("published $published times for ~7,000 reads", published <= 40)
    }

    @Test
    fun `first report publishes and the final one always does`() {
        val throttle = UpdateProgressThrottle()
        assertEquals("first", 0.01f, throttle.accept(1, 100, 0))
        assertEquals("final", 1f, throttle.accept(100, 100, 1))
    }

    @Test
    fun `same whole percent is skipped even after the interval`() {
        val throttle = UpdateProgressThrottle()
        throttle.accept(10, 1000, 0)
        assertNull("same percent", throttle.accept(15, 1000, 10_000))
    }

    @Test
    fun `a new percent inside the interval waits`() {
        val throttle = UpdateProgressThrottle(minIntervalMs = 250)
        throttle.accept(10, 1000, 0)
        assertNull("too soon", throttle.accept(30, 1000, 100))
        assertEquals("after the interval", 0.03f, throttle.accept(30, 1000, 250))
    }

    @Test
    fun `unknown total publishes nothing`() {
        val throttle = UpdateProgressThrottle()
        assertNull("no total", throttle.accept(500, null, 0))
        assertNull("zero total", throttle.accept(500, 0, 1000))
    }
}
