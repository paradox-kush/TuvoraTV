package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.internal.RecordingStallPolicy
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class RecordingStallPolicyTest {

    @Test
    fun `the stall limit is 30 seconds`() {
        assertEquals(30_000L, RecordingStallPolicy.STALL_MS)
    }

    @Test
    fun `no bytes for 30 seconds is a stall`() {
        val p = RecordingStallPolicy(startElapsedMs = 1_000)
        assertFalse(p.isStalled(1_000 + 29_999))
        assertTrue(p.isStalled(1_000 + 30_000))
    }

    @Test
    fun `progress restarts the clock`() {
        val p = RecordingStallPolicy(0)
        p.onProgress(20_000)
        assertFalse(p.isStalled(49_999))
        assertTrue(p.isStalled(50_000))
        p.onProgress(50_000)
        assertFalse(p.isStalled(70_000))
    }

    @Test
    fun `a progress report with an older time never moves the clock back`() {
        val p = RecordingStallPolicy(0)
        p.onProgress(20_000)
        p.onProgress(5_000)
        assertFalse("still counted from 20 s", p.isStalled(49_999))
    }

    @Test
    fun `a reading from before the last progress is not a stall`() {
        val p = RecordingStallPolicy(100_000)
        assertFalse(p.isStalled(50_000))
    }

    @Test
    fun `a large elapsed value from a long-running device is fine`() {
        val up = 40L * 24 * 60 * 60 * 1000
        val p = RecordingStallPolicy(up)
        p.onProgress(up + 10_000)
        assertFalse(p.isStalled(up + 39_999))
        assertTrue(p.isStalled(up + 40_000))
    }
}
