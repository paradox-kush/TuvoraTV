package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store builds keep add-ons for discovery (catalogs, metadata, subtitles) but never use them as
 * playback sources: add-ons sync onto store devices from tuvora.co / full builds, so without this
 * gate a Play Store build silently plays streams from synced add-ons.
 */
class AddonSourcePolicyTest {

    private val movieSeries = listOf("movie", "series")

    private fun addon(vararg resourceNames: String) = Addon(
        id = "org.example.addon",
        name = "Example",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://addon.example.com",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        resources = resourceNames.map { AddonResource(name = it, types = movieSeries, idPrefixes = null) },
        idPrefixes = listOf("tt")
    )

    private fun stream(name: String) = Stream(
        name = name,
        title = name,
        description = null,
        url = "https://cdn.example.com/$name.mkv",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = name,
        addonLogo = null
    )

    @Test
    fun `store build strips the stream resource and keeps discovery resources`() {
        val manifest = addon("catalog", "meta", "stream", "subtitles")

        val store = AddonSourcePolicy.manifestForBuild(manifest, streamSourcesEnabled = false)

        assertEquals(
            "catalog, meta and subtitles survive; stream is dropped",
            listOf("catalog", "meta", "subtitles"),
            store.resources.map { it.name }
        )
        assertFalse(
            "a store-build add-on never claims to serve streams",
            store.supportsStreamResource("movie", "tt0111161")
        )
        assertTrue("the original manifest did serve streams", manifest.supportsStreamResource("movie", "tt0111161"))
    }

    @Test
    fun `full build returns the same manifest instance`() {
        val manifest = addon("catalog", "meta", "stream", "subtitles")

        assertSame(
            "full builds must not touch the manifest",
            manifest,
            AddonSourcePolicy.manifestForBuild(manifest, streamSourcesEnabled = true)
        )
    }

    @Test
    fun `store build returns the same instance when there is no stream resource`() {
        val manifest = addon("catalog", "meta", "subtitles")

        assertSame(
            "nothing to strip means no copy",
            manifest,
            AddonSourcePolicy.manifestForBuild(manifest, streamSourcesEnabled = false)
        )
    }

    @Test
    fun `store build drops add-on embedded streams but keeps IPTV ones`() {
        val streams = listOf(stream("a"), stream("b"))

        assertEquals(
            "add-on meta streams are not playable in store builds",
            emptyList<Stream>(),
            AddonSourcePolicy.embeddedStreamsForBuild(streams, streamSourcesEnabled = false, isIptv = false)
        )
        assertEquals(
            "IPTV embedded streams always survive",
            streams,
            AddonSourcePolicy.embeddedStreamsForBuild(streams, streamSourcesEnabled = false, isIptv = true)
        )
        assertEquals(
            "full builds keep add-on embedded streams",
            streams,
            AddonSourcePolicy.embeddedStreamsForBuild(streams, streamSourcesEnabled = true, isIptv = false)
        )
    }

    @Test
    fun `store build never replays a cached add-on link but still replays IPTV`() {
        assertFalse(
            "cached add-on (or legacy untagged) link is rejected in store builds",
            AddonSourcePolicy.cachedLinkUsable(streamSourcesEnabled = false, isIptv = false)
        )
        assertTrue(
            "cached IPTV link is still reused in store builds",
            AddonSourcePolicy.cachedLinkUsable(streamSourcesEnabled = false, isIptv = true)
        )
        assertTrue(
            "full builds reuse any cached link",
            AddonSourcePolicy.cachedLinkUsable(streamSourcesEnabled = true, isIptv = false)
        )
    }
}
