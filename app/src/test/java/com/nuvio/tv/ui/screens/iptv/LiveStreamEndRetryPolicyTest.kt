package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStreamEndRetryPolicyTest {

    @Test
    fun `live EOF retries indefinitely with capped backoff`() {
        assertEquals(1_000L, LiveStreamEndRetryPolicy.delayMs(1))
        assertEquals(2_000L, LiveStreamEndRetryPolicy.delayMs(2))
        assertEquals(5_000L, LiveStreamEndRetryPolicy.delayMs(3))
        assertEquals(10_000L, LiveStreamEndRetryPolicy.delayMs(4))
        assertEquals(20_000L, LiveStreamEndRetryPolicy.delayMs(5))
        assertEquals(20_000L, LiveStreamEndRetryPolicy.delayMs(5_000))
    }
}
