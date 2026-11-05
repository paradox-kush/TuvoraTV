package com.nuvio.tv.core.livepause

import kotlin.math.roundToLong

enum class LivePauseMechanism { PROVIDER_CATCHUP, DISK_BUFFER, ENGINE_BACK_BUFFER, UNAVAILABLE }

data class LivePauseChannel(val isLive: Boolean, val isCatchUpPlayback: Boolean, val hasProviderArchive: Boolean)

data class LivePauseCapabilities(val diskBuffer: Boolean, val engineBackBuffer: Boolean)

/**
 * Every live pause / rewind decision that needs no I/O: which mechanism a channel gets, how big a disk
 * buffer may be, whether the next piece may be written, what to trim, how to size mpv's cache, which
 * HLS variant to capture. Pure, so it is tested without the player or the disk. The TV twin of
 * NuvioMobile's `features/livetv/pause/LivePausePolicy.kt` — same names, same semantics.
 */
object LivePausePolicy {
    const val DEFAULT_KEEP_MS = 10 * 60_000L
    /** Below this a buffer is not worth starting. */
    const val MIN_KEEP_MS = 60_000L
    /** Never eat the last 300 MB of the device. */
    const val FREE_SPACE_RESERVE_BYTES = 300L * 1024 * 1024
    /** Unknown bitrate: assume HD, conservative. */
    const val FALLBACK_BITRATE_BPS = 8_000_000L
    /** TS/HLS overhead over the elementary bitrate. */
    const val CONTAINER_OVERHEAD = 1.10
    /** First playable piece or Failed. */
    const val BUFFER_START_DEADLINE_MS = 20_000L

    /** Not live, or a catch-up replay (which already seeks normally) → UNAVAILABLE. */
    fun mechanism(channel: LivePauseChannel, caps: LivePauseCapabilities): LivePauseMechanism = when {
        !channel.isLive || channel.isCatchUpPlayback -> LivePauseMechanism.UNAVAILABLE
        channel.hasProviderArchive -> LivePauseMechanism.PROVIDER_CATCHUP
        caps.diskBuffer -> LivePauseMechanism.DISK_BUFFER
        caps.engineBackBuffer -> LivePauseMechanism.ENGINE_BACK_BUFFER
        else -> LivePauseMechanism.UNAVAILABLE
    }

    private fun bytesPerSecond(bitrateBps: Long?): Double =
        (bitrateBps?.takeIf { it > 0 } ?: FALLBACK_BITRATE_BPS) / 8.0 * CONTAINER_OVERHEAD

    fun bytesFor(durationMs: Long, bitrateBps: Long?): Long =
        (bytesPerSecond(bitrateBps) * durationMs.coerceAtLeast(0) / 1000.0).roundToLong().coerceAtLeast(0)

    fun durationFor(bytes: Long, bitrateBps: Long?): Long =
        (bytes.coerceAtLeast(0) / bytesPerSecond(bitrateBps) * 1000.0).roundToLong().coerceAtLeast(0)

    sealed interface StartDecision {
        data class Start(val keepMs: Long, val budgetBytes: Long) : StartDecision
        data class Refuse(val reason: RefuseReason) : StartDecision
    }

    enum class RefuseReason { NOT_LIVE, UNSUPPORTED, NO_SPACE }

    /** DISK_BUFFER start: shrink keepMs to what fits in (freeBytes - reserve); under [MIN_KEEP_MS] → NO_SPACE. */
    fun diskStart(freeBytes: Long, bitrateBps: Long?, requestedKeepMs: Long = DEFAULT_KEEP_MS): StartDecision {
        val usable = freeBytes - FREE_SPACE_RESERVE_BYTES
        if (usable <= 0) return StartDecision.Refuse(RefuseReason.NO_SPACE)
        val keepMs = minOf(requestedKeepMs, durationFor(usable, bitrateBps))
        if (keepMs < MIN_KEEP_MS) return StartDecision.Refuse(RefuseReason.NO_SPACE)
        return StartDecision.Start(keepMs, bytesFor(keepMs, bitrateBps))
    }

