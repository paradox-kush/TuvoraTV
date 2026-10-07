package com.nuvio.tv.core.contracts

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Own-source search is PLURAL (media-servers design 5.1): each source (IPTV playlists, a signed-in media
 * server) registers one [IptvSearchProvider] under its own name; duplicates are refused. Each provider carries
 * its own enabled gate ([IptvSearchProvider.hasSearchableSources]) - today's single `iptvSearchProvider`
 * collaborator in `SearchViewModel` is the [CompositeSearchProvider] over them, so that screen is unchanged.
 */
object SearchProviderRegistry {
    private val providers = NamedRegistry<IptvSearchProvider>("SearchProvider")

    fun register(name: String, provider: IptvSearchProvider) = providers.register(name, provider)

    val all: List<IptvSearchProvider> get() = providers.all

    fun resetForTest() = providers.resetForTest()
}

/**
 * The combined view over [providers] (registration order). With exactly one provider every answer is that
 * provider's own - byte-identical to the old single binding; with none, nothing is searchable.
 *  - [hasSearchableSources]: any provider has a source;
 *  - [search]: the rows of every searchable provider, concurrently, concatenated in registration order (a
 *    provider that throws costs only its own rows);
 *  - [sourceSignature]: null while no provider has a signature; the lone signature unchanged with one
 *    provider; otherwise the non-null signatures joined, so any source-set change refreshes the shown search.
 */
class CompositeSearchProvider(
    private val providers: () -> List<IptvSearchProvider>,
) : IptvSearchProvider {
    override suspend fun hasSearchableSources(): Boolean = providers().any { it.hasSearchableSources() }

    override suspend fun search(query: String): List<IptvSearchRow> = coroutineScope {
        providers().map { provider ->
            async {
                if (!provider.hasSearchableSources()) emptyList()
                else try {
                    provider.search(query)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.flatMap { it.await() }
    }

    override fun sourceSignature(): Flow<String?> {
        val all = providers()
        if (all.isEmpty()) return flowOf(null)
        if (all.size == 1) return all.single().sourceSignature()
        return combine(all.map { it.sourceSignature() }) { signatures ->
            signatures.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString("\u0004")
        }.distinctUntilChanged()
    }
}
