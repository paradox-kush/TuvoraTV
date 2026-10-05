package com.nuvio.tv.core.iptv.overlay

import com.nuvio.tv.core.contracts.RealtimeSyncParticipant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B115: the website editor (and other devices) push overlay edits through `sync_push_iptv_overlay`,
 * which emits the `iptv_overlay` Realtime surface. Before this the TV dropped it as an unknown surface
 * and the edit only arrived when the guide was reopened. The pull is the existing delta (cursor) pull.
 */
@Singleton
class IptvOverlayRealtimeParticipant @Inject constructor(
    private val repository: IptvOverlayRepository,
) : RealtimeSyncParticipant {
    override val name: String = "IPTV overlay"
    override val realtimeSurfaces: Set<String> = setOf("iptv_overlay")
    override suspend fun pullForRealtimeSurface(profileId: Int) {
        repository.pullForProfile(profileId)
    }
}
