package com.nuvio.tv.core.memory

import com.nuvio.tv.core.contracts.DemuxerBudgetBytes

/**
 * Pure device-tier -> player-buffer mappings. Kept pure (no Android, no player) so they stay unit-
 * testable, and here in core/memory (the tier's home) so player code reads them only via the
 * [com.nuvio.tv.core.contracts.PlayerMemoryBudget] port. Moved out of the player package by the
 * Part A budget port (was NuvioMpvSurfaceView.demuxerBytesFor / NuvioExoPlayerPerformanceHelper.
 * playerTargetBufferBytes).
 */

/** mpv demuxer cache per tier: LOW 48+16 MiB, MID/HIGH 64+32 MiB (mobile's proven forward:back). */
fun demuxerBytesFor(tier: MemoryTier): DemuxerBudgetBytes = when (tier) {
    MemoryTier.LOW -> DemuxerBudgetBytes(
        maxBytes = 48L * 1024L * 1024L,
        maxBackBytes = 16L * 1024L * 1024L,
    )
    MemoryTier.MID, MemoryTier.HIGH -> DemuxerBudgetBytes(
        maxBytes = 64L * 1024L * 1024L,
        maxBackBytes = 32L * 1024L * 1024L,
    )
}

/**
 * ExoPlayer stock-path target buffer. LOW keeps a flat 40MB (stopped 2GB-box swap-thrash);
 * everything else budgets a quarter of the real heap, clamped 40..100MB.
 */
fun exoTargetBufferBytesFor(tier: MemoryTier, maxHeapBytes: Long): Int {
    if (tier == MemoryTier.LOW) return 40 * 1024 * 1024
    return (maxHeapBytes / 4)
        .coerceIn(40L * 1024 * 1024, 100L * 1024 * 1024)
        .toInt()
}
