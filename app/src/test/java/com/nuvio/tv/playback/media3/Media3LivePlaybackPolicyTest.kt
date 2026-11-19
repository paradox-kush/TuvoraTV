package com.nuvio.tv.playback.media3

import com.nuvio.tv.core.memory.MemoryTier
import com.nuvio.tv.core.memory.exoTargetBufferBytesFor
import com.nuvio.tv.playback.core.BufferingPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Media3LivePlaybackPolicyTest {
    private val continuous = Media3BufferPlan(60_000, 60_000, 2_000, 4_000)

    @Test
    fun `auto live buffer is TiviMate continuous 60 s with 2 s start and 4 s rebuffer`() {
        assertEquals(
            "min == max so the live socket is never paused on a short time window",
            continuous,
            Media3LivePlaybackPolicy.bufferPlan(BufferingPreference.LOW_LATENCY_LIVE),
        )
    }

    @Test
    fun `a buffer the user chose is never overridden`() {
        assertNull("custom live buffer", Media3LivePlaybackPolicy.bufferPlan(BufferingPreference.CUSTOM))
        assertNull("balanced", Media3LivePlaybackPolicy.bufferPlan(BufferingPreference.BALANCED))
        assertNull("recommended", Media3LivePlaybackPolicy.bufferPlan(BufferingPreference.RECOMMENDED))
    }

    @Test
    fun `the live window per memory tier is capped by the existing byte budget`() {
        val mib = 1024 * 1024L
        val low = exoTargetBufferBytesFor(MemoryTier.LOW, 512 * mib)
        val lowPlan = Media3LivePlaybackPolicy.clampToByteBudget(continuous, low)
        assertEquals("LOW tier: 40 MiB at the assumed 16 Mbps", 20_971, lowPlan.maximumMs)
        assertEquals("still min == max", lowPlan.maximumMs, lowPlan.minimumMs)
        assertEquals("gates untouched", 2_000, lowPlan.playbackMs)
        assertEquals("gates untouched", 4_000, lowPlan.rebufferMs)

        val midHeap = exoTargetBufferBytesFor(MemoryTier.MID, 256 * mib)
        assertEquals("a quarter of a 256 MiB heap = 64 MiB", 33_554, Media3LivePlaybackPolicy.clampToByteBudget(continuous, midHeap).maximumMs)

        val roomy = exoTargetBufferBytesFor(MemoryTier.MID, 1024 * mib)
        assertEquals("100 MiB cap", 52_428, Media3LivePlaybackPolicy.clampToByteBudget(continuous, roomy).maximumMs)
    }

    @Test
    fun `a huge byte budget never raises the window above 60 s`() {
        assertEquals(continuous, Media3LivePlaybackPolicy.clampToByteBudget(continuous, 400 * 1024 * 1024))
    }

    @Test
    fun `a tiny byte budget never drops the window below the floor`() {
        val clamped = Media3LivePlaybackPolicy.clampToByteBudget(continuous, 8 * 1024 * 1024)
        assertEquals("floor", 8_000, clamped.minimumMs)
        assertEquals("floor", 8_000, clamped.maximumMs)
    }

    @Test
    fun `byte clamp leaves a non continuous plan alone`() {
        val vod = Media3BufferPlan(15_000, 50_000, 2_500, 5_000)
        assertEquals("vod plan", vod, Media3LivePlaybackPolicy.clampToByteBudget(vod, 1))
    }
}
