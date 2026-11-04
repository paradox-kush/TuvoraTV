package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.nuvio.tv.core.memory.MemoryTier
import com.nuvio.tv.core.memory.demuxerBytesFor
import org.junit.Test

/**
 * The demuxer budget was a flat 64+64MiB — the largest native cache in the fleet, granted on
 * the smallest devices. These pin the locked tiering decision: LOW = 48+16MiB, everything
 * else = 64+32MiB (mobile's proven forward:back split).
 */
class MpvDemuxerBytesTest {

    private val mib = 1024L * 1024L

    @Test
    fun `low tier gets 48 MiB forward and 16 MiB back`() {
        val bytes = demuxerBytesFor(MemoryTier.LOW)
        assertEquals(48L * mib, bytes.maxBytes)
        assertEquals(16L * mib, bytes.maxBackBytes)
    }

    @Test
    fun `mid and high tiers get 64 MiB forward and 32 MiB back`() {
        for (tier in listOf(MemoryTier.MID, MemoryTier.HIGH)) {
            val bytes = demuxerBytesFor(tier)
            assertEquals("$tier forward", 64L * mib, bytes.maxBytes)
            assertEquals("$tier back", 32L * mib, bytes.maxBackBytes)
        }
    }

    @Test
    fun `every tier spends more on the forward window than on seek-back`() {
        for (tier in MemoryTier.entries) {
            val bytes = demuxerBytesFor(tier)
            assertTrue(
                "$tier must favour the forward window that absorbs network jitter",
                bytes.maxBytes > bytes.maxBackBytes,
            )
        }
    }

    @Test
    fun `no tier exceeds the old flat budget`() {
        for (tier in MemoryTier.entries) {
            val bytes = demuxerBytesFor(tier)
            assertTrue("$tier total", bytes.maxBytes + bytes.maxBackBytes <= 128L * mib)
        }
    }
}
