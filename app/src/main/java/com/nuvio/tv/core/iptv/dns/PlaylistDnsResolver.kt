package com.nuvio.tv.core.iptv.dns

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.data.local.XtreamAccountStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.Dns
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges an IPTV content id (or accountId) to its playlist's DNS choice for the **playback** layer,
 * which — unlike the hub/search/client layers — doesn't carry an [XtreamAccount] to the point where
 * the media source is built.
 *
 * It keeps an in-memory `accountId -> dnsProvider` mirror warmed from [XtreamAccountStore] (the same
 * pattern [com.nuvio.tv.data.local.XtreamLiveStore] uses for its sync in-memory mirror), so the
 * player can look up a provider synchronously off the videoId's accountId. Everything degrades to the
 * system resolver when the account isn't known yet — DNS selection never blocks or breaks playback.
 */
@Singleton
class PlaylistDnsResolver @Inject constructor(
    private val playlistDns: PlaylistDns,
    private val livePlayback: PlaylistLivePlayback,
    accountStore: XtreamAccountStore,
) {
    private val providerByAccountId = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        accountStore.accounts
            .onEach { accounts ->
                providerByAccountId.clear()
                accounts.forEach { providerByAccountId[it.id] = it.dnsProvider }
            }
            .launchIn(scope)
    }

    /** The DoH provider for the playlist owning [videoId], or null when it's not an xtream id / unknown. */
    fun providerForVideoId(videoId: String?): String? {
        if (videoId == null || !videoId.startsWith(XtreamItemRegistry.PREFIX)) return null
        val accountId = XtreamItemRegistry.parseId(videoId)?.accountId ?: return null
        return providerByAccountId[accountId]
    }

    /** The [Dns] the Media3 playback client should use for [videoId], or null to keep the default client. */
    fun dnsForVideoId(videoId: String?): Dns? {
        val provider = providerForVideoId(videoId) ?: return null
        if (!playlistDns.usesDoh(provider)) return null
        return playlistDns.dnsFor(provider)
    }

    /**
     * Prepares an mpv LIVE stream ([rawUrl]) for the playlist owning [contentId]: DoH-resolves +
     * follows redirects + rewrites http URLs to a resolved IP with a `Host` header (see
     * [PlaylistLivePlayback]). May perform network I/O — call off-main. Returns the original URL with
     * no headers when the id isn't an IPTV live id, the playlist uses system DNS, or anything fails.
     */
    fun prepareLive(contentId: String?, rawUrl: String): PreparedLiveStream =
        livePlayback.prepare(providerForVideoId(contentId), rawUrl)
}
