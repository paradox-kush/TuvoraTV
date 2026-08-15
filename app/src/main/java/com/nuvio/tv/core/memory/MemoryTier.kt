package com.nuvio.tv.core.memory

/**
 * The app-wide memory tier (hand-ported twin of the mobile/desktop
 * com.nuvio.app.core.memory.MemoryTier). TV probes itself with ActivityManager and every
 * budget — image caches, ingest batches, player buffers — is sized from the tier.
 */
enum class MemoryTier {
    LOW, MID, HIGH;

    /** One tier lower (transient pressure escalation). LOW has no lower rung. */
    fun escalated(): MemoryTier = when (this) {
        LOW -> LOW
        MID -> LOW
        HIGH -> MID
    }
}