    enum class SpaceVerdict { WRITE, TRIM_OLDEST, STOP_NO_SPACE }

    /** Checked before EVERY piece / segment write, on every path. */
    fun beforeWrite(freeBytes: Long, nextPieceBytes: Long, bufferedBytes: Long, budgetBytes: Long): SpaceVerdict = when {
        freeBytes - nextPieceBytes < FREE_SPACE_RESERVE_BYTES ->
            if (bufferedBytes > 0) SpaceVerdict.TRIM_OLDEST else SpaceVerdict.STOP_NO_SPACE
        // Nothing buffered means nothing to trim: a lone oversize piece is written rather than looping.
        bufferedBytes > 0 && bufferedBytes + nextPieceBytes > budgetBytes -> SpaceVerdict.TRIM_OLDEST
        else -> SpaceVerdict.WRITE
    }

    /** How many pieces to drop from the front so the rest is within [keepMs]; always keeps [minPieces]. */
    fun piecesToDrop(pieceDurationsMs: List<Long>, keepMs: Long, minPieces: Int = 3): Int {
        var total = pieceDurationsMs.sum()
        var drop = 0
        while (pieceDurationsMs.size - drop > minPieces && total > keepMs) {
            total -= pieceDurationsMs[drop]
            drop++
        }
        return drop
    }

    data class EngineCachePlan(val maxBytes: Long, val maxBackBytes: Long, val cacheSecs: Long)

    private fun backBytes(memoryBudgetBytes: Long, bitrateBps: Long?, keepMs: Long): Long =
        minOf(memoryBudgetBytes / 2, bytesFor(keepMs, bitrateBps))

    /** While watching live (rewind): the back buffer grows, the forward cache stays normal. */
    fun engineLivePlan(
        memoryBudgetBytes: Long,
        normal: EngineCachePlan,
        bitrateBps: Long?,
        keepMs: Long = DEFAULT_KEEP_MS,
    ): EngineCachePlan = EngineCachePlan(
        maxBytes = normal.maxBytes,
        maxBackBytes = backBytes(memoryBudgetBytes, bitrateBps, keepMs),
        cacheSecs = normal.cacheSecs,
    )

    /** While paused: the rest of the budget goes forward (never below normal) so the demuxer keeps reading. */
    fun enginePausedPlan(
        memoryBudgetBytes: Long,
        normal: EngineCachePlan,
        bitrateBps: Long?,
        keepMs: Long = DEFAULT_KEEP_MS,
    ): EngineCachePlan {
        val back = backBytes(memoryBudgetBytes, bitrateBps, keepMs)
        val forward = maxOf(memoryBudgetBytes - back, normal.maxBytes)
        return EngineCachePlan(
            maxBytes = forward,
            maxBackBytes = back,
            cacheSecs = maxOf(durationFor(forward, bitrateBps) / 1000, normal.cacheSecs),
        )
    }

    enum class GoLiveAction { SEEK_TO_EDGE, RETUNE }

    /** DISK_BUFFER and PROVIDER_CATCHUP retune (a normal channel open re-mints single-use links). */
    fun goLive(mechanism: LivePauseMechanism, captureHealthy: Boolean): GoLiveAction = when (mechanism) {
        LivePauseMechanism.ENGINE_BACK_BUFFER -> if (captureHealthy) GoLiveAction.SEEK_TO_EDGE else GoLiveAction.RETUNE
        else -> GoLiveAction.RETUNE
    }

    data class HlsVariant(val uri: String, val bandwidthBps: Long, val height: Int?)

    fun maxCaptureBitrateBps(lowMemoryTier: Boolean): Long = if (lowMemoryTier) 6_000_000L else 15_000_000L

    /** Highest bandwidth at or under the cap; none fits → the lowest; empty → null. */
    fun pickVariant(variants: List<HlsVariant>, maxBitrateBps: Long): HlsVariant? {
        if (variants.isEmpty()) return null
        return variants.filter { it.bandwidthBps <= maxBitrateBps }.maxByOrNull { it.bandwidthBps }
            ?: variants.minByOrNull { it.bandwidthBps }
    }
}
