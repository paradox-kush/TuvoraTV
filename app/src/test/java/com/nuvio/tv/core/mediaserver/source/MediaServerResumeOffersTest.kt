package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.PlaybackResumeOffer
import com.nuvio.tv.core.contracts.PlaybackResumeOfferRegistry
import com.nuvio.tv.core.mediaserver.FakeClient
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.MediaServerRuntime
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.mediabrowser.IsoTime
import com.nuvio.tv.core.mediaserver.client.mediabrowser.UserDataDto
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.item
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerResumeOffersTest {
    private val fake = FakeClient()
    private val id = "ms:jellyfin:$M:$U:movie:m1"

    @Before fun clean() = PlaybackResumeOfferRegistry.resetForTest()
    @After fun restore() = PlaybackResumeOfferRegistry.resetForTest()

    private fun rig(): TestRig = TestRig(clientFactory = { fake }).also { r ->
        val e = entry()
        r.store.applyFromRemote(1, listOf(e))
        r.credentials.save(e.serverKey, StoredCredential("T"))
    }

    private fun serverItem(positionMs: Long, lastPlayedMs: Long?) = item("m1") {
        it.copy(userData = UserDataDto(playbackPositionTicks = positionMs * 10_000, lastPlayedDate = lastPlayedMs?.let(IsoTime::format)), runTimeTicks = 7_200_000L * 10_000)
    }

    @Test
    fun aServerPositionNewerThanTuvorasIsOfferedOnceWithOneItemFetch() = runTest {
        val rig = rig()
        fake.items["m1"] = serverItem(positionMs = 40 * 60_000L, lastPlayedMs = 2_000_000_000_000L)
        val source = MediaServerResumeOffers(rig.store, rig.services)
        val offer = source.offer(id, tuvoraPositionMs = 5 * 60_000L, tuvoraUpdatedAtMs = 1_900_000_000_000L, durationMs = null)
        assertEquals(PlaybackResumeOffer(40 * 60_000L), offer)
        assertEquals("exactly one fetch", listOf("m1"), fake.itemRequests)
    }

    @Test
    fun noOfferWhenTuvoraIsTheNewerOrTheSameOrTheServerHasNothing() = runTest {
        val rig = rig()
        val source = MediaServerResumeOffers(rig.store, rig.services)
        fake.items["m1"] = serverItem(40 * 60_000L, lastPlayedMs = 1_000_000_000_000L)
        assertNull("Tuvora watched later", source.offer(id, 5 * 60_000L, tuvoraUpdatedAtMs = 1_900_000_000_000L, durationMs = null))
        fake.items["m1"] = serverItem(5 * 60_000L + 5_000, lastPlayedMs = 2_000_000_000_000L)
        assertNull("within 30 s of each other", source.offer(id, 5 * 60_000L, 1_900_000_000_000L, null))
        fake.items["m1"] = item("m1")
        assertNull("no UserData", source.offer(id, 0, null, null))
    }

    @Test
    fun aFailingOrRevokedServerOffersNothingAndNeverBreaksPlayback() = runTest {
        val rig = rig()
        val source = MediaServerResumeOffers(rig.store, rig.services)
        fake.failWith = MediaServerException.Unreachable("down")
        assertNull(source.offer(id, 0, null, null))
        fake.failWith = MediaServerException.Http(401)
        assertNull(source.offer(id, 0, null, null))
        assertTrue("a 401 is noticed here too", "jellyfin:$M:$U" in rig.services.expiredSessions.value)
    }

    @Test
    fun onlyMoviesAndEpisodesAreAskedAbout() {
        val rig = rig()
        val source = MediaServerResumeOffers(rig.store, rig.services)
        assertTrue(source.handles(id, "ms"))
        assertTrue(source.handles("ms:jellyfin:$M:$U:episode:e1", "ms"))
        assertFalse(source.handles("ms:jellyfin:$M:$U:series:s1", "ms"))
        assertFalse(source.handles("tt0133093", null))
        assertFalse(source.handles("xtream:http://x|u:vod:1", "xtream"))
    }

    @Test
    fun theRegistryAsksOnlyTheOwnerAndSwallowsAFailure() = runTest {
        val rig = rig()
        fake.items["m1"] = serverItem(40 * 60_000L, 2_000_000_000_000L)
        PlaybackResumeOfferRegistry.register(object : com.nuvio.tv.core.contracts.PlaybackResumeOfferSource {
            override val name = "broken"
            override fun handles(videoId: String, providerAddonId: String?) = true
            override suspend fun offer(videoId: String, tuvoraPositionMs: Long?, tuvoraUpdatedAtMs: Long?, durationMs: Long?): PlaybackResumeOffer? = error("boom")
        })
        PlaybackResumeOfferRegistry.register(MediaServerResumeOffers(rig.store, rig.services))
        assertEquals("nothing local: the server's position is the start", PlaybackResumeOffer(40 * 60_000L, autoStart = true), PlaybackResumeOfferRegistry.offerFor(id, "ms", 0, 1_000_000_000_000L, null))
        assertNull("a broken source that owns everything offers nothing and the call still returns", PlaybackResumeOfferRegistry.offerFor("tt0133093", null, 0, null, null))
    }
}
