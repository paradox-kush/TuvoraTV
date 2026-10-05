package com.nuvio.tv.core.radar

import com.nuvio.tv.core.contracts.RealtimeSyncParticipant
import javax.inject.Inject
import javax.inject.Singleton

/** B03: followed leagues / teams changed elsewhere (web Sports tab, another device) -> pull them. */
@Singleton
class RadarRealtimeParticipant @Inject constructor(
    private val syncService: dagger.Lazy<RadarSyncService>,
) : RealtimeSyncParticipant {
    override val name: String = "Radar follows"
    override val realtimeSurfaces: Set<String> = setOf("radar")
    override suspend fun pullForRealtimeSurface(profileId: Int) {
        syncService.get().pullAndApply().getOrThrow()
    }
}
