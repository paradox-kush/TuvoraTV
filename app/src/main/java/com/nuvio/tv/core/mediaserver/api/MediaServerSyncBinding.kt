package com.nuvio.tv.core.mediaserver.api

/**
 * What the playlist-sync engine needs of the media-server entries (design 5.3: ONE engine owns the
 * `iptv_playlists` revision counter; media servers add a second row mapper, not a second loop).
 */
class MediaServerSyncBinding(
    /** The active profile's entries - what the user sees and what a push carries. */
    val currentEntries: () -> List<MediaServerEntry>,
    /** Whether the in-memory list may full-replace the server's media rows (a damaged/absent local store must not). */
    val canPushFullReplace: () -> Boolean,
    /** Applies the server's (reconciled) rows as the new local list WITHOUT echoing a push back. */
    val applyFromRemote: (profileId: Int, remote: List<MediaServerEntry>) -> Unit,
)
