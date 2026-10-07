package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class PlaybackAvailabilityTest {
    // Which ids are "own sources" is registered policy now (media-servers design 5.1): wire IPTV the way
    // NuvioApplication does, and put the process back as found.
    @Before
    fun wireIptv() {
        com.nuvio.tv.core.contracts.OwnSourcePolicy.resetForTest()
        com.nuvio.tv.core.iptv.IptvSourceRegistrations.register()
    }

    @After
    fun unwireIptv() = com.nuvio.tv.core.contracts.OwnSourcePolicy.resetForTest()

    @Test
    fun `no sources or metadata-only addons cannot play`() {
        assertFalse(PlaybackAvailability().canStream("movie", "tt123"))
        val metaAddon = addon().copy(resources = listOf(AddonResource("meta", listOf("movie"), null)))
        assertFalse(PlaybackAvailability(addons = listOf(metaAddon)).canStream("movie", "tt123"))
    }

    @Test
    fun `iptv items are playable with no addons scrapers or cached meta`() {
        // Regression (2026-09-27): upstream's play-disable check greyed out Play/Resume for every
        // Xtream/Stalker/M3U item for IPTV-only users (store builds hide addons). The IPTV resolver
        // plays these ids directly, so they never need an addon or scraper.
        val none = PlaybackAvailability(isLoaded = true)
        assertTrue("xtream vod", none.canStream("movie", "xtream:http://p|u:vod:42"))
        assertTrue("xtream episode", none.canStream("series", "xtream:http://p|u:episode:7", "xtream:http://p|u:series:3"))
        assertTrue("m3u", none.canStream("movie", "m3u:abc"))
        assertTrue("series content id carries the iptv prefix", none.canStream("series", "7", "xtream:http://p|u:series:3"))
    }

    @Test
    fun `iptv source lane counts as a source only for the content types it serves`() {
        // An IPTV account with movies enabled can supply streams for addon-catalog titles (tt/tmdb
        // ids) through the Xtream source lane, the same way a scraper supporting "movie" does.
        val moviesOnly = PlaybackAvailability(isLoaded = true, iptvSourceTypes = setOf("movie"))
        assertTrue("movie via iptv lane", moviesOnly.canStream("movie", "tt123"))
        assertFalse("series not served", moviesOnly.canStream("series", "tt123:1:1"))
        assertFalse("no iptv lane, no sources", PlaybackAvailability(isLoaded = true).canStream("movie", "tt123"))
    }

    @Test
    fun `addon must be enabled and match the type and video prefix`() {
        val available = PlaybackAvailability(addons = listOf(addon()))
        assertTrue(available.canStream("movie", "tt123"))
        assertFalse(available.canStream("series", "tt123:1:1"))
        assertFalse(available.canStream("movie", "tmdb:123"))
        assertFalse(available.copy(addons = listOf(addon().copy(enabled = false))).canStream("movie", "tt123"))
    }

    @Test
    fun `resource prefixes override addon prefixes and missing prefixes fall back`() {
        val configured = addon().copy(idPrefixes = listOf("tmdb:"))
        assertTrue(configured.supportsStreamResource("movie", "tt123"))
        assertFalse(configured.supportsStreamResource("movie", "tmdb:123"))
        for (prefixes in listOf<List<String>?>(null, emptyList())) {
            val fallback = configured.copy(resources = listOf(AddonResource("stream", emptyList(), prefixes)))
            assertTrue(fallback.supportsStreamResource("series", "tmdb:123:1:1"))
            assertFalse(fallback.supportsStreamResource("movie", "tt123"))
        }
    }

    @Test
    fun `unrestricted stream resources support custom content types`() {
        val unrestricted = addon().copy(resources = listOf(AddonResource("stream", emptyList(), null)))
        assertTrue(PlaybackAvailability(addons = listOf(unrestricted)).canStream("channel", "channel:1"))
    }

    @Test
    fun `plugins honor enabled state and existing type aliases for both runtimes`() {
        for (runtime in RepositoryType.entries) {
            val scraper = scraper().copy(type = runtime, supportedTypes = listOf("tv"))
            val available = PlaybackAvailability(scrapers = listOf(scraper))
            assertTrue(available.canStream("series", "tt123:1:1"))
            assertTrue(available.canStream("other", "channel:1"))
            assertFalse(available.canStream("movie", "tt123"))
            assertFalse(available.copy(scrapers = listOf(scraper.copy(enabled = false))).canStream("series", "tt123:1:1"))
        }
    }

    @Test
    fun `embedded streams allow only the matching video without installed sources`() {
        val video = video()
        val available = PlaybackAvailability()
        assertTrue(available.canStream("other", video.id, video = video))
        assertFalse(available.canStream("other", "different-video", video = video))
        assertFalse(available.canStream("other", video.id, video = video.copy(streams = emptyList())))
    }

    @Test
    fun `continue watching can use embedded streams from cached parent metadata`() {
        val video = video()
        val meta = mockk<Meta>()
        every { meta.videos } returns listOf(video)
        val available = PlaybackAvailability(cachedMeta = { type, id ->
            meta.takeIf { type == "other" && id == "parent" }
        })
        assertTrue(available.canStream("other", video.id, "parent"))
        assertFalse(available.canStream("other", "different-video", "parent"))
        assertFalse(available.canStream("other", video.id, "uncached-parent"))
    }

    private fun addon() = Addon(
        id = "addon", name = "Addon", version = "1", description = null, logo = null,
        baseUrl = "https://example.com", catalogs = emptyList(), types = emptyList(),
        resources = listOf(AddonResource("stream", listOf("movie"), listOf("tt")))
    )

    private fun scraper() = ScraperInfo(
        id = "scraper", name = "Scraper", description = "", version = "1", filename = "scraper.js",
        supportedTypes = listOf("movie"), enabled = true, manifestEnabled = true, logo = null,
        contentLanguage = emptyList(), repositoryId = "repo", formats = null
    )

    private fun video() = Video(
        id = "embedded-video", title = "Video", released = null, thumbnail = null,
        streams = listOf(mockk<Stream>()), season = null, episode = null, overview = null
    )
}
