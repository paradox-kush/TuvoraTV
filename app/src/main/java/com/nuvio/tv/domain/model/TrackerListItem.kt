package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Unified view of a tracker library entry (MyAnimeList / AniList / Kitsu).
 * Carries the [MetaPreview] used for home-row rendering plus tracker-native
 * bookkeeping so that later progress writes don't need to re-resolve IDs.
 */
@Immutable
data class TrackerListItem(
    val source: TrackerSource,
    /**
     * Tracker-native entry id — MAL anime id, AniList media id, or Kitsu
     * library-entry id (note: Kitsu's id is the library-entry id, not the
     * anime id; the anime id is in [animeId]).
     */
    val entryId: String,
    /** Tracker-native anime id (same as [entryId] for MAL/AniList). */
    val animeId: String,
    val status: TrackerListStatus,
    val progress: Int,
    val totalEpisodes: Int?,
    /**
     * [MetaPreview] with id populated as `"tt{imdb}"` when cross-reference is
     * available, else `"tmdb:{id}"`, else a source-prefixed fallback id like
     * `"mal:{id}"` (the fallback will fail to open the NuvioTV detail screen
     * — surface it to the user with a toast when tapped).
     */
    val preview: MetaPreview,
    /** IMDb id when successfully cross-referenced (often `null` for anime). */
    val imdbId: String? = null,
    /** TMDB id when successfully cross-referenced. */
    val tmdbId: Int? = null,
    /** Whether the detail screen can be opened for this item. */
    val canOpenDetail: Boolean = imdbId != null || tmdbId != null
) {
    enum class TrackerSource { MAL, ANILIST, KITSU }
}
