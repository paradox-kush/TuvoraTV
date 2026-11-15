package com.nuvio.tv.core.multiview

/** The codec families a tile decodes with. Anything else is counted as [AVC], the common live case. */
internal enum class TileCodec { AVC, HEVC }

/** What one tile costs to decode. Before its first frame the cost is [ASSUMED]: 1080p50 AVC. */
internal data class TileLoad(
    val codec: TileCodec = TileCodec.AVC,
    val height: Int = 1080,
    val frameRate: Float = 50f,
) {
    /** Cost in "1080p frames per second", the unit decoder throughput is measured in. */
    val cost: Float get() = (height.coerceAtLeast(1).toFloat() / 1080f).let { it * it } * frameRate.coerceAtLeast(1f)

    companion object {
        val ASSUMED = TileLoad()
    }
}

/**
 * What a decoder family says about itself. [declaredInstances] is the MediaCodec's own number (32 or more, or null,
 * means it is not telling). [throughputFps1080] is the measured rate for 1080p, the block's total throughput.
 */
internal data class CodecFacts(
    val declaredInstances: Int? = null,
    val throughputFps1080: Float? = null,
)

/**
 * [observedComfortable] is the ledger: per codec, the most tiles this device has been seen to open at once
 * (a tile failed for lack of a decoder while N were open: comfortable is N-1).
 */
internal data class DecoderFacts(
    val avc: CodecFacts? = null,
    val hevc: CodecFacts? = null,
    val observedComfortable: Map<TileCodec, Int> = emptyMap(),
) {
    fun of(codec: TileCodec): CodecFacts? = if (codec == TileCodec.AVC) avc else hevc
}

/** A device-neutral memory class (the platform maps its own tier onto it). */
internal enum class MemoryClass { LOW, MID, HIGH }

internal enum class CapacityReason { ObservedCeiling, DecodeThroughput, Memory }

internal sealed interface Capacity {
    data object Comfortable : Capacity
    data class Tight(val reason: CapacityReason) : Capacity
    data object Unknown : Capacity
}

/**
 * Whether this device can run these tiles at full quality. It is a message and nothing else: the result can say
 * "tight" but there is no outcome that refuses a tile, and nothing here counts against a provider account.
 *
 * Inputs, in order of trust: (1) the observed ceiling, (2) measured decode throughput against the tiles' summed
 * cost, (3) the declared instance count (32 or more, or missing, is unknown), (4) the memory class.
 */
internal object MultiviewCapacityPolicy {
    /** A decoder running above this share of its measured throughput is tight. */
    const val THROUGHPUT_HEADROOM = 0.9f

    /** Tiles the box has the memory for at full quality. HIGH has no memory limit of its own. */
    fun memoryCeiling(tier: MemoryClass): Int = when (tier) {
        MemoryClass.LOW -> 2
        MemoryClass.MID -> 3
        MemoryClass.HIGH -> MultiviewLayoutPolicy.MAX_TILES
    }

    /** Declared instance counts at or above this are "unlimited" in practice, not a fact. */
    const val DECLARED_UNLIMITED = 32

    fun assess(facts: DecoderFacts, tiles: List<TileLoad>, tier: MemoryClass): Capacity {
        if (tiles.isEmpty()) return Capacity.Comfortable
        val byCodec = tiles.groupBy { it.codec }

        // 1. The ledger: this device has failed to open this many at once.
        if (byCodec.any { (codec, list) -> facts.observedComfortable[codec]?.let { list.size > it } == true }) {
            return Capacity.Tight(CapacityReason.ObservedCeiling)
        }
        // 2. Measured throughput, and the declared count where it is a real number.
        var informed = true
        for ((codec, list) in byCodec) {
            val codecFacts = facts.of(codec)
            val throughput = codecFacts?.throughputFps1080
            val declared = codecFacts?.declaredInstances?.takeIf { it in 1 until DECLARED_UNLIMITED }
            if (throughput != null && list.sumOf { it.cost.toDouble() } > throughput * THROUGHPUT_HEADROOM) {
                return Capacity.Tight(CapacityReason.DecodeThroughput)
            }
            if (declared != null && list.size > declared) return Capacity.Tight(CapacityReason.DecodeThroughput)
            if (throughput == null && declared == null && facts.observedComfortable[codec] == null) informed = false
        }
        // 4. Memory is a fact about the box whatever the decoder says.
        if (tiles.size > memoryCeiling(tier)) return Capacity.Tight(CapacityReason.Memory)
        return if (informed) Capacity.Comfortable else Capacity.Unknown
    }

    /** About how many tiles at the assumed load this box plays comfortably: 1..4, or null when [assess] cannot tell. */
    fun comfortableTiles(facts: DecoderFacts, tier: MemoryClass, sample: TileLoad = TileLoad.ASSUMED): Int? {
        if (assess(facts, listOf(sample), tier) == Capacity.Unknown) return null
        var best = 1
        for (n in 2..MultiviewLayoutPolicy.MAX_TILES) {
            if (assess(facts, List(n) { sample }, tier) == Capacity.Comfortable) best = n else break
        }
        return best
    }
}

/** Source of the facts a platform can read (the Android one reads MediaCodecList). Read once per session, off the main thread. */
internal fun interface DecoderFactsPort {
    suspend fun facts(): DecoderFacts
}

/** The ledger's persistence: per device model and codec, on this device only. */
internal interface DecoderCeilingStore {
    suspend fun load(): Map<TileCodec, Int>
    suspend fun save(codec: TileCodec, comfortable: Int)
}

internal object CeilingLedgerPolicy {
    /**
     * What a [TileFailure.DecoderUnavailable] with [openTiles] open teaches: comfortable is one fewer, and a ceiling
     * only ever goes down. Null when it teaches nothing (one tile alone failing is no evidence of a concurrency limit).
     */
    fun learned(failure: TileFailure, openTiles: Int, existing: Int?): Int? {
        if (!TileFailurePolicy.countsTowardDecoderCeiling(failure) || openTiles < 2) return null
        val next = openTiles - 1
        return if (existing == null || next < existing) next else null
    }
}
