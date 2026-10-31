package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** F48 on TV — a playlist with no catalog index is a failure the viewer can retry, never "no matches". */
class IptvSearchReadinessTest {
    @Test
    fun `a missing or zero build time is not ready`() {
        assertFalse("null", IptvSearchReadiness.isReady(null))
        assertFalse("zero", IptvSearchReadiness.isReady(0L))
        assertFalse("negative", IptvSearchReadiness.isReady(-5L))
        assertTrue("built", IptvSearchReadiness.isReady(1_700_000_000_000L))
    }

    @Test
    fun `requiring a missing index throws so the search ends Failed`() {
        val error = assertThrows(IptvSearchNotReadyException::class.java) { IptvSearchReadiness.require(null, "Movies index") }
        assertEquals("message", "Movies index is not available yet", error.message)
        IptvSearchReadiness.require(1L, "Movies index") // ready: no throw
    }
}
