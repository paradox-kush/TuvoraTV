package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto
import com.nuvio.tv.core.mediaserver.client.mediabrowser.PersonDto
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.item
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds.Kind
import com.nuvio.tv.core.mediaserver.source
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.nuvio.tv.core.mediaserver.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerItemMapperTest {
    private val jf = entry(address = "http://nas:8096")
    private val emby = entry(type = MediaServerType.EMBY, address = "http://nas:8096")

    @Test
    fun kindsAndPreviewTypes() {
        assertEquals(Kind.MOVIE, MediaServerItemMapper.kindOf("Movie")); assertEquals(Kind.SERIES, MediaServerItemMapper.kindOf("Series"))
        assertEquals(Kind.EPISODE, MediaServerItemMapper.kindOf("Episode")); assertEquals(Kind.SEASON, MediaServerItemMapper.kindOf("Season"))
        assertNull(MediaServerItemMapper.kindOf("MusicAlbum")); assertNull(MediaServerItemMapper.kindOf(null))
        assertEquals("movie", MediaServerItemMapper.previewType(Kind.MOVIE)); assertEquals("series", MediaServerItemMapper.previewType(Kind.SERIES))
    }

    @Test
    fun aMovieCardCarriesTheContentIdTokenlessImagesAndMetadata() {
        val dto = item("m1", "The Matrix") { it.copy(productionYear = 1999, communityRating = 8.7, genres = listOf("Sci-Fi"), overview = "Neo.", premiereDate = "1999-03-31T00:00:00.0000000Z", backdropImageTags = listOf("btag")) }
        val p = assertNotNull(MediaServerItemMapper.preview(jf, dto))
        assertEquals("ms:jellyfin:$M:$U:movie:m1", p.id)
        assertEquals("movie", p.rawType); assertEquals("The Matrix", p.name)
        assertEquals("http://nas:8096/Items/m1/Images/Primary?tag=ptag&maxWidth=400&quality=90", p.poster)
        assertEquals("http://nas:8096/Items/m1/Images/Backdrop?tag=btag&maxWidth=1280&quality=90", p.background)
        assertFalse("an image URL never carries a credential (it ends up in Coil's disk cache journal)", p.poster!!.contains("key", ignoreCase = true) || p.background!!.contains("key", ignoreCase = true))
        assertEquals("1999", p.releaseInfo); assertEquals(8.7f, p.imdbRating); assertEquals(listOf("Sci-Fi"), p.genres); assertEquals("Neo.", p.description)
    }

    @Test
    fun embyCardsUseTheSameCredentialFreeImageUrlsAndAnItemWithoutArtworkHasNoPoster() {
        val p = assertNotNull(MediaServerItemMapper.preview(emby, item("10", "Test Movie")))
        assertEquals("http://nas:8096/Items/10/Images/Primary?tag=ptag&maxWidth=400&quality=90", p.poster)
        assertTrue(p.id.startsWith("ms:emby:"))
        // Emby answers an image request for an item with no image with a 500: no tag = no URL, a placeholder instead
        val bare = assertNotNull(MediaServerItemMapper.preview(emby, item("12", "Long Movie", tags = emptyMap())))
        assertNull(bare.poster)
    }

    @Test
    fun anEpisodeOnAShelfIsTheSeriesCardWithTheEpisodeAsItsNote() {
        val ep = item("ep1", "Pilot", "Episode") { it.copy(seriesId = "ser1", seriesName = "Severance", seriesPrimaryImageTag = "stag", parentIndexNumber = 1, indexNumber = 2) }
        val p = assertNotNull(MediaServerItemMapper.preview(jf, ep))
        assertEquals("the card opens the series page", "ms:jellyfin:$M:$U:series:ser1", p.id)
        assertEquals("Severance", p.name); assertEquals("series", p.rawType)
        assertEquals("S1:E2 · Pilot", p.description)
        assertEquals("http://nas:8096/Items/ser1/Images/Primary?tag=stag&maxWidth=400&quality=90", p.poster)
        assertNull("an episode without a series has no card", MediaServerItemMapper.preview(jf, item("ep2", "x", "Episode")))
    }

    @Test
    fun unsupportedTypesAndSeasonsHaveNoCard() {
        assertNull(MediaServerItemMapper.preview(jf, item("a", type = "MusicAlbum")))
        assertNull(MediaServerItemMapper.preview(jf, item("s", type = "Season")))
        assertNull(MediaServerItemMapper.preview(jf, ItemDto(type = "Movie", name = "no id")))
    }

    @Test
    fun aBackdropFallsBackToTheParents() {
        val dto = item("e", type = "Movie") { it.copy(parentBackdropItemId = "par", parentBackdropImageTags = listOf("pbt")) }
        assertEquals("http://nas:8096/Items/par/Images/Backdrop?tag=pbt&maxWidth=1280&quality=90", MediaServerItemMapper.preview(jf, dto)!!.background)
        assertNull(MediaServerItemMapper.preview(jf, item("e"))!!.background)
    }

    @Test
    fun seriesDetailsCarryEpisodesWithoutEmbeddedStreams() {
        val series = item("ser1", "Severance", "Series") {
            it.copy(
                productionYear = 2022, officialRating = "TV-MA", overview = "Work.", status = "Continuing", runTimeTicks = 30_000_000_000,
                providerIds = mapOf("Imdb" to "tt11280740", "Tmdb" to "95396"),
                people = listOf(PersonDto("Ben Stiller", "d1", null, "Director"), PersonDto("Adam Scott", "a1", "Mark", "Actor", "ptag")),
                imageTags = mapOf("Primary" to "ptag", "Logo" to "ltag"),
            )
        }
        val eps = listOf(
            item("e1", "Good News", "Episode") { it.copy(indexNumber = 1, parentIndexNumber = 1, runTimeTicks = 30_000_000_000, overview = "o") },
            item("e2", "Half Loop", "Episode") { it.copy(indexNumber = 2, parentIndexNumber = 1) },
            item("x", "stray", "Movie"),
        )
        val meta = assertNotNull(MediaServerItemMapper.details(jf, series, eps))
        assertEquals("ms:jellyfin:$M:$U:series:ser1", meta.id)
        assertEquals("tt11280740", meta.imdbId); assertEquals("TV-MA", meta.ageRating); assertEquals("2022", meta.releaseInfo)
        assertEquals("50", meta.runtime); assertEquals(listOf("Ben Stiller"), meta.director)
        assertEquals(listOf("Adam Scott"), meta.cast); assertEquals("Mark", meta.castMembers.single().character)
        assertNotNull(meta.logo)
        assertEquals(listOf("ms:jellyfin:$M:$U:episode:e1", "ms:jellyfin:$M:$U:episode:e2"), meta.videos.map { it.id })
        assertTrue("episodes resolve through the direct lane, never as embedded streams", meta.videos.all { it.streams.isEmpty() })
        assertEquals(1, meta.videos.first().season); assertEquals(50, meta.videos.first().runtime)
    }

    @Test
    fun aMovieDetailHasNoEpisodesAndAnEpisodeIdHasNoDetailPage() {
        val meta = assertNotNull(MediaServerItemMapper.details(jf, item("m1", "M"), emptyList()))
        assertTrue(meta.videos.isEmpty())
        assertNull(MediaServerItemMapper.details(jf, item("e1", type = "Episode"), emptyList()))
    }

    @Test
    fun theRegistryRecordHoldsSourcesAndTheServersResumePosition() {
        val dto = item("m1", "M", sources = listOf(source("a", height = 2160, codec = "hevc", size = 12_400_000_000), source("b", height = 720, codec = "h264", size = 900_000_000))) {
            it.copy(runTimeTicks = 72_000_000_000, userData = com.nuvio.tv.core.mediaserver.client.mediabrowser.UserDataDto(playbackPositionTicks = 18_000_000_000, lastPlayedDate = "2026-10-01T10:00:00Z"))
        }
        val r = assertNotNull(MediaServerItemMapper.registered(jf, dto))
        assertEquals("ms:jellyfin:$M:$U:movie:m1", r.contentId)
        assertEquals(listOf("a", "b"), r.sources.map { it.id })
        assertEquals("4K · HEVC · 11.5 GB", r.sources[0].label)
        assertEquals("720p · H.264 · 858 MB", r.sources[1].label)
        assertEquals(7_200_000L, r.durationMs); assertEquals(1_800_000L, r.serverPositionMs)
        assertEquals("2026-10-01T10:00:00Z", r.serverLastPlayedAt)
        assertNull(MediaServerItemMapper.registered(jf, item("a", type = "MusicAlbum")))
    }

    @Test
    fun aZeroPositionIsNoResumePosition() {
        val dto = item("m1") { it.copy(userData = com.nuvio.tv.core.mediaserver.client.mediabrowser.UserDataDto(playbackPositionTicks = 0)) }
        assertNull(MediaServerItemMapper.registered(jf, dto)!!.serverPositionMs)
    }

    @Test
    fun oneDecimalAndSizeLabels() {
        assertEquals("7.8", MediaServerItemMapper.oneDecimal(7.84)); assertEquals("8.0", MediaServerItemMapper.oneDecimal(7.96)); assertEquals("0.5", MediaServerItemMapper.oneDecimal(0.5))
        assertEquals("1.0 GB", MediaServerItemMapper.sizeLabel(1_073_741_824)); assertEquals("512 MB", MediaServerItemMapper.sizeLabel(536_870_912))
    }
}
