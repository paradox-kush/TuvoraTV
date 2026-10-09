package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.client.MediaServerClient
import com.nuvio.tv.core.mediaserver.client.PlaybackInfoRequest
import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaSourceDto
import com.nuvio.tv.core.mediaserver.policy.VersionPickPolicy

/**
 * The versions of a playable item, as a title page lists them. A server that prepares streams only when asked may answer
 * the item page with stand-ins ("Streams resolve on play" / "Load versions" - recorded on an add-on aggregator with
 * "resolve on open" off); its real list comes from PlaybackInfo, which prepares it. One extra request, and only for such
 * a server: an item whose page already carries versions (or none at all) is returned as it is.
 */
internal object MediaServerVersions {
    suspend fun of(client: MediaServerClient, item: ItemDto): List<MediaSourceDto> {
        val listed = item.mediaSources
        if (listed.isEmpty() || listed.any { !VersionPickPolicy.isPlaceholder(it.type) }) return listed
        val id = item.id ?: return listed
        return client.playbackInfo(id, PlaybackInfoRequest()).sources
    }
}
