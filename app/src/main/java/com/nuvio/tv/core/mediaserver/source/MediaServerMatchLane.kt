package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.MsLog

import com.nuvio.tv.core.contracts.StreamSourceGroup
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.client.MediaServerClient
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDialect
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaSourceDto
import com.nuvio.tv.core.mediaserver.policy.MatchCache
import com.nuvio.tv.core.mediaserver.policy.MatchLookupPolicy
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException

/** Where a title page's TMDB/IMDb ids and titles come from (TMDB in production, a fake in tests). */
internal fun interface MatchTitleFacts {
    suspend fun facts(videoId: String, type: String): MatchLookupPolicy.TitleFacts?
}

/**
 * The matched lane (design 5.6, P3; TV port of the Mobile/Desktop lane): on a title page for a TMDB/IMDb title, each signed-in server that HAS the
 * title offers its files as a source - one entry per `MediaSource` ("1080p · H.264 · 4.2 GB" under the server's
 * name). Lookup: Emby filters server-side (`AnyProviderIdEquals`); Jellyfin has no provider-id filter, so it is a
 * title(+year) search verified against `ProviderIds` ([MatchLookupPolicy]). The answer is cached per server with a
 * TTL ([MatchCache]) so reopening the page and the in-player episode switch cost nothing; there is NO polling - a
 * lookup happens only when a title page / the player asks, once per cache lifetime.
 *
 * Results are the same deferred streams as the direct lane (`ms-deferred:`), provider id `ms-match:{serverKey}`,
 * so minting, the credential-refresh gate, progress reports and the "never cache a media-server link" rule all
 * follow the existing paths.
 */
