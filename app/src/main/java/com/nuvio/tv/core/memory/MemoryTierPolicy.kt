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

    /**
     * How many catalog rows one index-write transaction may hold — LOW 100 / MID 300 / HIGH 500.
     *
     * The hub reads its movie/series rows from the SAME index database the build writes, so the
     * batch size is really "how long the UI can be blocked". Measured on a 2 GB Onn 4K box: at
     * 5,000 rows (~25,000 statements per transaction, one INSERT per normalized key on top of each
     * row) the reads queued behind the writer long enough that categories looked empty and the
     * whole app felt broken until the build finished.
     *
     * Smaller batches cut the lock hold AND the heap peak: the old 5,000 was picked against
     * "materialize the whole catalog", never against a few hundred. Numbers are StreamVault's
     * (CatalogSyncRuntimeProfile), whose tier cuts [androidTier] already matches.
     */
    fun indexBatchSize(tier: MemoryTier): Int = when (tier) {
        MemoryTier.LOW -> 100
        MemoryTier.MID -> 300
        MemoryTier.HIGH -> 500
    }

    /** Image memory cache budget per tier (Coil): LOW 32 / MID 64 / HIGH 96 MiB. */
    fun imageMemoryCacheBytes(tier: MemoryTier): Long = when (tier) {
        MemoryTier.LOW -> 32L * MIB
        MemoryTier.MID -> 64L * MIB
        MemoryTier.HIGH -> 96L * MIB
    }

    private const val MIB = 1024L * 1024
}
