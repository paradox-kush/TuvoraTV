package com.nuvio.tv.core.player

import com.nuvio.tv.core.contracts.OwnSourcePolicy

/**
 * Whether upstream's AutoSync (sync 15, default ON) may range-read the STREAM url to index its
 * embedded subtitles ([com.nuvio.tv.ui.screens.player.autosync.EmbeddedSubtitleTimelineLoader]).
 *
 * Same reasoning as [SubtitleHashProbePolicy]: an IPTV link can be single-use (a Stalker
 * `play_token` is burned by the first read) or connection-counted (a one-connection Xtream panel
 * drops the player when the probe takes the slot), and every freshly re-minted link would be
 * probed again. So AutoSync only reads plain add-on/debrid streams; never:
 *  - an IPTV id ([iptvId]) or any own-source content id (IPTV `xtream:`/`m3u:`, media server);
 *  - a TMDB-matched IPTV copy ([isMatchedIptvSource]: it plays under a public `tt…` id);
 *  - a stream on a known IPTV panel / media-server host ([isPanelHost]);
 *  - catch-up or live playback; a non-http(s) url.
 */
object AutoSyncSourcePolicy {
    fun mayProbe(
        url: String?,
        iptvId: String?,
        contentId: String?,
        videoId: String?,
        contentType: String?,
        isCatchUp: Boolean,
        isMatchedIptvSource: Boolean,
        isPanelHost: (String) -> Boolean,
    ): Boolean {
        if (iptvId != null || isMatchedIptvSource) return false
        if (OwnSourcePolicy.isOwnContentId(contentId) || OwnSourcePolicy.isOwnContentId(videoId)) return false
        return SubtitleHashProbePolicy.mayProbe(
            url = url,
            contentId = contentId,
            videoId = videoId,
            isLiveContent = SubtitleHashProbePolicy.isLivePlayback(contentType, isCatchUp),
            isPanelHost = isPanelHost,
        )
    }
}
