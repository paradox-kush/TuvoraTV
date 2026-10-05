package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.Subtitle

interface SubtitleRepository {
    /**
     * Fetches subtitles from all installed addons that support subtitles
     * @param type Content type (movie, series, etc.)
     * @param id Content ID (IMDB ID, etc.)
     * @param videoId Optional video ID for series (e.g., tt1234567:1:1 for series episode)
     * @param videoHash Optional OpenSubtitles file hash
     * @param videoSize Optional video file size in bytes
     * @param filename Optional video filename
     * @return List of subtitles from all addons
     */
    suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)? = null,
        onSubtitlesEmitted: ((List<Subtitle>) -> Unit)? = null,
        /** F17: an IPTV episode's season/episode, to build its public `tt…:S:E` id. */
        season: Int? = null,
        episode: Int? = null,
    ): List<Subtitle>
}
