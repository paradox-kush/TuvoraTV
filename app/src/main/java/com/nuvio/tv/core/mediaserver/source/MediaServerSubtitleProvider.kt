package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.OwnSourceSubtitleProvider
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDialect
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserUrls
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import com.nuvio.tv.domain.model.Subtitle

/**
 * A server item's own sidecar text subtitles (the `.srt` / `.ass` beside the file; the containers' embedded tracks
 * are listed by the engines themselves). Third-party subtitle add-ons are never asked about an `ms:` id - this is how
 * its subtitles still reach the player. The version the viewer picked (the media source minted for this play) wins;
 * otherwise the item's first.
 */
internal class MediaServerSubtitleProvider(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
) : OwnSourceSubtitleProvider {
    override val name: String = MediaServerSourceRegistrations.NAME

    override fun handles(videoId: String): Boolean = MediaServerIds.isContentId(videoId)

    override suspend fun subtitles(videoId: String): List<Subtitle> {
        val item = MediaServerItemRegistry.get(videoId) ?: return emptyList()
        val entry = store.entryByServerKey(item.serverKey) ?: return emptyList()
        val minted = MediaServerPlaybackSessions.forItem(item.serverKey, item.itemId)
        val source = item.sources.firstOrNull { it.id == minted?.mediaSourceId } ?: item.sources.firstOrNull() ?: return emptyList()
        val headers = authHeaders(entry, services)
        return source.subtitles.map { sub ->
            Subtitle(
                id = "${item.itemId}:${source.id}:${sub.index}",
                url = MediaBrowserUrls.subtitle(entry.address.orEmpty(), item.itemId, source.id, sub.index),
                lang = sub.language,
                addonName = entry.name,
                addonLogo = null,
                isStreamProvided = true,
                headers = headers,
            )
        }
    }
}

/** The header an Emby request needs for the token (Jellyfin's direct-play URLs and subtitles need none); null when none applies. */
internal fun authHeaders(entry: MediaServerEntry, services: MediaServerServices): Map<String, String>? {
    val header = MediaBrowserDialect.of(entry.type).extraTokenHeader ?: return null
    val token = services.credentials.token(entry.serverKey) ?: return null
    return mapOf(header to token)
}
