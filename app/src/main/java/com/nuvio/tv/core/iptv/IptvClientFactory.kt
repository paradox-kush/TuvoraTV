package com.nuvio.tv.core.iptv

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a call site the right [IptvClient] for an account, dispatching on [XtreamAccount.sourceType]:
 *  - [XtreamAccount.SOURCE_XTREAM] -> [XtreamClient] (live `player_api.php`)
 *  - [XtreamAccount.SOURCE_URL]    -> [M3UClient] (pre-ingested M3U catalog in SQLite)
 *  - [XtreamAccount.SOURCE_FILE]   -> [M3UClient] (same catalog, ingested from a local file copy)
 *
 * Every IPTV consumer (hub, live guide, search, meta/stream short-circuits) resolves its client
 * through here so the browse/play pipeline stays source-agnostic — the registry ids, native detail,
 * and direct-stream playback are identical regardless of source. Unknown/future source types fall
 * back to Xtream (the only fully-wired source before this).
 */
@Singleton
class IptvClientFactory @Inject constructor(
    private val xtreamClient: XtreamClient,
    private val m3uClient: M3UClient,
) {
    fun clientFor(account: XtreamAccount): IptvClient = when (account.sourceType) {
        XtreamAccount.SOURCE_URL, XtreamAccount.SOURCE_FILE -> m3uClient
        else -> xtreamClient
    }

    /** The M3U client, for the ingest trigger on add/edit (which is M3U-specific). */
    fun m3u(): M3UClient = m3uClient
}
