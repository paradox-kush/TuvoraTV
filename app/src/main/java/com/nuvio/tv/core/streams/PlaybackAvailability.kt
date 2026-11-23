package com.nuvio.tv.core.streams

import com.nuvio.tv.core.usenet.builtInUsenetCanSearch
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Video

internal fun Addon.supportsStreamResource(type: String, videoId: String): Boolean =
    supportsResource("stream", type, videoId)

internal fun Addon.supportsResource(name: String, type: String, id: String): Boolean =
    resources.any { resource ->
        resource.name == name &&
            (resource.types.isEmpty() || resource.types.contains(type)) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { id.startsWith(it) }
            }
    }

internal data class PlaybackAvailability(
    val addons: List<Addon> = emptyList(),
    val scrapers: List<ScraperInfo> = emptyList(),
    val isLoaded: Boolean = false,
    /** True when the built-in Usenet sources are enabled and have a provider and an indexer. */
    val usenetReady: Boolean = false,
    private val cachedMeta: (String, String) -> Meta? = { _, _ -> null },
    /**
     * Stremio content types ("movie", "series") the user's IPTV accounts can supply streams for through
     * the Xtream source lane — a source like a scraper, so an IPTV-only user can still play catalog titles.
     */
    val iptvSourceTypes: Set<String> = emptySet(),
    private val serverStreams: (String, String) -> Boolean = { _, _ -> false },
    val hasPendingAddonManifests: Boolean = false,
    val hasFailedAddonManifests: Boolean = false
) {
    fun canStream(
        type: String,
        videoId: String,
        contentId: String = videoId,
        video: Video? = null
    ): Boolean = ((hasPendingAddonManifests || hasFailedAddonManifests) && com.nuvio.tv.core.build.AppFeaturePolicy.addonStreamSourcesEnabled) ||
        isIptvId(videoId) || isIptvId(contentId) ||
        type in iptvSourceTypes ||
        video?.takeIf { it.id == videoId }?.streams?.isNotEmpty() == true ||
        cachedMeta(type, contentId)?.videos?.any { it.id == videoId && it.streams.isNotEmpty() } == true ||
        addons.any { it.enabled && it.supportsStreamResource(type, videoId) } ||
        scrapers.any { it.enabled && it.supportsType(type) } ||
        // Built-in sources are searched on play, so a title can play even with no addon installed.
        (usenetReady && builtInUsenetCanSearch(type, videoId)) ||
        serverStreams(type, videoId)

    companion object {
        // Items of an own source (IPTV Xtream/Stalker "xtream:…", M3U "m3u:…") are played by that source's
        // resolver directly and never need an addon or scraper. Without this, upstream's play-disable check
        // greys out Play/Resume for IPTV-only users (store builds hide addons). Which ids are "own" is the
        // registered [OwnSourcePolicy], not a literal here. Media-server items (upstream's `server:` ids)
        // are covered by [serverStreams] instead.
        fun isIptvId(id: String): Boolean = com.nuvio.tv.core.contracts.OwnSourcePolicy.isOwnContentId(id)
    }
}
