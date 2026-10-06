package com.nuvio.tv.core.mediaserver.api

/**
 * How a LOCAL change to a media-server entry reaches the one playlist-sync engine (design 5.3: one engine,
 * one revision counter). The entry store records the user's intent here; the engine reconciles it onto the
 * server's rows on its next sync and acknowledges it once the commit lands. Implemented next to the engine
 * (the playlist-sync state is its own); registered at the composition root. With no sink (tests, a
 * signed-out/anonymous profile) entries are simply device-local.
 */
interface MediaServerSyncSink {
    fun recordAdd(profileId: Int, entry: MediaServerEntry)

    /** [base] is the entry as it was before this edit (the field-level merge's reference). */
    fun recordUpdate(profileId: Int, entry: MediaServerEntry, base: MediaServerEntry?)

    fun recordDelete(profileId: Int, key: String)
}
