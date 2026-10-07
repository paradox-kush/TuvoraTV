package com.nuvio.tv.core.contracts

import com.nuvio.tv.domain.model.Meta

/**
 * Neutral port: an own source (a media server) that resolves its OWN ids to a [Meta] for the detail
 * screen / Continue Watching enrichment, bypassing add-on resolution. TV's IPTV lane keeps its inline
 * check in `MetaRepositoryImpl` (golden-list frozen); other sources register here, plural, duplicate refused.
 */
interface MetaSourceProvider {
    fun handlesId(id: String): Boolean

    /** The meta for [id], or null when it is gone / unreachable (the caller shows "no longer available"). */
    suspend fun meta(type: String, id: String): Meta?
}

object MetaSourceRegistry {
    private val providers = NamedRegistry<MetaSourceProvider>("MetaSourceProvider")

    fun register(name: String, provider: MetaSourceProvider) = providers.register(name, provider)

    val all: List<MetaSourceProvider> get() = providers.all

    fun resetForTest() = providers.resetForTest()
}

/** The combined view: first provider that handles the id answers. */
object MetaSourceAccess {
    fun handlesId(id: String?): Boolean = id != null && MetaSourceRegistry.all.any { it.handlesId(id) }

    suspend fun meta(type: String, id: String): Meta? =
        MetaSourceRegistry.all.firstOrNull { it.handlesId(id) }?.meta(type, id)
}
