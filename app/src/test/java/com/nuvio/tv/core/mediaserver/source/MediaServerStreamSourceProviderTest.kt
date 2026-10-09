package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.PlaybackPlayMethod
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.FakeClient
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.PlaybackNegotiation
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.item
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
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

class MediaServerStreamSourceProviderTest {
    @After
    fun reset() {
        MediaServerItemRegistry.reset()
        MediaServerPlaybackSessions.reset()
    }

    private val client = FakeClient()
    private fun rig(type: MediaServerType = MediaServerType.JELLYFIN) = TestRig(clientFactory = { client }).also { r ->
        val e = entry(type = type)
        r.store.applyFromRemote(1, listOf(e))
        r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }

    private fun provider(rig: TestRig) = MediaServerStreamSourceProvider(rig.store, rig.services)
    private fun id(type: String = "jellyfin", kind: String = "movie", item: String = "m1") = "ms:$type:$M:$U:$kind:$item"
    private fun register(rig: TestRig, vararg dto: com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto) {
        val e = rig.store.current().single()
        dto.forEach { MediaServerItemMapper.registered(e, it)?.copy(sourcesLoaded = true)?.let(MediaServerItemRegistry::register) }
    }

    @Test
    fun theItemRegistryIsBoundedAndKeepsTheNewest() {
        val e = entry()
        repeat(MediaServerItemRegistry.MAX_ITEMS + 50) { i ->
            MediaServerItemMapper.registered(e, item("i$i"))?.let(MediaServerItemRegistry::register)
        }
        assertEquals(MediaServerItemRegistry.MAX_ITEMS, MediaServerItemRegistry.sizeForTest())
        assertNull("the oldest went first", MediaServerItemRegistry.get(id(item = "i0")))
        assertNotNull(MediaServerItemRegistry.get(id(item = "i${MediaServerItemRegistry.MAX_ITEMS + 49}")))
    }

    @Test
    fun ownershipIsBySourceIdPrefixAndNeverClaimsOtherSources() {
        val p = provider(rig())
        assertTrue(p.isHandledId(id())); assertFalse(p.isHandledId("xtream:http://a|b:vod:1")); assertFalse(p.isHandledId("tt0133093")); assertFalse(p.isHandledId(null))
        assertTrue(p.isMatchSourceId("ms-match:jellyfin:$M:$U")); assertFalse(p.isMatchSourceId("ms")); assertFalse(p.isMatchSourceId("xtream-match:x"))
        assertTrue(p.isDeferredUrl("ms-deferred:jellyfin:$M:$U|i|s")); assertFalse(p.isDeferredUrl("https://nas/x"))
        assertTrue("the matched lane is P3", p.matchSourceGroups("movie").isEmpty() && p.matchSourceGroups("series").isEmpty())
    }

    @Test
    fun listingIsOneDeferredStreamPerMediaSourceAndNeverHoldsATokenOrAPlayableUrl() = runTest {
        val rig = rig()
        register(rig, item("m1", "The Matrix", sources = listOf(source("srcA"), source("srcB", height = 720))))
        val group = provider(rig).directStreams(id()).single()
        assertEquals("Home", group.addonName)
        assertEquals(listOf("ms-deferred:jellyfin:$M:$U|m1|srcA", "ms-deferred:jellyfin:$M:$U|m1|srcB"), group.streams.map { it.url })
        val s = group.streams.first()
        assertEquals("ms", s.sourceId); assertEquals("Home", s.addonName); assertEquals("The Matrix", s.title)
        assertEquals("1080p · H.264 · 4.2 GB", s.name)
        assertEquals("one entry per MediaSource, labelled by what the file is", "720p · H.264 · 4.2 GB", group.streams[1].name)
        assertFalse("no token on a Jellyfin stream item", group.toString().contains("TOKEN-1"))
        assertNull(s.behaviorHints?.proxyHeaders)
        assertTrue("a registry miss with no way to rebuild it lists nothing", provider(rig).directStreams(id(item = "unknown")).isEmpty())
    }

    @Test
    fun aColdStartMissIsRebuiltThroughTheMetaLaneOnce() = runTest {
        val rig = rig()
        var asked = 0
        val p = MediaServerStreamSourceProvider(rig.store, rig.services, ensureRegistered = { asked++; register(rig, item("m1", "The Matrix", sources = listOf(source("srcA")))); true })
        assertEquals(1, p.directStreams(id()).single().streams.size)
        assertEquals(1, asked)
    }

    @Test
    fun embyStreamItemsCarryTheTokenAsAHeaderNeverInTheUrl() = runTest {
        val rig = rig(MediaServerType.EMBY)
        register(rig, item("m1"))
        val s = provider(rig).directStreams(id("emby")).single().streams.single()
        assertEquals(mapOf("X-Emby-Token" to "TOKEN-1"), s.behaviorHints?.proxyHeaders?.request)
        assertFalse(s.url!!.contains("TOKEN-1"))
    }

