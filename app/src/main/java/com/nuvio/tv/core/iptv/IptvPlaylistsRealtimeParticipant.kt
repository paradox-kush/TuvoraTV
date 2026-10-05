package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.RealtimeSyncParticipant
import javax.inject.Inject
import javax.inject.Singleton

/** B03: a profile's playlist set changed elsewhere (web editor, provider, another device) -> pull it. */
@Singleton
class IptvPlaylistsRealtimeParticipant @Inject constructor(
    private val syncService: dagger.Lazy<com.nuvio.tv.core.sync.XtreamAccountSyncService>,
) : RealtimeSyncParticipant {
    override val name: String = "IPTV playlists"
    override val realtimeSurfaces: Set<String> = setOf("iptv_playlists")
    override suspend fun pullForRealtimeSurface(profileId: Int) {
        syncService.get().pullAndApply().getOrThrow()
    }
}
