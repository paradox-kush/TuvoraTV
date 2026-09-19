package com.nuvio.tv.core.sync

/**
 * Pure decisions for the watch-sync "push point" — the read time of the last full push, used to
 * tell which local entries have not yet reached remote and so must survive a pull that omits them.
 *
 * Extracted so the two invariants the sync-point fixes hinge on are unit-testable without the
 * services, the network, or DataStore. See WatchProgressSyncService / WatchedItemsSyncService.
 */
internal object WatchSyncPoint {

    /**
     * Advance a push point, never lowering it. A push that read older data can still finish last;
     * its stamp must not retract a newer push's claim. Applied both in memory and inside the
     * durable store's edit so out-of-order writes cannot leave the older point on disk.
     */
    fun advance(current: Long, candidate: Long): Long = maxOf(current, candidate)

    /**
     * Whether a local entry is protected from remote-absence deletion: true when it was last
     * touched strictly after the push point. There is deliberately NO `point > 0` gate — a device
     * that has never pushed (point 0) has nothing on remote, so a pull returning none of its
     * entries proves nothing, and every real entry (touched at > 0) is protected.
     */
    fun preservesLocal(localLastTouchedMs: Long, syncPointMs: Long): Boolean =
        localLastTouchedMs > syncPointMs
}
