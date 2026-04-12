package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.anime.AnimeIdMapper
import com.nuvio.tv.data.remote.dto.anilist.AniListMediaListEntryDto
import com.nuvio.tv.domain.model.TrackerListItem
import com.nuvio.tv.domain.model.TrackerListStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps AniList list entries → [TrackerListItem]. AniList returns [idMal]
 * inline in some cases, so we try that before falling back to `arm`. Parallel
 * lookups per entry as with [MalListToMetaMapper].
 */
@Singleton
class AniListListToMetaMapper @Inject constructor(
    private val animeIdMapper: AnimeIdMapper
) {
    suspend fun map(entries: List<AniListMediaListEntryDto>): List<TrackerListItem> = coroutineScope {
        entries.map { entry -> async { mapOne(entry) } }
            .mapNotNull { it.await() }
    }

    private suspend fun mapOne(entry: AniListMediaListEntryDto): TrackerListItem? {
        val status = TrackerListStatus.fromAniList(entry.status) ?: return null
        val media = entry.media ?: return null
        val arm = animeIdMapper.resolveFromTracker(AnimeIdMapper.TrackerSource.ANILIST, media.id)
        val contentType = TrackerMetaPreviewBuilder.contentTypeFromAniList(media.format)
        val title = media.title?.english?.takeIf { it.isNotBlank() }
            ?: media.title?.userPreferred
            ?: media.title?.romaji
            ?: media.title?.native
            ?: "Unknown"
        val poster = media.coverImage?.extraLarge ?: media.coverImage?.large ?: media.coverImage?.medium
        val imdb = arm?.imdb
        val tmdb = arm?.themoviedb
        val preview = TrackerMetaPreviewBuilder.preview(
            fallbackIdPrefix = "anilist",
            fallbackId = media.id.toString(),
            title = title,
            posterUrl = poster,
            contentType = contentType,
            imdbId = imdb,
            tmdbId = tmdb,
            totalEpisodes = media.episodes?.takeIf { it > 0 },
            year = media.startDate?.year
        )
        return TrackerListItem(
            source = TrackerListItem.TrackerSource.ANILIST,
            entryId = entry.id.toString(),
            animeId = media.id.toString(),
            status = status,
            progress = entry.progress,
            totalEpisodes = media.episodes?.takeIf { it > 0 },
            preview = preview,
            imdbId = imdb,
            tmdbId = tmdb
        )
    }
}
