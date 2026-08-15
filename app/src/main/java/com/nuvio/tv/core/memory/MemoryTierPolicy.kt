package com.nuvio.tv.core.memory

/**
 * Pure tier decisions — the probe feeds OS facts in, the tier comes out. Twin of the
 * mobile/desktop policy, minus the iOS/desktop rules TV can never run: this codebase
 * only ships to Android TV, so only the Android rule and the shared budgets live here.
 */
internal object MemoryTierPolicy {

    /**
     * Android: the OS's own words — isLowRamDevice / memoryClass (MB). memoryClass stays
     * the floor: budget TV boxes declare no Media Performance Class (they read 0), so MPC
     * can only ever upgrade phones, never gate boxes.
     */
    fun androidTier(isLowRamDevice: Boolean, memoryClassMb: Int): MemoryTier = when {
        isLowRamDevice || memoryClassMb <= 192 -> MemoryTier.LOW
        memoryClassMb <= 320 -> MemoryTier.MID
        else -> MemoryTier.HIGH
    }

    /** Image memory cache budget per tier (Coil): LOW 32 / MID 64 / HIGH 96 MiB. */
    fun imageMemoryCacheBytes(tier: MemoryTier): Long = when (tier) {
        MemoryTier.LOW -> 32L * MIB
        MemoryTier.MID -> 64L * MIB
        MemoryTier.HIGH -> 96L * MIB
    }

    private const val MIB = 1024L * 1024
}
