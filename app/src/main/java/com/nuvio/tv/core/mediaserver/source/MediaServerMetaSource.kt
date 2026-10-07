package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.MsLog as Logger
import com.nuvio.tv.core.contracts.MetaSourceProvider
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds.Kind
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import kotlinx.coroutines.CancellationException

/**
 * Native metadata for a server's own titles (design 5.4 "Details"): the server's item (+ its episodes for a
 * series) becomes [MetaDetails], enriched from TMDB when the item carries a TMDB id and the user has TMDB on -
 * exactly the Xtream lane's behaviour. A series page's episodes are `MetaVideo`s WITHOUT embedded streams:
 * they resolve through the direct lane (`handlesId` -> registry), so the store-build embedded-stream filter
 * never sees them.
 */
internal class MediaServerMetaSource(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
    /** Layers TMDB art / cast onto the server's own metadata for an item carrying a TMDB id (the IPTV lane does the same). */
    private val enrich: suspend (Meta, tmdbId: String) -> Meta = { meta, _ -> meta },
) : MetaSourceProvider {
    private val log = Logger.withTag("MediaServerMetaSource")

    override fun handlesId(id: String): Boolean = MediaServerIds.isContentId(id)

    override suspend fun meta(type: String, id: String): Meta? {
        val parsed = MediaServerIds.parse(id) ?: return null
        if (parsed.kind != Kind.MOVIE && parsed.kind != Kind.SERIES) return null
        val entry = store.entryByServerKey(parsed.serverKey) ?: return null
        val client = services.clientFor(entry) ?: return null
        return try {
            val item = client.item(parsed.itemId, fields = DETAIL_FIELDS) ?: return null
            val episodes = if (parsed.kind == Kind.SERIES) client.episodes(parsed.itemId, fields = EPISODE_FIELDS) else emptyList()
            MediaServerItemMapper.registered(entry, item)?.let(MediaServerItemRegistry::register)
            episodes.forEach { ep -> MediaServerItemMapper.registered(entry, ep)?.let(MediaServerItemRegistry::register) }
            val meta = MediaServerItemMapper.details(entry, item, episodes) ?: return null
            val tmdbId = item.providerIds.entries.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
            if (tmdbId != null) enrichSafely(meta, tmdbId) else meta
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            null
        } catch (e: MediaServerException) {
            null
        }
    }

    /**
     * Rebuilds the registry record of a persisted `ms:` id whose in-memory entry was lost (Continue Watching /
     * Library after a fresh launch): one item fetch. True once a playable item is registered. Nothing here is
     * single-use (unlike Stalker links), so [forceFresh] only skips the cache.
     */
    suspend fun ensureStreamRegistered(id: String, forceFresh: Boolean = false): Boolean {
        // A row / episode list registers cards WITHOUT media sources (a light fetch); play needs them (labels, sidecar subtitles), so such a record is refetched once.
        val cached = MediaServerItemRegistry.get(id)
        if (!forceFresh && cached != null && (cached.sources.isNotEmpty() || cached.kind == Kind.SERIES)) return true
        val parsed = MediaServerIds.parse(id) ?: return false
        val entry = store.entryByServerKey(parsed.serverKey) ?: return false
        val client = services.clientFor(entry) ?: return false
        return try {
            val item = client.item(parsed.itemId, fields = "Overview,MediaSources") ?: return false
            val registered = MediaServerItemMapper.registered(entry, item) ?: return false
            // the registry key is the id the caller asked with (its kind is authoritative, not the server type's guess)
            MediaServerItemRegistry.register(registered.copy(contentId = id))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            false
        } catch (e: MediaServerException) {
            false
        }
    }

    private suspend fun enrichSafely(meta: Meta, tmdbId: String): Meta =
        try {
            enrich(meta, tmdbId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "TMDB enrichment failed for tmdb:$tmdbId" }
            meta
        }

    private companion object {
        const val DETAIL_FIELDS = "Overview,Genres,People,ProviderIds,MediaSources,OfficialRating,CommunityRating,PremiereDate,ProductionYear,Status,EndDate,Taglines"
        const val EPISODE_FIELDS = "Overview,PremiereDate"
    }
}
