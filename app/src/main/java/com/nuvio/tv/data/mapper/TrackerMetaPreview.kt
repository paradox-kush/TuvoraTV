package com.nuvio.tv.data.mapper

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TrackerListItem

/**
 * Shared helpers for building [MetaPreview] instances from tracker DTOs.
 * Each tracker-specific mapper ([MalListToMetaMapper], [AniListListToMetaMapper],
 * [KitsuListToMetaMapper]) resolves IDs first, then calls into here to
 * assemble the preview with a consistent id convention:
 *
 *   1. Prefer `tt{imdbDigits}` — this is the Stremio canonical id and will
 *      resolve against any TMDB/Cinemeta-compatible addon.
 *   2. Else `tmdb:{id}` — works for TMDB-only addons.
 *   3. Else `{source}:{id}` (e.g., `mal:16498`) — tapping will fail to open
 *      a detail screen; callers are expected to mark [TrackerListItem.canOpenDetail]
 *      false so the UI can surface that.
 */
object TrackerMetaPreviewBuilder {

    fun preview(
        fallbackIdPrefix: String,
        fallbackId: String,
        title: String,
        posterUrl: String?,
        contentType: ContentType,
        imdbId: String?,
        tmdbId: Int?,
        totalEpisodes: Int?,
        year: Int?
    ): MetaPreview {
        val previewId = buildPreviewId(imdbId, tmdbId, fallbackIdPrefix, fallbackId)
        val releaseInfo = year?.toString()
        val runtime = totalEpisodes?.takeIf { it > 0 }?.let { "$it ep" }
        return MetaPreview(
            id = previewId,
            type = contentType,
            rawType = contentType.toApiString(),
            name = title,
            poster = posterUrl,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = releaseInfo,
            imdbRating = null,
            genres = emptyList(),
            runtime = runtime,
            imdbId = imdbId,
            rawPosterUrl = posterUrl
        )
    }

    fun buildPreviewId(imdbId: String?, tmdbId: Int?, fallbackPrefix: String, fallbackId: String): String {
        imdbId?.let { return it.trim() }
        tmdbId?.let { return "tmdb:$it" }
        return "$fallbackPrefix:$fallbackId"
    }

    /** Map MAL `media_type` → [ContentType]. */
    fun contentTypeFromMal(mediaType: String?): ContentType = when (mediaType?.lowercase()) {
        "movie" -> ContentType.MOVIE
        "tv", "tv_short", "ova", "ona", "special", "music", "unknown", null -> ContentType.SERIES
        else -> ContentType.SERIES
    }

    /** Map AniList `format` → [ContentType]. */
    fun contentTypeFromAniList(format: String?): ContentType = when (format?.uppercase()) {
        "MOVIE" -> ContentType.MOVIE
        "TV", "TV_SHORT", "OVA", "ONA", "SPECIAL", "MUSIC" -> ContentType.SERIES
        else -> ContentType.SERIES
    }

    /** Map Kitsu `subtype` → [ContentType]. */
    fun contentTypeFromKitsu(subtype: String?): ContentType = when (subtype?.lowercase()) {
        "movie" -> ContentType.MOVIE
        "tv", "ova", "ona", "special", "music" -> ContentType.SERIES
        else -> ContentType.SERIES
    }
}
