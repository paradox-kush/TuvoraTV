package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewCapacityPolicyTest {
    /** The Walmart Onn 4K as read from the device: AVC 134 fps at 1080p, 9 instances. */
    private val onn = DecoderFacts(
        avc = CodecFacts(declaredInstances = 9, throughputFps1080 = 134f),
        hevc = CodecFacts(declaredInstances = 9, throughputFps1080 = 246f),
    )

    private fun tiles(n: Int, height: Int = 1080, fps: Float = 50f, codec: TileCodec = TileCodec.AVC) =
        List(n) { TileLoad(codec, height, fps) }

    private fun assess(facts: DecoderFacts, tiles: List<TileLoad>, tier: MemoryClass = MemoryClass.HIGH) =
        MultiviewCapacityPolicy.assess(facts, tiles, tier)

    // ---- the Onn case ----

    @Test
    fun `two 1080p50 tiles are comfortable on the Onn`() {
        assertEquals(Capacity.Comfortable, assess(onn, tiles(2)))
    }

    @Test
    fun `four 1080p50 tiles are tight on decode throughput on the Onn`() {
        assertEquals(Capacity.Tight(CapacityReason.DecodeThroughput), assess(onn, tiles(4)))
    }

    @Test
    fun `four 720p30 tiles are comfortable on the Onn`() {
        assertEquals(Capacity.Comfortable, assess(onn, tiles(4, height = 720, fps = 30f)))
    }

    @Test
    fun `three 1080p50 tiles are tight on the Onn`() {
        assertEquals(Capacity.Tight(CapacityReason.DecodeThroughput), assess(onn, tiles(3)))
    }

    @Test
    fun `hevc has its own throughput`() {
        assertEquals(Capacity.Comfortable, assess(onn, tiles(4, codec = TileCodec.HEVC)))
    }

    @Test
    fun `mixed codecs are judged per decoder`() {
        val mixed = tiles(2) + tiles(2, codec = TileCodec.HEVC)
        assertEquals(Capacity.Comfortable, assess(onn, mixed))
    }

    // ---- the order of trust ----

    @Test
    fun `an observed ceiling beats a generous declaration`() {
        val facts = onn.copy(observedComfortable = mapOf(TileCodec.AVC to 1))
        assertEquals(Capacity.Tight(CapacityReason.ObservedCeiling), assess(facts, tiles(2, height = 480, fps = 25f)))
    }

    @Test
    fun `tiles within the observed ceiling are not tight on its account`() {
        val facts = DecoderFacts(avc = CodecFacts(), observedComfortable = mapOf(TileCodec.AVC to 2))
        assertEquals(Capacity.Comfortable, assess(facts, tiles(2)))
    }

    @Test
    fun `a declared count of 32 or more is unknown and one that is missing too`() {
        val unlimited = DecoderFacts(avc = CodecFacts(declaredInstances = 32))
        assertEquals(Capacity.Unknown, assess(unlimited, tiles(4)))
        assertEquals(Capacity.Unknown, assess(DecoderFacts(), tiles(4)))
        assertEquals(Capacity.Unknown, assess(DecoderFacts(avc = CodecFacts(declaredInstances = 64)), tiles(2)))
    }

    @Test
    fun `a real declared count is used when nothing was measured`() {
        val declared = DecoderFacts(avc = CodecFacts(declaredInstances = 2))
        assertEquals(Capacity.Comfortable, assess(declared, tiles(2)))
        assertEquals(Capacity.Tight(CapacityReason.DecodeThroughput), assess(declared, tiles(3)))
    }

    @Test
    fun `memory tightens a box whose decoder is fine`() {
        assertEquals(Capacity.Tight(CapacityReason.Memory), assess(onn, tiles(3, height = 480, fps = 25f), MemoryClass.LOW))
        assertEquals(Capacity.Tight(CapacityReason.Memory), assess(onn, tiles(4, height = 480, fps = 25f), MemoryClass.MID))
        assertEquals(Capacity.Comfortable, assess(onn, tiles(2, height = 480, fps = 25f), MemoryClass.LOW))
    }

    @Test
    fun `memory is a fact even when the decoder is unknown`() {
        assertEquals(Capacity.Tight(CapacityReason.Memory), assess(DecoderFacts(), tiles(3), MemoryClass.LOW))
    }

    @Test
    fun `no tiles is comfortable`() {
        assertEquals(Capacity.Comfortable, assess(DecoderFacts(), emptyList()))
    }

    @Test
    fun `an unknown tile is assumed to be 1080p50 AVC`() {
        assertEquals(TileCodec.AVC, TileLoad.ASSUMED.codec)
        assertEquals(1080, TileLoad.ASSUMED.height)
        assertEquals(50f, TileLoad.ASSUMED.frameRate, 0f)
        assertEquals("cost in 1080p frames per second", 50f, TileLoad.ASSUMED.cost, 0.001f)
    }

    // ---- the message ----

    @Test
    fun `the Onn plays about two comfortably at the assumed load`() {
        // Two 1080p50 streams are 100 of 134 fps, under the 90 percent line (120); three are 150, over it.
        val about = MultiviewCapacityPolicy.comfortableTiles(onn, MemoryClass.HIGH)
        assertEquals(2, about)
    }

    @Test
    fun `an unknown box has no number to give`() {
        assertNull(MultiviewCapacityPolicy.comfortableTiles(DecoderFacts(), MemoryClass.HIGH))
    }

    // ---- the ledger ----

    @Test
    fun `a decoder failure with others open lowers the ceiling to one fewer`() {
        assertEquals(2, CeilingLedgerPolicy.learned(TileFailure.DecoderUnavailable, openTiles = 3, existing = null))
        assertEquals(1, CeilingLedgerPolicy.learned(TileFailure.DecoderUnavailable, openTiles = 2, existing = 2))
    }

    @Test
    fun `the ceiling only goes down and only decoder failures count`() {
        assertNull("not lower", CeilingLedgerPolicy.learned(TileFailure.DecoderUnavailable, openTiles = 4, existing = 2))
        assertNull("one tile alone is no evidence", CeilingLedgerPolicy.learned(TileFailure.DecoderUnavailable, openTiles = 1, existing = null))
        assertNull("other failures", CeilingLedgerPolicy.learned(TileFailure.NoData, openTiles = 4, existing = null))
        assertTrue(TileFailurePolicy.countsTowardDecoderCeiling(TileFailure.DecoderUnavailable))
    }
}
