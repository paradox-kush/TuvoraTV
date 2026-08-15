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
     * @param forceFresh true on the 401/403/410 recovery path: bypass Stalker's static-cmd
     *   verdict and mint a genuinely new create_link — a static URL that just died would
     *   otherwise be rebuilt byte-identical and replay the failure. Initial plays/zaps leave it
     *   false so unflagged rows keep their zero-request static playback.
     * @return a fresh URL, or null when the id isn't an xtream id / account is gone / resolve failed
     */
    suspend fun refreshIptvStreamUrl(videoId: String, forceFresh: Boolean = false): String?

    /**
     * Same recovery for a TMDB-MATCHED iptv stream — [videoId] is a tmdb/imdb id here, so
     * [refreshIptvStreamUrl] can't help. Matched-lane streams are labeled with the owning
     * account's display name ([addonName]); this re-runs the xtream/stalker matcher for that
     * account and returns the rebuilt stream's fresh URL, preferring the edition whose label
     * equals [streamName]. Null when the label doesn't belong to an iptv account, the matcher
     * finds nothing, or the "fresh" URL is identical to [failedUrl] (stable Xtream URLs — a 401
     * there is an account/provider problem no new link can fix).
     */
    /**
     * Mints the real play link for a matched Stalker source that was listed without one (see
     * XtreamStreamSource's deferred scheme). Returns [url] unchanged when it isn't deferred, or
     * null when the portal won't issue a link.
     */
    suspend fun mintDeferredIptvUrl(url: String): String?

    suspend fun refreshMatchedIptvStreamUrl(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        addonName: String?,
        streamName: String?,
        failedUrl: String
    ): String?
}
