package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.OwnSourcePolicy

/**
 * IPTV's entries in the plural own-source registries (media-servers design 5.1, TV). The literals that used to
 * sit inside upstream-aligned code (`PlaybackAvailability` / `AddonSubtitleIdPolicy`) live here, with the
 * feature that owns them, so a second source (a media server) registers beside them instead of editing them.
 * Called once per process from the composition root (NuvioApplication).
 */
object IptvSourceRegistrations {
    const val NAME = "iptv"

    /** Xtream / Stalker / M3U-link items play under `xtream:` ids, M3U playlist items under `m3u:` ids. */
    private val CONTENT_ID_PREFIXES = listOf("xtream:", "m3u:")

    /** Ids whose account part is the raw playlist key (server + username, URL, MAC): never leave the device. */
    private const val PROVIDER_SCOPED_PREFIX = "xtream:"

    fun register(searchProvider: com.nuvio.tv.core.contracts.IptvSearchProvider? = null) {
        searchProvider?.let { com.nuvio.tv.core.contracts.SearchProviderRegistry.register(NAME, it) }
        OwnSourcePolicy.registerContentIdPredicate(NAME) { id -> CONTENT_ID_PREFIXES.any { id.startsWith(it) } }
        OwnSourcePolicy.registerSubtitleScopedPredicate(NAME) { id -> id.startsWith(PROVIDER_SCOPED_PREFIX) }
    }
}
