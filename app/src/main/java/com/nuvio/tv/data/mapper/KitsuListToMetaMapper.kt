package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.anime.AnimeIdMapper
import com.nuvio.tv.data.remote.dto.kitsu.KitsuIncludedDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryEntryDto
import com.nuvio.tv.data.remote.dto.kitsu.KitsuLibraryPageDto
import com.nuvio.tv.domain.model.TrackerListItem
import com.nuvio.tv.domain.model.TrackerListStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a Kitsu JSON:API library page → [TrackerListItem]s. The mapper resolves
 * `included` records (anime + mappings) inline — Kitsu has first-class
 * `mappings` relationships so we can often skip the `arm` round trip:
 * `mappings` contains entries like `{ externalSite: "themoviedb/show", externalId: "1429" }`.
 * When a TMDB mapping is present we use it directly; otherwise fall back to arm.
 */
@Singleton
class KitsuListToMetaMapper @Inject constructor(
    private val animeIdMapper: AnimeIdMapper
) {
    suspend fun map(page: KitsuLibraryPageDto): List<TrackerListItem> = coroutineScope {
        val includedByKey: Map<String, KitsuIncludedDto> = page.included.associateBy { "${it.type}:${it.id}" }

        page.data.map { entry -> async { mapOne(entry, includedByKey) } }
            .mapNotNull { it.await() }
    }

    private suspend fun mapOne(
        entry: KitsuLibraryEntryDto,
        included: Map<String, KitsuIncludedDto>
    ): TrackerListItem? {
        val attrs = entry.attributes ?: return null
        val status = TrackerListStatus.fromKitsu(attrs.status) ?: return null
        val animeRef = entry.relationships?.anime?.data ?: return null
        val anime = included["${animeRef.type}:${animeRef.id}"] ?: return null
        val animeAttrs = anime.attributes ?: return null

        // Collect inline mappings (if any) — Kitsu surfaces cross-site IDs directly.
        val mappingRefs = anime.relationships?.get("mappings")?.data.orEmpty()
        var inlineImdb: String? = null
        var inlineTmdb: Int? = null
        var inlineMal: Int? = null
        var inlineAnilist: Int? = null
        for (ref in mappingRefs) {
            val m = included["${ref.type}:${ref.id}"] ?: continue
            val site = m.attributes?.externalSite ?: continue
            val value = m.attributes.externalId ?: continue
            when (site.lowercase()) {
                "myanimelist/anime" -> inlineMal = value.toIntOrNull()
                "anilist/anime" -> inlineAnilist = value.toIntOrNull()
                "themoviedb/show", "themoviedb/movie" -> inlineTmdb = value.toIntOrNull()
                "imdb" -> if (inlineImdb == null) inlineImdb = value
            }
        }

        // Fall back to arm only when inline mappings lacked IMDb/TMDB.
        val arm = if (inlineImdb == null && inlineTmdb == null) {
            animeIdMapper.resolveFromTracker(AnimeIdMapper.TrackerSource.KITSU, animeRef.id.toIntOrNull() ?: 0)
        } else null
        val imdb = inlineImdb ?: arm?.imdb
        val tmdb = inlineTmdb ?: arm?.themoviedb

        val contentType = TrackerMetaPreviewBuilder.contentTypeFromKitsu(animeAttrs.subtype)
        val title = animeAttrs.titles?.get("en")?.takeIf { it.isNotBlank() }
            ?: animeAttrs.titles?.get("en_jp")
            ?: animeAttrs.canonicalTitle
            ?: "Unknown"
        val poster = animeAttrs.posterImage?.large
            ?: animeAttrs.posterImage?.medium
            ?: animeAttrs.posterImage?.original
        val year = animeAttrs.startDate?.take(4)?.toIntOrNull()

        val preview = TrackerMetaPreviewBuilder.preview(
            fallbackIdPrefix = "kitsu",
            fallbackId = animeRef.id,
            title = title,
            posterUrl = poster,
            contentType = contentType,
            imdbId = imdb,
            tmdbId = tmdb,
            totalEpisodes = animeAttrs.episodeCount?.takeIf { it > 0 },
            year = year
        )
        return TrackerListItem(
            source = TrackerListItem.TrackerSource.KITSU,
            entryId = entry.id,
            animeId = animeRef.id,
            status = status,
            progress = attrs.progress,
            totalEpisodes = animeAttrs.episodeCount?.takeIf { it > 0 },
            preview = preview,
            imdbId = imdb,
            tmdbId = tmdb
        )
    }
}
