package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.refresh.IptvRefreshStore
import com.nuvio.tv.core.iptv.content.M3UFileStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drops everything CACHED under a playlist id when the playlist goes away — explicit
 * user delete or a remote sync that no longer lists it. Match index rows, ingested M3U
 * catalog + EPG, session caches, refresh timestamps, the local file copy. Saved USER
 * data (library, watch progress, live favorites/recents) is deliberately NOT touched
 * here: caches rebuild for free, user data doesn't — the explicit-delete path drops it
 * separately, a possibly-transient sync must never.
 */
@Singleton
class IptvAccountPurge @Inject constructor(
    private val registry: XtreamItemRegistry,
    private val searchIndex: XtreamSearchIndex,
    private val matchIndex: XtreamMatchIndex,
    private val contentDb: IptvContentDb,
    private val refreshStore: IptvRefreshStore,
    private val fileStore: M3UFileStore,
    private val epgMirrorDb: com.nuvio.tv.core.epg.EpgMirrorDb,
    private val stalkerClient: com.nuvio.tv.core.iptv.stalker.StalkerClient,
) {
    suspend fun purgeCaches(accountId: String) {
        registry.clear() // global in-memory map; rebuilds lazily on next browse
        searchIndex.evict(accountId)
        runCatching { matchIndex.purge(accountId) }
        runCatching { contentDb.purge(accountId) }
        runCatching { refreshStore.clear(accountId) }
        runCatching { fileStore.delete(accountId) }
        runCatching { epgMirrorDb.purgeProvider(accountId) }
        runCatching { stalkerClient.evictCaches(accountId) }   // cached lineup + create_link cmds
    }
}
