package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.playback.ui.LiveReconnectReason
import com.nuvio.tv.playback.ui.LiveReconnectStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReconnectStatusTextPolicyTest {
    @Test
    fun `every reason has its own sentence`() {
        val resources = LiveReconnectReason.entries.map(LiveReconnectStatusTextPolicy::reasonRes)
        assertEquals("one string per reason", LiveReconnectReason.entries.size, resources.toSet().size)
    }

    @Test
    fun `the attempt counter shows only with a known attempt inside the budget`() {
        assertTrue(LiveReconnectStatusTextPolicy.showsAttempt(LiveReconnectStatus(2, 3, LiveReconnectReason.STREAM_STOPPED)))
        assertFalse("before the first attempt", LiveReconnectStatusTextPolicy.showsAttempt(LiveReconnectStatus(null, null, LiveReconnectReason.STREAM_STOPPED)))
        assertFalse("never 4 of 3", LiveReconnectStatusTextPolicy.showsAttempt(LiveReconnectStatus(4, 3, LiveReconnectReason.STREAM_STOPPED)))
    }
}
