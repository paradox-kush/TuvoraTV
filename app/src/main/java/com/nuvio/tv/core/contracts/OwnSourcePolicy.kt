package com.nuvio.tv.core.contracts

/**
 * Which content ids and stream-provider ids belong to a user-owned SOURCE (an IPTV playlist, a media
 * server) rather than to an add-on/debrid provider. Upstream-aligned code asks this instead of hard-coding
 * the IPTV literals ("xtream:" / "m3u:" content ids). TV twin of the KMP `OwnSourcePolicy`.
 *
 *  - [isOwnContentId]: the id is played by its own source lane, never needs an add-on or scraper
 *    (`PlaybackAvailability`);
 *  - [isOwnProviderId]: a stream came from an own source (a stream group / `Stream.sourceId`);
 *  - [isSubtitleScopedId]: the id embeds something that must never reach a third-party subtitle add-on
 *    (an IPTV playlist key, a media server's machine/user ids);
 *  - [isExcludedFromTrackingScrobble]: never scrobbled to Trakt / Simkl / MDBList (a media server's own
 *    items in v1 - owner decision 2026-10-06);
 *  - [telemetryId]: how an analytics event may name an item that embeds device-private identity.
 *
 * Predicate registries, never one: a content id and a provider id are different namespaces. Features
 * register from the composition root (no fork reference here). A duplicate name is refused.
 */
object OwnSourcePolicy {
    private val contentIdPredicates = NamedRegistry<(String) -> Boolean>("own content-id predicate")
    private val providerIdPredicates = NamedRegistry<(String) -> Boolean>("own provider-id predicate")
    private val subtitleScoped = NamedRegistry<(String) -> Boolean>("subtitle-scoped id predicate")
    private val scrobbleExclusions = NamedRegistry<(String) -> Boolean>("tracking-scrobble exclusion")
    private val telemetryRewriters = NamedRegistry<(id: String, salt: String) -> String?>("telemetry-id rewriter")
    private val linkCacheExclusions = NamedRegistry<(String) -> Boolean>("link-cache exclusion")

    fun registerContentIdPredicate(name: String, predicate: (String) -> Boolean) =
        contentIdPredicates.register(name, predicate)

    fun registerProviderIdPredicate(name: String, predicate: (String) -> Boolean) =
        providerIdPredicates.register(name, predicate)

    fun registerSubtitleScopedPredicate(name: String, predicate: (String) -> Boolean) =
        subtitleScoped.register(name, predicate)

    fun registerScrobbleExclusion(name: String, predicate: (String) -> Boolean) =
        scrobbleExclusions.register(name, predicate)

    /** A source whose minted play links must never be stored for reuse (they are minted per play and may carry a token). */
    fun registerLinkCacheExclusion(name: String, predicate: (String) -> Boolean) =
        linkCacheExclusions.register(name, predicate)

    /** [rewriter] returns null for "not mine". */
    fun registerTelemetryRewriter(name: String, rewriter: (id: String, salt: String) -> String?) =
        telemetryRewriters.register(name, rewriter)

    /** True when [id] is a namespaced content id of any registered own source. */
    fun isOwnContentId(id: String?): Boolean =
        id != null && contentIdPredicates.all.any { it(id) }

    /** True when [addonId] (a stream group / provider id) belongs to any registered own source. */
    fun isOwnProviderId(addonId: String?): Boolean =
        !addonId.isNullOrBlank() && providerIdPredicates.all.any { it(addonId) }

    /** True when [id] (trimmed) must not be sent to a subtitle add-on. */
    fun isSubtitleScopedId(id: String?): Boolean {
        val trimmed = id?.trim() ?: return false
        return subtitleScoped.all.any { it(trimmed) }
    }

    fun isNeverCachedLinkId(id: String?): Boolean =
        id != null && linkCacheExclusions.all.any { it(id) }

    fun isExcludedFromTrackingScrobble(id: String?): Boolean =
        id != null && scrobbleExclusions.all.any { it(id) }

    /** The id a telemetry event may carry for [id]: the owning source's rewrite, else [id] unchanged. */
    fun telemetryId(id: String, installSalt: String): String =
        telemetryRewriters.all.firstNotNullOfOrNull { it(id, installSalt) } ?: id

    fun resetForTest() {
        contentIdPredicates.resetForTest()
        providerIdPredicates.resetForTest()
        subtitleScoped.resetForTest()
        scrobbleExclusions.resetForTest()
        telemetryRewriters.resetForTest()
        linkCacheExclusions.resetForTest()
    }
}