    @Test
    fun aFullyHydratedItemWithNoKnownSourcesStillGetsADeferredUrl() = runTest {
        val rig = rig()
        val full = MediaServerItemMapper.registered(rig.store.current().single(), item("ep1", type = "Episode") { it.copy(seriesId = "s") })!!
        MediaServerItemRegistry.register(full.copy(sourcesLoaded = true))
        val p = MediaServerStreamSourceProvider(rig.store, rig.services, ensureRegistered = { false })
        val s = p.directStreams(id(kind = "episode", item = "ep1")).single().streams.single()
        assertEquals("ms-deferred:jellyfin:$M:$U|ep1|", s.url)
        assertEquals("Direct play", s.name)
    }

    private fun withSidecars(vararg streams: com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaStreamDto) =
        source("srcA").let { it.copy(mediaStreams = it.mediaStreams + streams) }

    @Test
    fun sidecarTextSubtitlesAreTheOwnSourcesSubtitlesAsTokenlessUrlsAndFollowThePickedVersion() = runTest {
        val rig = rig()
        val sub = com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaStreamDto(index = 4, type = "Subtitle", codec = "subrip", language = "eng", displayTitle = "English (SRT)", isExternal = true)
        val embedded = sub.copy(index = 5, isExternal = false)
        val bitmap = sub.copy(index = 6, codec = "pgssub")
        register(rig, item("m1", sources = listOf(withSidecars(sub, embedded, bitmap), source("srcB"))))
        val subs = MediaServerSubtitleProvider(rig.store, rig.services)
        assertTrue(subs.handles(id())); assertFalse(subs.handles("tt0133093"))
        val only = subs.subtitles(id()).single()
        assertEquals("http://nas:8096/Videos/m1/srcA/Subtitles/4/0/Stream.srt", only.url)
        assertEquals("eng", only.lang); assertTrue(only.isStreamProvided)
        assertNull("Jellyfin serves subtitle files anonymously", only.headers)
        assertFalse(only.url.contains("TOKEN-1"))
        // the version the viewer picked (minted for this play) decides which version's sidecars apply
        MediaServerPlaybackSessions.record(MediaServerPlaybackSessions.Session("jellyfin:$M:$U", "m1", "srcB", "ps", PlaybackPlayMethod.DIRECT_PLAY))
        assertTrue("srcB has no sidecars", subs.subtitles(id()).isEmpty())
        assertTrue("an unknown item has none", subs.subtitles(id(item = "unknown")).isEmpty())
    }

    @Test
    fun embySidecarSubtitlesCarryTheTokenInTheHeaderNotTheUrl() = runTest {
        val rig = rig(MediaServerType.EMBY)
        val sub = com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaStreamDto(index = 3, type = "Subtitle", codec = "srt", isExternal = true)
        register(rig, item("m1", sources = listOf(withSidecars(sub))))
        val only = MediaServerSubtitleProvider(rig.store, rig.services).subtitles(id("emby")).single()
        assertEquals(mapOf("X-Emby-Token" to "TOKEN-1"), only.headers)
        assertFalse(only.url.contains("TOKEN-1"))
        assertEquals("an unlabelled track is still listed", "und", only.lang)
    }

    private fun deferred(item: String = "m1", source: String? = "srcA") = MediaServerIds.deferredUrl("jellyfin:$M:$U", item, source)

