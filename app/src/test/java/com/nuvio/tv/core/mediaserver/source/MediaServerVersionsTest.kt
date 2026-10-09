package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.AggregatorServerFixtures
import com.nuvio.tv.core.mediaserver.FakeHttp
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.SlowResolveServerFixtures
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.assertNotNull
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.json
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.policy.MintFailurePolicy
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Servers whose library is filled by add-ons answer ONE title with SEVERAL versions - one per add-on result. Every one is
 * offered, told apart by the add-on's own words, and a pick plays THAT version. Recorded answers only (Mobile twin:
 * MediaServerVersionsTest; TV has no matched lane yet, so these run through the direct lane).
 */
class MediaServerVersionsTest {
    @After
    fun reset() {
        MediaServerItemRegistry.reset()
        MediaServerPlaybackSessions.reset()
    }

    private val requests = mutableListOf<String>()

    private fun rig(route: (String) -> String?): Pair<TestRig, String> {
        val rig = TestRig(http = FakeHttp { r ->
            requests += r.method + " " + r.url
            route(r.url)?.let { json(it) } ?: json("not found: ${r.url}", 404)
        })
        val e = entry()
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, StoredCredential("TOKEN-LIVE"))
        return rig to e.serverKey
    }

    private suspend fun open(rig: TestRig, itemId: String): List<com.nuvio.tv.domain.model.Stream> {
        val id = "ms:jellyfin:$M:$U:movie:$itemId"
        assertNotNull(MediaServerMetaSource(rig.store, rig.services).meta("movie", id))
        return MediaServerStreamSourceProvider(rig.store, rig.services).directStreams(id).flatMap { it.streams }
    }

    // ---- a standalone server: six results, six versions; the first carries the ITEM id ----

    private val manyItem = "7c8cf7256b5c4167abfbd0711fa6a20c"
    private fun manyRig() = rig { u -> if ("/Items/$manyItem" in u) SlowResolveServerFixtures.SR_ITEM_MANY_VERSIONS else null }

    @Test
    fun everyResultOfATitleIsOfferedAsItsOwnVersion() = runTest {
        val streams = open(manyRig().first, manyItem)
        assertEquals(6, streams.size)
        assertEquals(6, streams.map { it.url }.toSet().size)
    }

    @Test
    fun pickingTheFirstListedVersionAsksForThatVersionNotTheServersOwnChoice() = runTest {
        // recorded: MediaSourceId = the item id streamed the server's preferred result (2160p REMUX), not the listed 1080p BluRay
        val first = open(manyRig().first, manyItem).first()
        assertEquals("ms-deferred:jellyfin:$M:$U|$manyItem|8e5ca1f5b4d858debaad8a62a3667978", first.url)
    }

    @Test
    fun versionsThatProbeAlikeAreToldApartByTheAddOnsOwnWords() = runTest {
        val streams = open(manyRig().first, manyItem)
        val described = streams.associate { it.description to it.name }
        assertEquals("1080p · H.264", described["Server A · 1080p · BluRay x264 12 GB"])
        assertEquals("1080p · H.264", described["Server B · 1080p · WEB-DL H264 4.2 GB"])
        assertEquals("every version has its own description: ${streams.map { it.description }}", 6, streams.mapNotNull { it.description }.toSet().size)
    }

    // ---- an aggregator that resolves only on play: the item page lists placeholders, PlaybackInfo the versions ----

    private val lazyItem = "a1110100000000000cffffffff000000"
    private val noneItem = "a11101000000000006ffffffff000000"
    private fun lazyRig() = rig { u ->
        when {
            "/Items/$lazyItem/PlaybackInfo" in u -> AggregatorServerFixtures.AG_PLAYBACKINFO_LAZY
            "/Items/$lazyItem" in u -> AggregatorServerFixtures.AG_ITEM_LAZY
            "/Items/$noneItem/PlaybackInfo" in u -> AggregatorServerFixtures.AG_PLAYBACKINFO_NO_STREAMS
            "/Items/$noneItem" in u -> AggregatorServerFixtures.AG_ITEM_NO_STREAMS
            else -> null
        }
    }

    @Test
    fun placeholderVersionsAreNeverOfferedAndTheRealOnesComeFromPlaybackInfo() = runTest {
        val streams = open(lazyRig().first, lazyItem)
        assertEquals("${streams.map { it.name }}", 3, streams.size)
        assertTrue(streams.none { it.name.orEmpty().contains("Load versions") })
        assertEquals("[Service A] Release 2160p · Release.2160p.WEB.DL.HEVC.15.GB.mp4 · 💾1.18 MiB", streams.first().description)
        assertTrue("the versions were asked for: $requests", requests.any { "/Items/$lazyItem/PlaybackInfo" in it })
    }

    @Test
    fun aPlaceholderIsNeverMintedIntoAPlayUrl() = runTest {
        val (rig, key) = lazyRig()
        val reasons = mutableListOf<MintFailurePolicy.Reason>()
        val provider = MediaServerStreamSourceProvider(rig.store, rig.services, onMintFailure = { reasons += it })
        assertNull(provider.resolveDeferredUrl(MediaServerIds.deferredUrl(key, noneItem, null), forceMint = false))
        assertEquals(listOf(MintFailurePolicy.noPlayableSource), reasons)
    }
}
