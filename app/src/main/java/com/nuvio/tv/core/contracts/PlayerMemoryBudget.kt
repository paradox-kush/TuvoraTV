package com.nuvio.tv.core.contracts

/** mpv demuxer cache sizing (forward + seek-back windows), in bytes. */
data class DemuxerBudgetBytes(val maxBytes: Long, val maxBackBytes: Long)

/**
 * Neutral device-memory budget for the player, so player code never names core.memory directly.
 * The implementation ([com.nuvio.tv.core.memory.AndroidPlayerMemoryBudget]) resolves the device
 * tier; consumers just read the numbers. See research/tv-player-mpv-engine-ownership.md (Part A,
 * budget port / D6).
 */
interface PlayerMemoryBudget {
    /** mpv `demuxer-max-bytes` / `demuxer-max-back-bytes` for this device. */
    fun demuxerBytes(): DemuxerBudgetBytes

    /** ExoPlayer stock-path target buffer, given the app's max heap. */
    fun exoTargetBufferBytes(maxHeapBytes: Long): Int

    /** True on the low memory tier (smaller buffers to avoid swap-thrash on ≤2GB boxes). */
    fun isLowMemoryTier(): Boolean
}
