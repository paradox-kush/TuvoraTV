package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents a stream source from a Stremio addon
 */
@Immutable
data class Stream(
    val name: String?,
    val title: String?,
    val description: String?,
    val url: String?,
    val ytId: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val externalUrl: String?,
    val behaviorHints: StreamBehaviorHints?,
    val addonName: String,
    val addonLogo: String?,
    val sources: List<String>? = null,
    val quality: String? = null,
    val qualityValue: Int = -1,
    /** Sidecar subtitle files bundled with the stream (currently from local library sources). */
    val externalSubtitles: List<ExternalSubtitle> = emptyList()
) {
    /**
     * Returns the primary stream source URL
     */
    fun getStreamUrl(): String? = url ?: externalUrl

    /**
     * Returns true if this is a torrent-only stream (no HTTP URL available).
     * When both infoHash and url are present (e.g. debrid cached torrents),
     * the HTTP url is preferred and this returns false.
     */
    fun isTorrent(): Boolean = infoHash != null && url.isNullOrBlank()

    /**
     * Returns true if this is a YouTube stream
     */
    fun isYouTube(): Boolean = ytId != null

    /**
     * Returns true if this is an external URL (opens in browser)
     */
    fun isExternal(): Boolean = externalUrl != null && url == null

    /**
     * Returns a display name for the stream
     */
    fun getDisplayName(): String = name ?: title ?: description ?: "Unknown Stream"

    /**
     * Returns a display description for the stream
     */
    fun getDisplayDescription(): String? = description ?: title

    /**
     * Returns a stable key for use in LazyColumn/LazyRow.
     * Incorporates all content-identifying fields so the key doesn't change
     * when the list recomposes or items shift position. The [occurrence] parameter
     * disambiguates genuine duplicates (same addon+url+name+title).
     */
    fun stableKey(occurrence: Int = 0): String = buildString {
        append(addonName)
        append('\u0000')
        append(url ?: infoHash ?: ytId ?: externalUrl ?: "")
        append('\u0000')
        append(name ?: "")
        append('\u0000')
        append(title ?: "")
        if (occurrence > 0) {
            append('\u0000')
            append(occurrence)
        }
    }
}

@Immutable
data class StreamBehaviorHints(
    val notWebReady: Boolean?,
    val bingeGroup: String?,
    val countryWhitelist: List<String>?,
    val proxyHeaders: ProxyHeaders?,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null
)

@Immutable
data class ProxyHeaders(
    val request: Map<String, String>?,
    val response: Map<String, String>?
)

/**
 * A subtitle track delivered alongside a [Stream] (e.g. sidecar file next to the
 * media on an SMB share or Jellyfin server). Attached to ExoPlayer as a
 * `MediaItem.SubtitleConfiguration` at player startup.
 */
@Immutable
data class ExternalSubtitle(
    val url: String,
    val displayName: String,
    val language: String?,
    val mimeType: String,
    val isForced: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)

/**
 * Represents streams grouped by addon source
 */
@Immutable
data class AddonStreams(
    val addonName: String,
    val addonLogo: String?,
    val streams: List<Stream>
)
