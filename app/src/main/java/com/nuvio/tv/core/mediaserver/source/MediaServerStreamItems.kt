package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserUrls
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Subtitle

/**
 * The one shape of a media-server stream in a source list - used by the direct lane (`ms:` ids) and the matched
 * lane (`ms-match:` groups): a DEFERRED url (minted at pick time, never holding a token or a playable URL), the
 * sidecar text subtitles, and the per-product request header (Emby authenticates a player request by header;
 * Jellyfin's direct stream needs none - design 5.5).
 */
internal object MediaServerStreamItems {
    fun build(
        entry: MediaServerEntry,
        itemId: String,
        title: String?,
        source: MediaServerItemRegistry.Source?,
        groupId: String,
        headers: Map<String, String>?,
    ): Stream = Stream(
        name = source?.label ?: "Direct play",
        title = title,
        description = source?.description,
        url = MediaServerIds.deferredUrl(entry.serverKey, itemId, source?.id?.takeIf { it.isNotBlank() }),
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = headers?.let { ProxyHeaders(request = it, response = null) },
        ),
        addonName = entry.name,
        addonLogo = null,
        // Sidecar text subtitles ride with the stream; the engines list the container's own tracks by themselves.
        subtitles = source?.let { src ->
            src.subtitles.map { sub ->
                Subtitle(
                    id = "$itemId:${src.id}:${sub.index}",
                    url = MediaBrowserUrls.subtitle(entry.address.orEmpty(), itemId, src.id, sub.index),
                    lang = sub.language,
                    addonName = entry.name,
                    addonLogo = null,
                    isStreamProvided = true,
                    headers = headers,
                )
            }
        }.orEmpty(),
        sourceId = groupId,
    )
}
