package com.nuvio.tv.core.anime

/**
 * A resolved cross-reference between a TMDB (season, episode) and the matching
 * tracker entries on MyAnimeList / AniList / Kitsu, including the tracker-native
 * episode number (which may differ from TMDB's per-season numbering — e.g. One
 * Piece uses absolute episode numbering on all three trackers).
 */
data class TrackerEpisodeMapping(
    val tmdbId: Int,
    val tmdbSeason: Int,
    val tmdbEpisode: Int,
    /** Matched tracker entry IDs — any may be null if this service has no entry for the show. */
    val malId: Int? = null,
    val anilistId: Int? = null,
    val kitsuId: Int? = null,
    val anidbId: Int? = null,
    /**
     * The episode number to write to the trackers. For most anime this equals
     * [tmdbEpisode] (each TMDB season is its own tracker entry so numbering
     * restarts). For absolute-numbered shows (One Piece, Naruto, Detective
     * Conan…) it is the absolute episode across all seasons on the tracker.
     */
    val trackerEpisode: Int,
    /**
     * Total episode count for the matched tracker entry, if known. Used to
     * decide when to flip status → completed.
     */
    val totalEpisodes: Int? = null,
    /** Where this mapping came from, for diagnostics/logging. */
    val source: MappingSource
) {
    val hasAnyTrackerId: Boolean
        get() = malId != null || anilistId != null || kitsuId != null

    enum class MappingSource { PLEX_ANI_BRIDGE, ARM, HEURISTIC_NONE }
}

/**
 * Result of resolving all tracker entries associated with a TMDB show (one
 * per matched anime season/cour). Used for "mark whole show watched" flows.
 */
data class TrackerShowMapping(
    val tmdbId: Int,
    val entries: List<TrackerShowEntry>,
    val source: TrackerEpisodeMapping.MappingSource
)

data class TrackerShowEntry(
    /** The TMDB season number that maps to this tracker entry, if known. */
    val tmdbSeason: Int? = null,
    val malId: Int? = null,
    val anilistId: Int? = null,
    val kitsuId: Int? = null,
    val anidbId: Int? = null,
    val totalEpisodes: Int? = null
)
