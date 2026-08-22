package com.nuvio.tv.ui.screens.iptv.player

import com.nuvio.tv.core.contracts.CatchUpDialectRetry
import com.nuvio.tv.core.contracts.CatchUpPlaybackPort
import com.nuvio.tv.core.contracts.IptvContentClassifier
import com.nuvio.tv.core.contracts.LiveChannelNavigator
import com.nuvio.tv.core.contracts.LiveChannelTarget
import com.nuvio.tv.core.contracts.LivePlayback
import com.nuvio.tv.core.contracts.PlaybackDnsPort
import com.nuvio.tv.core.contracts.PreparedLive
import com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.iptv.dns.PlaylistDnsResolver
import okhttp3.Dns
import javax.inject.Inject

/**
 * The fork's implementation of the neutral [LivePlayback] ports: thin adapters over the existing
 * IPTV services. This file lives on a fork path (ui/screens/iptv), so it may name core.iptv freely;
 * the controller only ever sees the neutral contract. See research/tv-player-mpv-engine-ownership.md
 * Part B — B1 wires the seam without moving logic (the services below are unchanged).
 */
class IptvLivePlayback @Inject constructor(
    private val dnsResolver: PlaylistDnsResolver,
    private val catchUpCoordinator: CatchUpPlaybackCoordinator,
    private val livePlaylist: XtreamLivePlaylist,
) : LivePlayback {

    override val classifier: IptvContentClassifier = object : IptvContentClassifier {
        override fun isLiveId(id: String): Boolean = XtreamItemRegistry.isLiveContentId(id)
        override fun refreshableIptvId(candidates: List<String?>): String? =
            candidates.filterNotNull().firstOrNull { it.startsWith(XtreamItemRegistry.PREFIX) }
    }

    override val dns: PlaybackDnsPort = object : PlaybackDnsPort {
        override fun prepareLive(contentId: String?, rawUrl: String): PreparedLive {
            val prepared = dnsResolver.prepareLive(contentId, rawUrl)
            return PreparedLive(url = prepared.url, headers = prepared.headers)
        }
        override fun dnsForVideoId(videoId: String?): Dns? = dnsResolver.dnsForVideoId(videoId)
    }

    override val catchUp: CatchUpPlaybackPort = object : CatchUpPlaybackPort {
        override fun onPlayed(contentId: String?) = catchUpCoordinator.onPlayed(contentId)
        override fun onFailed(contentId: String?, errorCode: Int): CatchUpDialectRetry? =
            catchUpCoordinator.onFailed(contentId, errorCode)
                ?.let { CatchUpDialectRetry(channelName = it.channelName, url = it.url) }
    }

    override val channels: LiveChannelNavigator = object : LiveChannelNavigator {
        override fun relativeChannel(contentId: String, delta: Int): LiveChannelTarget? =
            livePlaylist.relativeTo(contentId, delta)
                ?.let { LiveChannelTarget(id = it.id, name = it.name, streamUrl = it.streamUrl) }
    }
}
