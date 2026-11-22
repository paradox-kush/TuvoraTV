package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.IptvSearchProvider
import com.nuvio.tv.core.contracts.IptvSearchRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Registers an [IptvSearchProvider] without building it. The real provider's graph (search index, match
 * index, overlay repository, content DB) is created on the first search call instead of in
 * `Application.onCreate`, which also runs for headless starts with a bindApplication deadline.
 */
class LazyIptvSearchProvider(create: () -> IptvSearchProvider) : IptvSearchProvider {
    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED, create)

    override suspend fun hasSearchableSources(): Boolean = delegate.hasSearchableSources()

    override suspend fun search(query: String): List<IptvSearchRow> = delegate.search(query)

    override fun sourceSignature(): Flow<String?> = flow { emitAll(delegate.sourceSignature()) }
}
