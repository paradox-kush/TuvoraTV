package com.nuvio.tv.core.mediaserver

import com.nuvio.tv.core.mediaserver.api.MediaServerSyncBinding
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the ONE playlist-sync engine needs of the media-server entries (design 5.3: one engine owns the
 * `iptv_playlists` revision counter; media servers add a second row mapper, not a second loop). Injected into the
 * sync service so the service never names the runtime.
 */
@Singleton
class MediaServerSyncAccess @Inject internal constructor(
    private val runtime: MediaServerRuntime,
) {
    internal fun binding(): MediaServerSyncBinding {
        val store = runtime.entryStore
        return MediaServerSyncBinding(
            currentEntries = { store.current() },
            canPushFullReplace = { store.canPushFullReplace() },
            applyFromRemote = { profileId, remote -> store.applyFromRemote(profileId, remote) },
        )
    }
}
