package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.FakeClient
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.item
import com.nuvio.tv.core.mediaserver.source
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.nuvio.tv.core.mediaserver.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerMetaSourceTest {
    private val TYPE = "movie"

    @After
    fun reset() = MediaServerItemRegistry.reset()

    private val client = FakeClient()
    private fun rig() = TestRig(clientFactory = { client }).also { r ->
        val e = entry()
        r.store.applyFromRemote(1, listOf(e))
        r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }

    private fun id(kind: String, item: String) = "ms:jellyfin:$M:$U:$kind:$item"

    @Test
    fun aMovieBecomesNativeMetaAndRegistersItsSources() = runTest {
        val rig = rig()
        client.items["m1"] = item("m1", "The Matrix", sources = listOf(source("a"), source("b", height = 720))) { it.copy(productionYear = 1999) }
        val meta = assertNotNull(MediaServerMetaSource(rig.store, rig.services).meta(TYPE, id("movie", "m1")))
        assertEquals("The Matrix", meta.name); assertEquals(id("movie", "m1"), meta.id)
        assertEquals(listOf("a", "b"), MediaServerItemRegistry.get(id("movie", "m1"))!!.sources.map { it.id })
    }

    @Test
    fun aSeriesRegistersItsEpisodesSoTheyPlayThroughTheDirectLane() = runTest {
        val rig = rig()
        client.items["s1"] = item("s1", "Severance", "Series")
        client.episodesOf["s1"] = listOf(item("e1", "Good News", "Episode") { it.copy(indexNumber = 1, parentIndexNumber = 1) })
        val meta = assertNotNull(MediaServerMetaSource(rig.store, rig.services).meta(TYPE, id("series", "s1")))
        assertEquals(listOf(id("episode", "e1")), meta.videos.map { it.id })
        assertNotNull("the episode is registered for the direct lane", MediaServerItemRegistry.get(id("episode", "e1")))
        assertTrue(meta.videos.all { it.streams.isEmpty() })
    }

    @Test
    fun theLanesDoNotBuildPagesForWhatIsNotTheirs() = runTest {
        val rig = rig()
        val src = MediaServerMetaSource(rig.store, rig.services)
        assertTrue(src.handlesId(id("movie", "m1"))); assertFalse(src.handlesId("xtream:a|b:vod:1")); assertFalse(src.handlesId("tt0133093"))
        assertNull(src.meta(TYPE, "xtream:a|b:vod:1"))
        assertNull("an episode id has no page of its own", src.meta(TYPE, id("episode", "e1")))
        assertNull(src.meta(TYPE, id("movie", "missing")))
        assertNull("no entry for that user", src.meta(TYPE, "ms:jellyfin:$M:someone-else:movie:m1"))
    }

    @Test
    fun aRevokedTokenFailsQuietlyAndDropsTheSession() = runTest {
        val rig = rig()
        client.failWith = MediaServerException.Http(401)
        assertNull(MediaServerMetaSource(rig.store, rig.services).meta(TYPE, id("movie", "m1")))
        assertFalse(rig.services.isSignedIn(rig.store.current().single()))
    }

    @Test
    fun anOfflineServerIsNullNotACrash() = runTest {
        val rig = rig()
        client.failWith = MediaServerException.Unreachable("down")
        assertNull(MediaServerMetaSource(rig.store, rig.services).meta(TYPE, id("movie", "m1")))
        assertTrue(rig.services.isSignedIn(rig.store.current().single()))
    }

    @Test
    fun ensureStreamRegisteredRebuildsAColdStartMissWithOneFetch() = runTest {
        val rig = rig()
        client.items["m1"] = item("m1", "The Matrix", sources = listOf(source("a")))
        val src = MediaServerMetaSource(rig.store, rig.services)
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), forceFresh = false))
        assertEquals(1, client.itemRequests.size)
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), forceFresh = false))
        assertEquals("already registered: no second fetch", 1, client.itemRequests.size)
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), forceFresh = true))
        assertEquals(2, client.itemRequests.size)
        assertFalse(src.ensureStreamRegistered(id("movie", "gone"), false))
        assertFalse(src.ensureStreamRegistered("tt1", false))
    }

    @Test
    fun aCardRegisteredWithoutMediaSourcesIsRefetchedOnceForPlayback() = runTest {
        val rig = rig()
        client.items["m1"] = item("m1", "The Matrix", sources = listOf(source("a")))
        val src = MediaServerMetaSource(rig.store, rig.services)
        // a Home row / episode list registers cards from a light fetch: no MediaSources yet
        MediaServerItemRegistry.register(MediaServerItemRegistry.Item(id("movie", "m1"), "jellyfin:$M:$U", MediaServerIds.Kind.MOVIE, "m1", "The Matrix", null, emptyList(), null, null, null))
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), forceFresh = false))
        assertEquals("the sourceless record is completed with one fetch", 1, client.itemRequests.size)
        assertEquals(listOf("a"), MediaServerItemRegistry.get(id("movie", "m1"))!!.sources.map { it.id })
    }

    @Test
    fun theRegistryKeyIsTheIdTheCallerAskedWith() = runTest {
        val rig = rig()
        client.items["e1"] = item("e1", "Pilot", "Episode") { it.copy(seriesId = "s1") }
        assertTrue(MediaServerMetaSource(rig.store, rig.services).ensureStreamRegistered(id("episode", "e1"), false))
        assertEquals(id("episode", "e1"), MediaServerItemRegistry.get(id("episode", "e1"))!!.contentId)
    }
    @Test
    fun aHydratedEmptySourceListDoesNotCauseAnotherFullItemFetch() = runTest {
        val rig = rig()
        client.items["m1"] = item("m1", "Prepared on demand")
        val src = MediaServerMetaSource(rig.store, rig.services)
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), false))
        assertTrue(src.ensureStreamRegistered(id("movie", "m1"), false))
        assertEquals(1, client.itemRequests.size)
        assertTrue(MediaServerItemRegistry.get(id("movie", "m1"))!!.sourcesLoaded)
    }
}
