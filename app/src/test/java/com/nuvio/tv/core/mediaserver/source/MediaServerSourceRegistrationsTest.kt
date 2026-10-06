package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.HomeSectionContributorRegistry
import com.nuvio.tv.core.contracts.IptvSearchProvider
import com.nuvio.tv.core.contracts.IptvSearchRow
import com.nuvio.tv.core.contracts.MetaSourceAccess
import com.nuvio.tv.core.contracts.MetaSourceRegistry
import com.nuvio.tv.core.contracts.OwnSourcePolicy
import com.nuvio.tv.core.rec.withWireItemId
import com.nuvio.tv.core.contracts.PlaybackResumeOfferRegistry
import com.nuvio.tv.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.tv.core.contracts.SearchProviderRegistry
import com.nuvio.tv.core.contracts.StreamSourceAccess
import com.nuvio.tv.core.contracts.StreamSourceRegistry
import com.nuvio.tv.core.iptv.IptvSourceRegistrations
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.assertFailsWith
import com.nuvio.tv.core.mediaserver.entry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The J0 hand-off contract on TV, pinned: media servers register as ONE entry named `mediaserver` in each plural
 * source port, next to IPTV, and the two never claim each other's ids. (IPTV's own golden list lives in
 * IptvGoldenListContractTest; this is the other half.)
 */
class MediaServerSourceRegistrationsTest {
    private val ms = "ms:jellyfin:$M:$U:movie:m1"
    private val xtream = "xtream:http://line.example.com|alice:vod:7"

    private object IptvSearchStandIn : IptvSearchProvider {
        override suspend fun hasSearchableSources() = true
        override suspend fun search(query: String): List<IptvSearchRow> = emptyList()
        override fun sourceSignature(): Flow<String?> = flowOf("iptv")
    }

    private val titles = object : MediaServerRowTitles {
        override suspend fun home(row: com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow, serverName: String) = "$row · $serverName"
        override suspend fun searchMovies(serverName: String) = "Movies · $serverName"
        override suspend fun searchSeries(serverName: String) = "TV Shows · $serverName"
    }

    private fun resetAll() {
        OwnSourcePolicy.resetForTest()
        StreamSourceRegistry.resetForTest()
        MetaSourceRegistry.resetForTest()
        SearchProviderRegistry.resetForTest()
        HomeSectionContributorRegistry.resetForTest()
        PlaybackSessionReporterRegistry.resetForTest()
        PlaybackResumeOfferRegistry.resetForTest()
        com.nuvio.tv.core.contracts.OwnSourceSubtitleRegistry.resetForTest()
    }

    @Before fun clean() = resetAll()
    @After fun restore() { resetAll(); MediaServerItemRegistry.reset() }

    private fun rig(): TestRig = TestRig().also { it.store.applyFromRemote(1, listOf(entry())) }

    private fun registerBoth(rig: TestRig) {
        IptvSourceRegistrations.register(IptvSearchStandIn)
        registerMediaServerSources(rig.store, rig.services, { rig.nowMs }, titles)
    }

    @Test
    fun aServersItemsAreNeverScrobbledAndTelemetryNeverNamesTheServerOrUser() {
        registerBoth(rig())
        assertTrue(OwnSourcePolicy.isExcludedFromTrackingScrobble(ms))
        assertFalse("IPTV scrobbling is unchanged", OwnSourcePolicy.isExcludedFromTrackingScrobble(xtream))
        assertFalse(OwnSourcePolicy.isExcludedFromTrackingScrobble("tmdb:603"))
        val wire = OwnSourcePolicy.telemetryId(ms, installSalt = "salt-1")
        assertFalse("machine and user ids stay on the device: $wire", M in wire || U in wire)
        assertTrue(wire, wire.startsWith("ms:jellyfin:") && wire.endsWith(":movie:m1"))
        assertEquals("stable for one install", wire, OwnSourcePolicy.telemetryId(ms, "salt-1"))
        assertTrue("different installs cannot be joined", wire != OwnSourcePolicy.telemetryId(ms, "salt-2"))
        assertEquals("everything else is untouched", "tt0133093", OwnSourcePolicy.telemetryId("tt0133093", "salt-1"))
    }

