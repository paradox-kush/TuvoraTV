package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.anime.AnimeIdMapper
import com.nuvio.tv.data.remote.dto.mal.MalListEntryDto
import com.nuvio.tv.domain.model.TrackerListItem
import com.nuvio.tv.domain.model.TrackerListStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps [MalListEntryDto] → [TrackerListItem]. Each entry requires a round trip
 * through `arm` to cross-reference the MAL id to IMDb/TMDB. The mapper issues
 * these lookups in parallel across all entries and drops any that come back
 * unresolved — the caller's memoisation (in [AnimeIdMapper]) means subsequent
 * calls for the same id are effectively free.
 */
@Singleton
class MalListToMetaMapper @Inject constructor(
    private val animeIdMapper: AnimeIdMapper
) {
    suspend fun map(entries: List<MalListEntryDto>): List<TrackerListItem> = coroutineScope {
        entries.map { entry -> async { mapOne(entry) } }
            .mapNotNull { it.await() }
    }

    private suspend fun mapOne(entry: MalListEntryDto): TrackerListItem? {
        val status = TrackerListStatus.fromMal(entry.listStatus?.status) ?: return null
        val node = entry.node
        val arm = animeIdMapper.resolveFromTracker(AnimeIdMapper.TrackerSource.MAL, node.id)
        val contentType = TrackerMetaPreviewBuilder.contentTypeFromMal(node.mediaType)
        val title = node.alternativeTitles?.english?.takeIf { it.isNotBlank() }
            ?: node.title
        val poster = node.mainPicture?.large ?: node.mainPicture?.medium
        val preview = TrackerMetaPreviewBuilder.preview(
            fallbackIdPrefix = "mal",
            fallbackId = node.id.toString(),
            title = title,
            posterUrl = poster,
            contentType = contentType,
            imdbId = arm?.imdb,
            tmdbId = arm?.themoviedb,
            totalEpisodes = node.numEpisodes?.takeIf { it > 0 },
            year = node.startSeason?.year
        )
        return TrackerListItem(
            source = TrackerListItem.TrackerSource.MAL,
            entryId = node.id.toString(),
            animeId = node.id.toString(),
            status = status,
            progress = entry.listStatus?.numEpisodesWatched ?: 0,
            totalEpisodes = node.numEpisodes?.takeIf { it > 0 },
            preview = preview,
            imdbId = arm?.imdb,
            tmdbId = arm?.themoviedb
        )
    }
}