internal class MediaServerMatchLane(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
    private val nowMs: () -> Long,
    private val titleFacts: MatchTitleFacts,
    private val cache: MatchCache = MatchCache(),
) {
    private val log = MsLog.withTag("MediaServerMatchLane")

    /** One source group per signed-in, enabled server (a signed-out / expired / switched-off server offers nothing, and is never asked). */
    fun groups(type: String): List<StreamSourceGroup> {
        if (kindOf(type) == null) return emptyList()
        val expired = services.expiredSessions.value
        return store.current()
            .filter { it.enabled && !it.address.isNullOrBlank() && services.isSignedIn(it) && it.serverKey !in expired }
            .map { StreamSourceGroup(MediaServerIds.matchGroupId(it.serverKey), it.name) }
    }

    suspend fun streams(sourceId: String, type: String, videoId: String, season: Int?, episode: Int?): List<Stream> {
        val kind = kindOf(type) ?: return emptyList()
        val serverKey = MediaServerIds.serverKeyOfMatchGroup(sourceId) ?: return emptyList()
        val entry = store.entryByServerKey(serverKey)?.takeIf { it.enabled } ?: return emptyList()
        val client = services.clientFor(entry) ?: return emptyList()
        return try {
            val facts = titleFacts.facts(videoId, type)?.takeIf { it.ids.hasAny } ?: return emptyList()
            val matched = matchedItemIds(entry, client, kind, facts)
            if (matched.isEmpty()) return emptyList()
            val headers = authHeaders(entry, services)
            val groupId = MediaServerIds.matchGroupId(serverKey)
            val playable = when (kind) {
                MatchLookupPolicy.ItemKind.MOVIE -> matched.mapNotNull { id -> withSources(client, id) }
                MatchLookupPolicy.ItemKind.SERIES -> {
                    val s = season ?: return emptyList()
                    val e = episode ?: return emptyList()
                    matched.mapNotNull { seriesId -> episodeOf(client, seriesId, s, e) }
                }
            }
            if (playable.isEmpty() && kind == MatchLookupPolicy.ItemKind.MOVIE) {
                // every cached id is gone (deleted on the server): forget the answer so the next page asks again
                cache.invalidate(cacheKey(serverKey, kind, facts) ?: return emptyList())
            }
            playable.flatMap { item -> streamsOf(entry, item, MediaServerVersions.of(client, item), kind, groupId, headers) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(serverKey)
            log.w { "match failed: HTTP ${e.status}" }
            emptyList() // a failed lookup is never cached: the next open asks again
        } catch (e: MediaServerException) {
            log.w { "match failed: ${e::class.simpleName}" }
            emptyList()
        }
    }

    /** The server's item ids that carry [facts]' ids: cached, else one lookup (a Jellyfin title search is up to [MatchLookupPolicy.MAX_TITLE_QUERIES] requests). */
    private suspend fun matchedItemIds(
        entry: MediaServerEntry,
        client: MediaServerClient,
        kind: MatchLookupPolicy.ItemKind,
        facts: MatchLookupPolicy.TitleFacts,
    ): List<String> {
        val key = cacheKey(entry.serverKey, kind, facts) ?: return emptyList()
        when (val cached = cache.get(key, nowMs())) {
            is MatchCache.Answer.Hit -> return cached.itemIds
            MatchCache.Answer.NotOnServer -> return emptyList()
            MatchCache.Answer.Unknown -> Unit
        }
        val dialect = MediaBrowserDialect.of(entry.type)
        val found = LinkedHashSet<String>()
        for (query in MatchLookupPolicy.queries(dialect, kind, facts)) {
            val candidates = client.lookup(query, LOOKUP_LIMIT)
            // Both dialects verify (as Plezy does): Jellyfin's title search is only a candidate list, and a filter a
            // server silently ignores (Jellyfin does with AnyProviderIdEquals) would otherwise return an arbitrary page.
            MatchLookupPolicy.verify(candidates, facts.ids).mapNotNullTo(found) { it.id }
            if (found.isNotEmpty()) break // the primary title found it: no need to try the original / translated names
        }
        val ids = found.take(MAX_EDITIONS)
        cache.put(key, ids, nowMs()) // an empty list is the cached "not on this server"
        return ids
    }

    private fun cacheKey(serverKey: String, kind: MatchLookupPolicy.ItemKind, facts: MatchLookupPolicy.TitleFacts): MatchCache.Key? =
        MatchCache.externalId(facts.ids)?.let { MatchCache.Key(serverKey, kind, it) }

    private suspend fun withSources(client: MediaServerClient, itemId: String): ItemDto? = client.item(itemId, fields = SOURCE_FIELDS)

    /**
     * The server's own episode for a TMDB (season, episode): seasons -> that season's episodes -> the episode with its sources.
     * A server with no season items (an on-demand library whose season fetcher is off answers `/Seasons` with `[]` while
     * `/Episodes` lists everything with `ParentIndexNumber`) or no `/Seasons` route at all falls back to the series' episode list.
     */
    private suspend fun episodeOf(client: MediaServerClient, seriesId: String, season: Int, episode: Int): ItemDto? {
        val seasonItem = try {
            client.seasons(seriesId).firstOrNull { it.indexNumber == season }
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) throw e else null
        }
        val seasonId = seasonItem?.id
        val ep = if (seasonId != null) {
            client.episodes(seriesId, seasonId).firstOrNull { it.indexNumber == episode && (it.parentIndexNumber ?: season) == season }
        } else {
            client.episodes(seriesId).firstOrNull { it.indexNumber == episode && it.parentIndexNumber == season }
        } ?: return null
        val epId = ep.id ?: return null
        return client.item(epId, fields = SOURCE_FIELDS)
    }

    private fun streamsOf(
        entry: MediaServerEntry,
        item: ItemDto,
        versions: List<MediaSourceDto>,
        kind: MatchLookupPolicy.ItemKind,
        groupId: String,
        headers: Map<String, String>?,
    ): List<Stream> {
        val itemId = item.id ?: return emptyList()
        val title = when (kind) {
            MatchLookupPolicy.ItemKind.MOVIE -> item.name
            MatchLookupPolicy.ItemKind.SERIES ->
                listOfNotNull(item.parentIndexNumber?.let { s -> item.indexNumber?.let { e -> "S${s}E$e" } }, item.name).joinToString(" · ").ifBlank { null }
        }
        val sources = MediaServerItemMapper.sourcesOf(itemId, versions)
        // an item the server lists without sources still plays (the mint step asks PlaybackInfo): one default entry; an item
        // whose only "versions" are stand-ins (the server found nothing to play) offers nothing
        if (sources.isEmpty()) return if (versions.isEmpty()) listOf(MediaServerStreamItems.build(entry, itemId, title, null, groupId, headers)) else emptyList()
        return sources.map { source -> MediaServerStreamItems.build(entry, itemId, title, source, groupId, headers) }
    }

    private fun kindOf(type: String): MatchLookupPolicy.ItemKind? = when (type.trim().lowercase()) {
        "movie", "film" -> MatchLookupPolicy.ItemKind.MOVIE
        "series", "tv", "show", "tvshow" -> MatchLookupPolicy.ItemKind.SERIES
        else -> null
    }

    private companion object {
        const val LOOKUP_LIMIT = 20
        /** More than a handful of editions of one title on one server is a library oddity, not a pick list. */
        const val MAX_EDITIONS = 4
        const val SOURCE_FIELDS = "MediaSources,ProviderIds"
    }
}
