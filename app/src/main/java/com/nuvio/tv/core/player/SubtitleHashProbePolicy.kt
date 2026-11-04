package com.nuvio.tv.core.player

import java.net.URI

/**
 * Whether the player may range-read a stream URL to compute the OpenSubtitles file hash.
 *
 * The hash needs two reads of the media (first and last 64 KiB). A provider-scoped stream
 * (IPTV / Stalker / media-server ids, or a host that is one of the user's IPTV panels) can be a
 * single-use or connection-counted link: probing it burns the link or a connection slot, and is
 * a request to the provider the user never asked for. Live feeds have no stable file to hash.
 * Local torrent streams are served from loopback and are fine to read.
 */
object SubtitleHashProbePolicy {
    fun mayProbe(
        url: String?,
        contentId: String?,
        videoId: String?,
        isLiveContent: Boolean,
        isPanelHost: (String) -> Boolean
    ): Boolean {
        if (url.isNullOrBlank() || isLiveContent) return false
        if (AddonSubtitleIdPolicy.isProviderScoped(contentId) || AddonSubtitleIdPolicy.isProviderScoped(videoId)) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return !isPanelHost(host)
    }
}