    @Test
    fun directPlayMintsTheStaticStreamUrlWithoutAToken() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("srcA")), "ps1")
        val url = provider(rig).resolveDeferredUrl(deferred(), forceMint = false)
        assertEquals("http://nas:8096/Videos/m1/stream?Static=true&MediaSourceId=srcA&Container=mkv&PlaySessionId=ps1", url)
        assertFalse(url!!.contains("TOKEN-1"))
        assertEquals("always pins the media source", "m1" to "srcA", client.playbackRequests.single().let { it.first to it.second.mediaSourceId })
        val session = MediaServerPlaybackSessions.forItem("jellyfin:$M:$U", "m1")!!
        assertEquals(PlaybackPlayMethod.DIRECT_PLAY, session.playMethod); assertEquals("ps1", session.playSessionId); assertEquals("srcA", session.mediaSourceId)
    }

    @Test
    fun aFailedPlayAttemptTranscodesThroughTheServerBuiltUrl() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("srcA")), "ps2")
        val url = provider(rig).resolveDeferredUrl(deferred(), forceMint = true)
        assertEquals("the server's own URL, token and all, kept verbatim", "http://nas:8096/videos/i/master.m3u8?MediaSourceId=src1&ApiKey=SERVER-BUILT", url)
        assertEquals(PlaybackPlayMethod.TRANSCODE, MediaServerPlaybackSessions.latestFor("jellyfin:$M:$U")!!.playMethod)
        assertTrue("the server only builds a TranscodingUrl when asked not to direct play", client.playbackRequests.single().second.forceTranscode)
    }

    @Test
    fun theFirstAttemptNeverForcesATranscode() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("srcA")), "ps")
        provider(rig).resolveDeferredUrl(deferred(), forceMint = false)
        assertFalse(client.playbackRequests.single().second.forceTranscode)
    }

    @Test
    fun aBlankMediaSourcePicksTheFirstTheServerOffers() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("first"), source("second")), null)
        val url = provider(rig).resolveDeferredUrl(deferred(source = null), false)
        assertTrue(url, url!!.contains("MediaSourceId=first"))
    }

    @Test
    fun aRevokedTokenDropsTheSessionAndMintsNothingSoThereIsNoRetryLoop() = runTest {
        val rig = rig()
        client.failWith = MediaServerException.Http(401)
        assertNull(provider(rig).resolveDeferredUrl(deferred(), false))
        assertFalse(rig.services.isSignedIn(rig.store.current().single()))
        assertEquals(1, rig.store.current().size)
    }

    @Test
    fun anOfflineServerOrAnUnplayableSourceMintsNothing() = runTest {
        val rig = rig()
        client.failWith = MediaServerException.Unreachable("down")
        assertNull(provider(rig).resolveDeferredUrl(deferred(), false))
        assertTrue("offline is not revoked", rig.services.isSignedIn(rig.store.current().single()))
        client.failWith = null
        client.negotiation = PlaybackNegotiation(listOf(source("srcA", direct = false, stream = false, transcode = false, transcodingUrl = null)), null)
        assertNull(provider(rig).resolveDeferredUrl(deferred(), false))
        client.negotiation = PlaybackNegotiation(emptyList(), null)
        assertNull(provider(rig).resolveDeferredUrl(deferred(), false))
    }

    @Test
    fun aFailedPlayIsReissuedForTheMediaSourceItWasMintedForAsATranscode() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("srcA"), source("srcB")), "ps1")
        val p = provider(rig)
        p.resolveDeferredUrl(deferred(source = "srcB"), forceMint = false) // the viewer picked version B
        client.playbackRequests.clear()
        val url = p.reissueLink(id(), forceMint = true)
        assertTrue("the server's own transcode URL", url!!.contains("master.m3u8"))
        val request = client.playbackRequests.single().second
        assertEquals("the same version again, not the first one", "srcB", request.mediaSourceId)
        assertTrue(request.forceTranscode)
        assertNull("not an item of this source", p.reissueLink("tt0133093", true))
    }

    @Test
    fun aMalformedOrForeignDeferredUrlMintsNothing() = runTest {
        val rig = rig()
        val p = provider(rig)
        assertNull(p.resolveDeferredUrl("ms-deferred:garbage", false))
        assertNull(p.resolveDeferredUrl("https://nas/x", false))
        assertNull("no entry for that user", p.resolveDeferredUrl(MediaServerIds.deferredUrl("jellyfin:$M:other-user", "m1", null), false))
        val noAddress = TestRig(clientFactory = { client }).also { r -> val e = entry(address = null); r.store.applyFromRemote(1, listOf(e)); r.credentials.save(e.serverKey, StoredCredential("t")) }
        assertNull(provider(noAddress).resolveDeferredUrl(deferred(), false))
    }
    @Test
    fun aSelectedVersionThatDisappearsCannotSilentlyPlayAnotherVersion() = runTest {
        val rig = rig()
        client.negotiation = PlaybackNegotiation(listOf(source("other-version")), "ps")
        assertNull(provider(rig).resolveDeferredUrl(deferred(source = "selected-version"), false))
        assertNull(MediaServerPlaybackSessions.latestFor("jellyfin:$M:$U"))
    }

    @Test
    fun anEmptyForcedTranscodeResponseFallsBackToTheSameOriginalVersionOnce() = runTest {
        val rig = rig()
        client.negotiationFor = { request -> PlaybackNegotiation(if (request.forceTranscode) emptyList() else listOf(source("srcA")), "ps") }
        val url = provider(rig).resolveDeferredUrl(deferred(), true)
        assertTrue(url!!.contains("Static=true&MediaSourceId=srcA"))
        assertEquals(listOf(true, false), client.playbackRequests.map { it.second.forceTranscode })
    }
    @Test
    fun aLightEpisodeWhoseFullFetchFailsCannotPretendItsSourcesWereLoaded() = runTest {
        val rig = rig()
        MediaServerItemMapper.registered(rig.store.current().single(), item("ep1", type = "Episode"))?.let(MediaServerItemRegistry::register)
        val p = MediaServerStreamSourceProvider(rig.store, rig.services, ensureRegistered = { false })
        assertTrue(p.directStreams(id(kind = "episode", item = "ep1")).isEmpty())
    }
}
