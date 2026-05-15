package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDebridStreamFilterTest {
    @Test
    fun `keeps only cached supported debrid streams and labels source as instant`() {
        val cachedTorbox = stream(
            name = "Direct 1080p",
            resolve = resolve(type = "debrid", service = "torbox", isCached = true)
        )
        val uncachedTorbox = stream(
            resolve = resolve(type = "debrid", service = "torbox", isCached = false)
        )
        val genericTorrent = stream(
            resolve = resolve(type = "torrent", service = null, isCached = null)
        )
        val unsupportedDebrid = stream(
            resolve = resolve(type = "debrid", service = "futurebox", isCached = true)
        )

        val result = DirectDebridStreamFilter.filterInstant(
            listOf(cachedTorbox, uncachedTorbox, genericTorrent, unsupportedDebrid)
        )

        assertEquals(1, result.size)
        assertEquals("Direct 1080p", result.single().name)
        assertEquals("Torbox Instant", result.single().addonName)
        assertTrue(result.single().isDirectDebrid())
        assertFalse(result.single().isTorrent())
    }

    @Test
    fun `uses provider instant name when source stream has no name`() {
        val result = DirectDebridStreamFilter.filterInstant(
            listOf(stream(name = null, resolve = resolve(type = "debrid", service = "realdebrid", isCached = true)))
        )

        assertEquals("Real-Debrid Instant", result.single().name)
    }

    private fun stream(
        name: String? = "Stream",
        resolve: StreamClientResolve?
    ): Stream = Stream(
        name = name,
        title = "Title",
        description = "Description",
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Direct Debrid",
        addonLogo = null,
        clientResolve = resolve
    )

    private fun resolve(
        type: String?,
        service: String?,
        isCached: Boolean?
    ): StreamClientResolve = StreamClientResolve(
        type = type,
        infoHash = "abc",
        fileIdx = 1,
        magnetUri = "magnet:?xt=urn:btih:abc",
        sources = null,
        torrentName = "Torrent",
        filename = "video.mkv",
        mediaType = "movie",
        mediaId = "tt1",
        mediaOnlyId = "tt1",
        title = "Title",
        season = null,
        episode = null,
        service = service,
        serviceIndex = 0,
        serviceExtension = null,
        isCached = isCached
    )
}
