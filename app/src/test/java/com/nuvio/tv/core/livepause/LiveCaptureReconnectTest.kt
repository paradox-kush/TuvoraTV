package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.livepause.LiveCaptureReconnect.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCaptureReconnectTest {
    @Test
    fun `backoff doubles from two seconds and caps at eight`() {
        val r = LiveCaptureReconnect(singleUseLink = false, maxAttemptsWithoutProgress = 5)
        val delays = List(5) { (r.onDrop() as Decision.Retry).delayMs }
        assertEquals("2, 4, 8, 8, 8", listOf(2_000L, 4_000L, 8_000L, 8_000L, 8_000L), delays)
    }

    @Test
    fun `gives up after the maximum attempts without progress`() {
        val r = LiveCaptureReconnect(singleUseLink = false)
        repeat(3) { assertTrue("attempt ${it + 1} retries", r.onDrop() is Decision.Retry) }
        assertEquals("the fourth drop gives up", Decision.GiveUp, r.onDrop())
        assertEquals("and keeps giving up", Decision.GiveUp, r.onDrop())
    }

    @Test
    fun `progress resets the counter and the backoff`() {
        val r = LiveCaptureReconnect(singleUseLink = false)
        r.onDrop(); r.onDrop()
        assertEquals("two attempts so far", 2, r.attempts)
        r.onProgress()
        assertEquals("reset", 0, r.attempts)
        assertEquals("backoff restarts", 2_000L, (r.onDrop() as Decision.Retry).delayMs)
        assertEquals("one attempt", 1, r.attempts)
    }

    @Test
    fun `progress after the budget is spent allows retries again`() {
        val r = LiveCaptureReconnect(singleUseLink = false)
        repeat(3) { r.onDrop() }
        r.onProgress()
        assertTrue("retry again", r.onDrop() is Decision.Retry)
    }

    @Test
    fun `a single-use link always mints a fresh link`() {
        val r = LiveCaptureReconnect(singleUseLink = true)
        repeat(3) { assertTrue("attempt ${it + 1} mints", (r.onDrop() as Decision.Retry).mintFreshLink) }
    }

    @Test
    fun `a reusable link never mints`() {
        val r = LiveCaptureReconnect(singleUseLink = false)
        repeat(3) { assertFalse("attempt ${it + 1} reuses", (r.onDrop() as Decision.Retry).mintFreshLink) }
    }

    @Test
    fun `the first open of a single-use link mints because the player spent its url`() {
        assertTrue("single use", LiveCaptureReconnect(singleUseLink = true).mintOnFirstOpen)
        assertFalse("reusable", LiveCaptureReconnect(singleUseLink = false).mintOnFirstOpen)
    }
}
