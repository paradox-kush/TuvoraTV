package com.nuvio.tv.core.contracts

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

/** One match source (a signed-in media server), identified by an opaque id its owner maps back. */
data class StreamSourceGroup(val sourceId: String, val addonName: String)

/**
 * Neutral port for own-source stream resolution that is NOT IPTV (a media server). TV's IPTV lane keeps its
 * own direct path inside `StreamRepositoryImpl` (its account store, Stalker minting and failover are
 * interleaved with it - the golden-list test freezes that behaviour); every OTHER source registers here, and
 * the repository asks the combined view ([StreamSourceAccess]) next to the IPTV lane. Sources are PLURAL:
 * each registers under its own name in [StreamSourceRegistry]; with nothing registered everything is
 * not-handled / empty / null.
 */
interface StreamSourceProvider {
    /** True when [videoId] is this source's own namespaced id and resolves to its own direct streams. */
    fun isHandledId(videoId: String?): Boolean

    /** The direct stream group(s) for [videoId] - deferred streams only, no token, no playable URL. */
    suspend fun directStreams(videoId: String): List<AddonStreams>

    /** One match source per signed-in server, for TMDB [type] ("movie"/"series"). Empty until the matched lane (P3). */
    fun matchSourceGroups(type: String): List<StreamSourceGroup>

    /** Resolve the streams for a single [matchSourceGroups] entry. */
    suspend fun resolveMatchStreams(
        sourceId: String,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<Stream>

    /** True when [providerAddonId] identifies a match source (a [matchSourceGroups] entry's id). */
    fun isMatchSourceId(providerAddonId: String): Boolean

    /** True when [url] is a deferred (not-yet-minted) play URL of this source. */
    fun isDeferredUrl(url: String?): Boolean

    /** Mint the real play URL for a deferred [url], or null. [forceMint] = the previous attempt failed (pick a transcode). */
    suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String?
}

object StreamSourceRegistry {
    private val providers = NamedRegistry<StreamSourceProvider>("StreamSourceProvider")

    fun register(name: String, provider: StreamSourceProvider) = providers.register(name, provider)

    val all: List<StreamSourceProvider> get() = providers.all

    fun resetForTest() = providers.resetForTest()
}

/**
 * The plural view behind the single-provider interface the repository speaks. Id-keyed calls go to the first
 * provider that handles the id; match groups concatenate in registration order; deferred URLs mint through
 * the claiming provider.
 */
class CompositeStreamSourceProvider(
    private val providers: () -> List<StreamSourceProvider>,
) : StreamSourceProvider {
    override fun isHandledId(videoId: String?): Boolean = providers().any { it.isHandledId(videoId) }

    override suspend fun directStreams(videoId: String): List<AddonStreams> =
        providers().firstOrNull { it.isHandledId(videoId) }?.directStreams(videoId).orEmpty()

    override fun matchSourceGroups(type: String): List<StreamSourceGroup> =
        providers().flatMap { it.matchSourceGroups(type) }

    override suspend fun resolveMatchStreams(
        sourceId: String,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<Stream> =
        providers().firstOrNull { it.isMatchSourceId(sourceId) }
            ?.resolveMatchStreams(sourceId, type, videoId, season, episode)
            .orEmpty()

    override fun isMatchSourceId(providerAddonId: String): Boolean =
        providers().any { it.isMatchSourceId(providerAddonId) }

    override fun isDeferredUrl(url: String?): Boolean = providers().any { it.isDeferredUrl(url) }

    override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? =
        providers().firstOrNull { it.isDeferredUrl(url) }?.resolveDeferredUrl(url, forceMint)
}

/** Thin read facade: the combined view of [StreamSourceRegistry]. Stable instance, empty until a source registers. */
object StreamSourceAccess {
    private val composite = CompositeStreamSourceProvider { StreamSourceRegistry.all }

    fun current(): StreamSourceProvider = composite
}
