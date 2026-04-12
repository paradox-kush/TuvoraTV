package com.nuvio.tv.data.mapper

import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Covers the `MetaPreview.id` fallback chain — which is load-bearing because
 * a wrong id means tapping a tracker-sourced home row opens the wrong detail
 * page or no detail at all.
 *
 *   1. prefer `tt…` IMDb id (Stremio canonical) when available
 *   2. else `tmdb:{id}`
 *   3. else `{source}:{id}` (won't resolve; UI should disable tap)
 */
class TrackerMetaPreviewBuilderTest {

    @Test
    fun `id prefers IMDb over everything else`() {
        val id = TrackerMetaPreviewBuilder.buildPreviewId(
            imdbId = "tt0388629",
            tmdbId = 37854,
            fallbackPrefix = "mal",
            fallbackId = "21"
        )
        assertEquals("tt0388629", id)
    }

    @Test
    fun `id trims IMDb whitespace`() {
        val id = TrackerMetaPreviewBuilder.buildPreviewId(
            imdbId = "  tt0944947  ",
            tmdbId = null,
            fallbackPrefix = "mal",
            fallbackId = "1"
        )
        assertEquals("tt0944947", id)
    }

    @Test
    fun `id falls back to tmdb when imdb absent`() {
        val id = TrackerMetaPreviewBuilder.buildPreviewId(
            imdbId = null,
            tmdbId = 1429,
            fallbackPrefix = "anilist",
            fallbackId = "16498"
        )
        assertEquals("tmdb:1429", id)
    }

    @Test
    fun `id uses source-prefixed fallback only when no cross-reference exists`() {
        val id = TrackerMetaPreviewBuilder.buildPreviewId(
            imdbId = null,
            tmdbId = null,
            fallbackPrefix = "kitsu",
            fallbackId = "7442"
        )
        assertEquals("kitsu:7442", id)
    }

    @Test
    fun `MAL media_type maps movies and everything-else to SERIES`() {
        assertEquals(ContentType.MOVIE, TrackerMetaPreviewBuilder.contentTypeFromMal("movie"))
        assertEquals(ContentType.SERIES, TrackerMetaPreviewBuilder.contentTypeFromMal("tv"))
        assertEquals(ContentType.SERIES, TrackerMetaPreviewBuilder.contentTypeFromMal("ova"))
        assertEquals(ContentType.SERIES, TrackerMetaPreviewBuilder.contentTypeFromMal("ona"))
        assertEquals(ContentType.SERIES, TrackerMetaPreviewBuilder.contentTypeFromMal("special"))
        assertEquals(ContentType.SERIES, TrackerMetaPreviewBuilder.contentTypeFromMal(null))
        assertEquals(ContentType.SERIES, TrackerMetaPreviewBuilder.contentTypeFromMal("weird_unknown_type"))
    }

    @Test
    fun `AniList format maps MOVIE to MOVIE and others to SERIES`() {
        assertEquals(ContentType.MOVIE, TrackerMetaPreviewBuilder.contentTypeFromAniList("MOVIE"))
        assertEquals(ContentType.SERIES, TrackerMetaPreviewBuilder.contentTypeFromAniList("TV"))
        assertEquals(ContentType.SERIES, TrackerMetaPreviewBuilder.contentTypeFromAniList("TV_SHORT"))
        assertEquals(ContentType.SERIES, TrackerMetaPreviewBuilder.contentTypeFromAniList(null))
    }

    @Test
    fun `preview assembles known fields without requiring cross-reference`() {
        val preview = TrackerMetaPreviewBuilder.preview(
            fallbackIdPrefix = "mal",
            fallbackId = "16498",
            title = "Attack on Titan",
            posterUrl = "https://example.com/aot.jpg",
            contentType = ContentType.SERIES,
            imdbId = "tt2560140",
            tmdbId = 1429,
            totalEpisodes = 25,
            year = 2013
        )
        assertEquals("tt2560140", preview.id)
        assertEquals("Attack on Titan", preview.name)
        assertEquals("https://example.com/aot.jpg", preview.poster)
        assertEquals("2013", preview.releaseInfo)
        assertEquals("25 ep", preview.runtime)
        assertEquals(ContentType.SERIES, preview.type)
        assertEquals("tt2560140", preview.imdbId)
        assertNotNull(preview.rawPosterUrl)
    }
}