    @Test
    fun theRecTelemetryEventCarriesOnlyTheHashedServerItemId() {
        registerBoth(rig())
        fun event(id: String?) = com.nuvio.tv.core.rec.RecEvent(eventType = "play_start", surface = "player", contentType = "movie", itemId = id)
        val wire = event(ms).withWireItemId("salt-1").itemId.orEmpty()
        assertFalse("machine and user ids never reach the telemetry wire: $wire", M in wire || U in wire)
        assertEquals(OwnSourcePolicy.telemetryId(ms, "salt-1"), wire)
        assertEquals("a public id is untouched", "tt0133093", event("tt0133093").withWireItemId("salt-1").itemId)
        assertEquals(null, event(null).withWireItemId("salt-1").itemId)
    }

    @Test
    fun aServersIdsNeverReachASubtitleAddOnAndItsLinksAreNeverCached() {
        registerBoth(rig())
        assertTrue("machine + user ids must not reach a third-party subtitle add-on", OwnSourcePolicy.isSubtitleScopedId(ms))
        assertTrue(OwnSourcePolicy.isSubtitleScopedId(xtream))
        assertFalse(OwnSourcePolicy.isSubtitleScopedId("tt0133093:1:2"))
        assertTrue("minted links may carry a token", OwnSourcePolicy.isNeverCachedLinkId(ms))
        assertFalse(OwnSourcePolicy.isNeverCachedLinkId("tt0133093"))
    }

    @Test
    fun theNameIsMediaserverInEveryPluralPort() {
        registerBoth(rig())
        assertEquals("mediaserver", MediaServerSourceRegistrations.NAME)
        assertEquals("IPTV + media servers", 2, SearchProviderRegistry.all.size)
        assertEquals("one stream-source entry", 1, StreamSourceRegistry.all.size)
        assertEquals("one meta-source entry", 1, MetaSourceRegistry.all.size)
        assertEquals(listOf("mediaserver"), HomeSectionContributorRegistry.all.map { it.name })
        assertEquals(listOf("mediaserver"), PlaybackSessionReporterRegistry.all.map { it.name })
        assertFalse(PlaybackResumeOfferRegistry.isEmpty)
    }

    @Test
    fun aDuplicateRegistrationIsRefusedLoudly() {
        val rig = rig()
        registerMediaServerSources(rig.store, rig.services, { rig.nowMs }, titles)
        assertFailsWith<IllegalArgumentException> { registerMediaServerSources(rig.store, rig.services, { rig.nowMs }, titles) }
    }

    @Test
    fun theMediaServerLaneOwnsOnlyItsOwnIds() {
        registerBoth(rig())
        val streams = StreamSourceAccess.current()
        assertTrue(streams.isHandledId(ms))
        assertFalse("IPTV keeps its own lane inside the repository: the plural port does not claim it", streams.isHandledId(xtream))
        assertEquals("exactly one source owns an ms: id", 1, StreamSourceRegistry.all.count { it.isHandledId(ms) })
        assertTrue(MetaSourceAccess.handlesId(ms)); assertFalse(MetaSourceAccess.handlesId(xtream))
        assertTrue(streams.isDeferredUrl("ms-deferred:jellyfin:$M:$U|m1|s")); assertFalse(streams.isDeferredUrl("https://nas/x"))
        assertFalse("the Stalker deferred scheme stays IPTV's", streams.isDeferredUrl("stalker-deferred:acc|movie|1|x"))
    }

    @Test
    fun ownSourcePredicatesCoverBothLanesWithoutStealingIptvs() {
        registerBoth(rig())
        assertTrue(OwnSourcePolicy.isOwnContentId(ms)); assertTrue(OwnSourcePolicy.isOwnContentId(xtream)); assertFalse(OwnSourcePolicy.isOwnContentId("tt0133093"))
        assertTrue(OwnSourcePolicy.isOwnProviderId("ms")); assertTrue(OwnSourcePolicy.isOwnProviderId("ms-match:jellyfin:$M:$U"))
        assertFalse(OwnSourcePolicy.isOwnProviderId("addon:torrentio"))
    }

    @Test
    fun onlyMediaServerPlaysReachTheSessionReporter() {
        registerBoth(rig())
        assertEquals(1, PlaybackSessionReporterRegistry.handlersFor(ms, "ms").size)
        assertTrue(PlaybackSessionReporterRegistry.handlersFor(xtream, "xtream").isEmpty())
        assertTrue(PlaybackSessionReporterRegistry.handlersFor("tt1", "addon:x").isEmpty())
    }

    @Test
    fun theHomeContributorIsReturnedSoScreensCanInvalidateIt() {
        val rig = rig()
        val home = registerMediaServerSources(rig.store, rig.services, { rig.nowMs }, titles)
        assertEquals("mediaserver", home.name)
        home.invalidate("jellyfin:$M") // no crash invalidating a server that was never fetched
    }
}
