package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.IptvSearchHit
import com.nuvio.tv.core.contracts.IptvSearchProvider
import com.nuvio.tv.core.contracts.IptvSearchRow
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * One search entry for every signed-in server (design 5.4 "Search"): the registry merges its rows next to IPTV's.
 * A server's rows are its `Movies` and `TV Shows` hits of `/Items?SearchTerm=` (the one route both dialects share;
 * Emby has no `/Search`). The provider's own gate ([hasSearchableSources]) means a signed-out or disabled server is
 * never queried and adds nothing to the request key.
 */
internal class MediaServerSearchProvider(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
    private val titles: MediaServerRowTitles,
) : IptvSearchProvider {
    private fun searchable(): List<MediaServerEntry> =
        store.current().filter { it.enabled && !it.address.isNullOrBlank() && services.isSignedIn(it) }

    override suspend fun hasSearchableSources(): Boolean = searchable().isNotEmpty()

    override suspend fun search(query: String): List<IptvSearchRow> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return coroutineScope {
            searchable().map { entry -> async { searchOne(entry, term) } }.awaitAll().flatten()
        }
    }

    private suspend fun searchOne(entry: MediaServerEntry, term: String): List<IptvSearchRow> {
        val client = services.clientFor(entry) ?: return emptyList()
        val hits = try {
            client.search(term, SEARCH_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            return emptyList()
        } catch (e: MediaServerException) {
            return emptyList()
        }
        MediaServerItemRegistry.registerAll(hits.mapNotNull { MediaServerItemMapper.registered(entry, it) })
        val previews = hits.mapNotNull { MediaServerItemMapper.preview(entry, it) }
        val movies = previews.filter { it.rawType == "movie" }
        val series = previews.filter { it.rawType == "series" }
        return buildList {
            if (movies.isNotEmpty()) add(row(entry, "search:movies", "movie", titles.searchMovies(entry.name), movies))
            if (series.isNotEmpty()) add(row(entry, "search:series", "series", titles.searchSeries(entry.name), series))
        }
    }

    private fun row(entry: MediaServerEntry, listId: String, contentType: String, title: String, items: List<com.nuvio.tv.domain.model.MetaPreview>) =
        IptvSearchRow(
            // Row keys never contain the user id: a re-login must not orphan anything keyed on them.
            catalogId = "${MediaServerIds.CONTENT_PREFIX}:${entry.sourceKey}:$listId",
            name = title,
            rawType = contentType,
            hits = items.map { IptvSearchHit(contentId = it.id, name = it.name, poster = it.poster, isLive = false) },
        )

    /** Enabled + signed-in servers and the credential state, as a digest - never a credential. Null while nothing is searchable. */
    override fun sourceSignature(): Flow<String?> =
        combine(store.entries, services.credentialVersion, services.expiredSessions) { _, _, _ -> signatureOf(searchable()) }.distinctUntilChanged()

    private fun signatureOf(entries: List<MediaServerEntry>): String? =
        entries.takeIf { it.isNotEmpty() }?.joinToString("|") { MediaServerIds.hash(it.serverKey + (it.address ?: "")) }

    private companion object {
        const val SEARCH_LIMIT = 20
    }
}
