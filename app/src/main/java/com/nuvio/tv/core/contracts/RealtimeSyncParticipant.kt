package com.nuvio.tv.core.contracts

/**
 * A fork feature that pulls on a Realtime `sync_invalidations.surface` the shared StartupSyncService
 * doesn't know by name (seam: sync firewall). Implementations are bound into a Hilt set at the
 * composition root (core/di), so upstream-aligned sync code never names the fork feature. Mirrors
 * Mobile's `SyncParticipant.realtimeSurfaces`.
 */
interface RealtimeSyncParticipant {
    val name: String
    val realtimeSurfaces: Set<String>
    suspend fun pullForRealtimeSurface(profileId: Int)
}

object RealtimeSyncRouting {
    /** The participants that pull on Realtime [surface] (pure). */
    fun participantsFor(
        surface: String,
        participants: Collection<RealtimeSyncParticipant>,
    ): List<RealtimeSyncParticipant> = participants.filter { surface in it.realtimeSurfaces }
}
