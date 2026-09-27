package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Video

internal fun Addon.supportsStreamResource(type: String, videoId: String): Boolean =
    resources.any { resource ->
        resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.contains(type)) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { videoId.startsWith(it) }
            }
    }

internal data class PlaybackAvailability(
    val addons: List<Addon> = emptyList(),
    val scrapers: List<ScraperInfo> = emptyList(),
    val isLoaded: Boolean = false,
    private val cachedMeta: (String, String) -> Meta? = { _, _ -> null },
    /**
     * Stremio content types ("movie", "series") the user's IPTV accounts can supply streams for through
     * the Xtream source lane — a source like a scraper, so an IPTV-only user can still play catalog titles.
     */
    val iptvSourceTypes: Set<String> = emptySet(),
) {
    fun canStream(
        type: String,
        videoId: String,
        contentId: String = videoId,
        video: Video? = null
    ): Boolean = isIptvId(videoId) || isIptvId(contentId) ||
        type in iptvSourceTypes ||
        video?.takeIf { it.id == videoId }?.streams?.isNotEmpty() == true ||
        cachedMeta(type, contentId)?.videos?.any { it.id == videoId && it.streams.isNotEmpty() } == true ||
        addons.any { it.enabled && it.supportsStreamResource(type, videoId) } ||
        scrapers.any { it.enabled && it.supportsType(type) }

    companion object {
        // IPTV items (Xtream/Stalker "xtream:…", M3U "m3u:…") are played by the IPTV resolver directly and
        // never need an addon or scraper. Without this, upstream's play-disable check greys out Play/Resume
        // for IPTV-only users (store builds hide addons).
        private val IPTV_ID_PREFIXES = listOf("xtream:", "m3u:")
        fun isIptvId(id: String): Boolean = IPTV_ID_PREFIXES.any { id.startsWith(it) }
    }
}
