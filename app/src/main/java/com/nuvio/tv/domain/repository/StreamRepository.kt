package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.Flow

interface StreamRepository {
    /**
     * Fetches streams from all installed addons for a given video ID
     * @param type The content type (movie, series, etc.)
     * @param videoId The video ID (for movies: IMDB ID, for series: IMDB_ID:season:episode)
     * @param season Optional season number for TV shows (used by local plugins)
     * @param episode Optional episode number for TV shows (used by local plugins)
     * @return Flow of AddonStreams grouped by addon
     */
    fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null
    ): Flow<NetworkResult<List<AddonStreams>>>

    /**
     * Fetches streams from a specific addon
     * @param baseUrl The addon base URL
     * @param type The content type
     * @param videoId The video ID
     * @return NetworkResult containing list of streams
     */
    suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>>

    /**
     * Re-resolves a fresh playable URL for an xtream/stalker item mid-playback. Stalker play URLs
     * carry a single-use/short-TTL create_link token, so a stream that 401s (expired token, session
     * rotated by another device on the same MAC) can often be revived by minting a new link instead
     * of surfacing the fatal error screen.
     *
     * @param videoId an [com.nuvio.tv.core.iptv.XtreamItemRegistry] id ("xtream:acc:kind:streamId")
     * @return a fresh URL, or null when the id isn't an xtream id / account is gone / resolve failed
     */
    suspend fun refreshIptvStreamUrl(videoId: String): String?
}
