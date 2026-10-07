package com.nuvio.tv.core.mediaserver

import com.nuvio.tv.core.contracts.PlaybackPlayMethod
import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.client.ItemsQuery
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.PlaybackInfoRequest
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDialect
import com.nuvio.tv.core.mediaserver.policy.PlaybackDecisionPolicy
import com.nuvio.tv.core.mediaserver.source.MediaServerItemMapper
import com.nuvio.tv.core.mediaserver.source.MediaServerItemRegistry
import com.nuvio.tv.core.mediaserver.source.MediaServerPlaybackSessions
import com.nuvio.tv.core.mediaserver.source.MediaServerStreamSourceProvider
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.nuvio.tv.core.mediaserver.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * The client against REAL recorded responses ([RealServerFixtures]: Jellyfin 12.2.0 and Emby 4.10.1.0): every
 * decode, route and decision below is pinned to what an actual server answered, not to what the docs say. Runs on
 * every platform (JVM host, iOS simulator, desktop).
 */
class RealServerContractTest {
    @After
    fun reset() {
        MediaServerItemRegistry.reset()
        MediaServerPlaybackSessions.reset()
    }

    private val jfMachine = "b8cf3f481b1e40ae8851c41b11da2041"
    private val jfUser = "bc38578b7cb748748243a927751e235e"
    private val embyUser = "5d535c19d77a4501a449ab349b2d1510"

    /** First route whose needle the URL contains answers; anything else is a failing 404 (so an unexpected route is visible). */
    private fun server(vararg routes: Pair<String, String>) = FakeHttp { r ->
        routes.firstOrNull { (needle, _) -> r.url.contains(needle) }?.let { json(it.second) } ?: json("not found: ${r.url}", 404)
    }

    private fun rigFor(type: MediaServerType, userId: String, machine: String, http: FakeHttp): TestRig {
        val rig = TestRig(http = http)
        val e = entry(type = type, userId = userId, machineId = machine, address = "http://nas:8096")
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, StoredCredential("TOKEN-LIVE"))
        return rig
    }

    // ---------------- Jellyfin 12.2.0 ----------------

    @Test
    fun jellyfinPublicInfoIdentifiesTheServer() = runTest {
        val rig = TestRig(http = server("/System/Info/Public" to RealServerFixtures.J_SYSTEM_INFO))
        val info = rig.services.authApi("http://nas:8096", MediaServerType.JELLYFIN).publicInfo()
        assertEquals(jfMachine, info.machineId)
        assertEquals("12.2.0", info.version)
        assertEquals(MediaServerType.JELLYFIN, info.detectedType)
    }

    @Test
    fun jellyfinQuickConnectShapesDecode() = runTest {
        val api = TestRig(http = server(
            "/QuickConnect/Initiate" to RealServerFixtures.J_QC_INITIATE,
            "/QuickConnect/Connect" to RealServerFixtures.J_QC_CONNECT_AUTHORIZED,
            "/Users/AuthenticateWithQuickConnect" to RealServerFixtures.J_AUTH_QUICKCONNECT,
        )).services.authApi("http://nas:8096", MediaServerType.JELLYFIN)
        val req = api.quickConnectInitiate()
        assertEquals("388948", req.code)
        assertTrue(req.secret.isNotBlank())
        assertTrue(api.quickConnectApproved(req.secret))
        val session = api.authenticateWithQuickConnect(req.secret)
        assertEquals(jfUser, session.userId)
        assertEquals("tuvora", session.userName)
        assertEquals(jfMachine, session.serverId)
        assertTrue(session.isAdministrator)
        assertTrue(session.accessToken.isNotBlank())
    }

    @Test
    fun jellyfinPasswordSignInDecodes() = runTest {
        val s = TestRig(http = server("/Users/AuthenticateByName" to RealServerFixtures.J_AUTH_BY_NAME))
            .services.authApi("http://nas:8096", MediaServerType.JELLYFIN).authenticateByName("tuvora", "pw")
        assertEquals(jfUser, s.userId); assertEquals("tuvora", s.userName)
    }

    @Test
    fun jellyfinLibraryRoutesAndShapes() = runTest {
        val http = server(
            "/Users/Me" to RealServerFixtures.J_USERS_ME,
            "/UserViews" to RealServerFixtures.J_VIEWS,
            "/Items?" to RealServerFixtures.J_ITEMS_MOVIES,
        )
        val rig = rigFor(MediaServerType.JELLYFIN, jfUser, jfMachine, http)
        val client = rig.services.clientFor(rig.store.current().single())!!
        assertEquals(jfUser, client.me().id)
        val views = client.views()
        assertTrue(views.isNotEmpty() && views.all { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() })
        assertTrue(views.any { it.collectionType == "movies" })
        val page = client.items(ItemsQuery(parentId = views.first().id, includeItemTypes = listOf("Movie"), limit = 3))
        assertTrue(page.items.isNotEmpty())
        val movie = page.items.first()
        assertEquals("Movie", movie.type)
        val card = assertNotNull(MediaServerItemMapper.preview(rig.store.current().single(), movie))
        assertEquals("ms:jellyfin:$jfMachine:$jfUser:movie:${movie.id}", card.id)
        assertEquals("movie", card.rawType)
        assertFalse("images are credential-free", card.poster.orEmpty().contains("TOKEN"))
        // the requests carried the dialect's routes and the token in the MediaBrowser header only
        assertTrue(http.requests.all { it.headers.getValue("Authorization").contains("Token=\"TOKEN-LIVE\"") })
        assertTrue("never a token in a URL", http.requests.none { it.url.contains("TOKEN-LIVE") })
        assertEquals(setOf("http://nas:8096/Users/Me", "http://nas:8096/UserViews?userId=$jfUser"), http.requests.map { it.url }.filter { !it.contains("/Items") }.toSet())
    }

    @Test
    fun jellyfinItemCarriesMediaSourcesAndUserData() = runTest {
        val http = server("/Items/657791ee941ef64455a51ee1525ac216" to RealServerFixtures.J_ITEM_AFTER_STOP)
        val rig = rigFor(MediaServerType.JELLYFIN, jfUser, jfMachine, http)
        val item = rig.services.clientFor(rig.store.current().single())!!.item("657791ee941ef64455a51ee1525ac216", "MediaSources")!!
        assertEquals("Movie", item.type)
        val source = item.mediaSources.first()
        assertTrue("a Jellyfin MediaSource id is the item id", source.supportsDirectPlay && source.id == item.id)
        assertEquals("File", source.protocol)
        val reg = assertNotNull(MediaServerItemMapper.registered(rig.store.current().single(), item))
        assertEquals(source.id, reg.sources.first().id)
        assertTrue(reg.sources.first().label.contains("H.264") || reg.sources.first().label.isNotBlank())
        assertTrue(item.userData != null)
    }

    @Test
    fun jellyfinSeriesSeasonsAndEpisodes() = runTest {
        val http = server(
            "/Shows/ba204eb72b0f97d64a53ff24dffd928c/Seasons" to RealServerFixtures.J_SEASONS,
            "/Shows/ba204eb72b0f97d64a53ff24dffd928c/Episodes" to RealServerFixtures.J_EPISODES,
        )
        val rig = rigFor(MediaServerType.JELLYFIN, jfUser, jfMachine, http)
        val client = rig.services.clientFor(rig.store.current().single())!!
        val seasons = client.seasons("ba204eb72b0f97d64a53ff24dffd928c")
        assertTrue(seasons.all { it.type == "Season" } && seasons.isNotEmpty())
        val eps = client.episodes("ba204eb72b0f97d64a53ff24dffd928c")
        assertTrue(eps.isNotEmpty() && eps.all { it.type == "Episode" && it.parentIndexNumber != null && it.indexNumber != null })
        val card = assertNotNull(MediaServerItemMapper.preview(rig.store.current().single(), eps.first()))
        assertEquals("an episode card opens its series", "ms:jellyfin:$jfMachine:$jfUser:series:ba204eb72b0f97d64a53ff24dffd928c", card.id)
    }

    @Test
    fun jellyfinHomeShelvesFromTheRealRoutes() = runTest {
        val http = server(
            "/UserItems/Resume" to RealServerFixtures.J_RESUME_AFTER_STOP,
            "/Shows/NextUp" to RealServerFixtures.J_NEXTUP_AFTER_PLAYED,
            "/Items/Latest" to RealServerFixtures.J_LATEST,
        )
        val rig = rigFor(MediaServerType.JELLYFIN, jfUser, jfMachine, http)
        val shelves = rig.services.clientFor(rig.store.current().single())!!.homeShelves(MediaServerHomeRow.entries.toSet(), 20, "Overview")
        assertTrue("resume rows are started items", shelves.continueWatching.isNotEmpty() && shelves.continueWatching.all { (it.userData?.playbackPositionTicks ?: 0) > 0 })
        assertTrue(shelves.nextUp.isNotEmpty() && shelves.nextUp.all { it.type == "Episode" && it.seriesId != null })
        assertTrue("/Items/Latest answers a bare list", shelves.recentlyAdded.isNotEmpty())
        assertEquals(3, http.requests.size)
        assertTrue(http.requests.single { it.url.contains("/Items/Latest") }.url.contains("userId=$jfUser"))
    }

    @Test
    fun jellyfinPlaybackInfoDirectPlayMintsATokenlessStaticUrl() = runTest {
        val http = server("/PlaybackInfo" to RealServerFixtures.J_PLAYBACKINFO_DIRECT)
        val rig = rigFor(MediaServerType.JELLYFIN, jfUser, jfMachine, http)
        val client = rig.services.clientFor(rig.store.current().single())!!
        val negotiation = client.playbackInfo("657791ee941ef64455a51ee1525ac216", PlaybackInfoRequest())
        assertTrue(negotiation.playSessionId?.isNotBlank() == true)
        val s = negotiation.sources.first()
        assertTrue(s.supportsDirectPlay && s.supportsDirectStream && s.supportsTranscoding && s.transcodingUrl == null)
        val d = PlaybackDecisionPolicy.decide(
            PlaybackDecisionPolicy.SourceFacts(s.id, s.protocol, s.container, s.supportsDirectPlay, s.supportsDirectStream, s.supportsTranscoding, s.directStreamUrl, s.transcodingUrl), null, false,
        )
        assertEquals(PlaybackPlayMethod.DIRECT_PLAY, d.method)
        assertTrue(d.plan is PlaybackDecisionPolicy.Plan.StaticStream)
    }

    @Test
    fun jellyfinTranscodeFallbackUsesTheServerBuiltUrlWithItsOwnApiKey() = runTest {
        val http = server("/PlaybackInfo" to RealServerFixtures.J_PLAYBACKINFO_TRANSCODE)
        val rig = rigFor(MediaServerType.JELLYFIN, jfUser, jfMachine, http)
        MediaServerItemRegistry.register(MediaServerItemMapper.registered(rig.store.current().single(), com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto(id = "657791ee941ef64455a51ee1525ac216", name = "M", type = "Movie"))!!)
        val provider = MediaServerStreamSourceProvider(rig.store, rig.services)
        val url = assertNotNull(provider.resolveDeferredUrl("ms-deferred:jellyfin:$jfMachine:$jfUser|657791ee941ef64455a51ee1525ac216|", forceMint = false))
        assertTrue(url, url.startsWith("http://nas:8096/videos/"))
        assertTrue("the server put its own ApiKey in the transcode URL: $url", url.contains("master.m3u8") && url.contains("ApiKey="))
        assertEquals(PlaybackPlayMethod.TRANSCODE, MediaServerPlaybackSessions.latestFor("jellyfin:$jfMachine:$jfUser")!!.playMethod)
    }

    @Test
    fun jellyfinSearchHitsMapToCards() = runTest {
        val rig = rigFor(MediaServerType.JELLYFIN, jfUser, jfMachine, server("/Items?" to RealServerFixtures.J_ITEMS_SEARCH))
        val hits = rig.services.clientFor(rig.store.current().single())!!.search("Test", 20)
        assertTrue(hits.isNotEmpty() && hits.any { MediaServerItemMapper.preview(rig.store.current().single(), it) != null })
    }

    // ---------------- Emby 4.10.1.0 ----------------

    @Test
    fun embyIsRecognisedWithoutAProductName() = runTest {
        val info = TestRig(http = server("/System/Info/Public" to RealServerFixtures.E_SYSTEM_INFO)).services.authApi("http://nas:8096", MediaServerType.JELLYFIN).publicInfo()
        assertEquals("Emby 4.10 omits ProductName but returns RemoteAddresses", MediaServerType.EMBY, info.detectedType)
        assertTrue(info.machineId.isNotBlank())
    }

    @Test
    fun embyPasswordSignInDecodes() = runTest {
        val s = TestRig(http = server("/Users/AuthenticateByName" to RealServerFixtures.E_AUTH_BY_NAME)).services.authApi("http://nas:8096", MediaServerType.EMBY).authenticateByName("tuvora", "pw")
        assertEquals(embyUser, s.userId)
        assertTrue(s.accessToken.isNotBlank())
    }

    @Test
    fun embyUsesTheUserScopedRoutesAndNumericIds() = runTest {
        val http = server(
            "/Users/$embyUser/Views" to RealServerFixtures.E_VIEWS,
            "/Users/$embyUser/Items/10" to RealServerFixtures.E_ITEM_MOVIE,
            "/Users/$embyUser/Items/Latest" to RealServerFixtures.E_LATEST,
            "/Users/$embyUser/Items/Resume" to RealServerFixtures.E_RESUME_AFTER_STOP,
            "/Items?" to RealServerFixtures.E_ITEMS_MOVIES,
        )
        val rig = rigFor(MediaServerType.EMBY, embyUser, "embymachine", http)
        val e = rig.store.current().single()
        val client = rig.services.clientFor(e)!!
        assertTrue(client.views().isNotEmpty())
        val movies = client.items(ItemsQuery(parentId = "3", includeItemTypes = listOf("Movie"), limit = 3)).items
        assertTrue("Emby ids are numeric strings", movies.all { it.id!!.all(Char::isDigit) })
        val one = client.item("10")!!
        assertEquals("10", one.id)
        assertTrue(one.mediaSources.first().id!!.startsWith("mediasource_"))
        val shelves = client.homeShelves(setOf(MediaServerHomeRow.CONTINUE_WATCHING, MediaServerHomeRow.RECENTLY_ADDED), 20, "Overview")
        assertEquals("the stopped-at-38% movie is the one resumable item", listOf("12"), shelves.continueWatching.map { it.id })
        assertTrue(shelves.recentlyAdded.size >= 2)
        // every authenticated request: MediaBrowser header + X-Emby-Token, never a token in the URL
        assertTrue(http.requests.all { it.headers["X-Emby-Token"] == "TOKEN-LIVE" })
        assertTrue(http.requests.none { it.url.contains("TOKEN-LIVE") })
        assertTrue("Emby 404s the unscoped /Items/Latest", http.requests.any { it.url.contains("/Users/$embyUser/Items/Latest") })
        // a numeric item id round-trips through content ids
        assertEquals("ms:emby:embymachine:$embyUser:movie:10", MediaServerItemMapper.preview(e, one)!!.id)
    }

    @Test
    fun embySeasonsAndEpisodes() = runTest {
        val rig = rigFor(MediaServerType.EMBY, embyUser, "embymachine", server("/Shows/13/Seasons" to RealServerFixtures.E_SEASONS, "/Shows/13/Episodes" to RealServerFixtures.E_EPISODES))
        val client = rig.services.clientFor(rig.store.current().single())!!
        assertTrue(client.seasons("13").isNotEmpty())
        val eps = client.episodes("13", "14")
        assertTrue(eps.isNotEmpty() && eps.all { it.type == "Episode" })
    }

    @Test
    fun embyTranscodeFallbackKeepsTheServerBuiltApiKeyUrl() = runTest {
        val http = server("/PlaybackInfo" to RealServerFixtures.E_PLAYBACKINFO_TRANSCODE)
        val rig = rigFor(MediaServerType.EMBY, embyUser, "embymachine", http)
        val provider = MediaServerStreamSourceProvider(rig.store, rig.services)
        val url = assertNotNull(provider.resolveDeferredUrl("ms-deferred:emby:embymachine:$embyUser|10|mediasource_10", forceMint = false))
        assertTrue(url, url.startsWith("http://nas:8096/videos/10/master.m3u8?") && url.contains("api_key="))
        assertEquals(PlaybackPlayMethod.TRANSCODE, MediaServerPlaybackSessions.forItem("emby:embymachine:$embyUser", "10")!!.playMethod)
    }

    @Test
    fun embyDirectPlayMintsAStaticUrlWithoutATokenAndTheHeaderCarriesIt() = runTest {
        val http = server("/PlaybackInfo" to RealServerFixtures.E_PLAYBACKINFO_DIRECT)
        val rig = rigFor(MediaServerType.EMBY, embyUser, "embymachine", http)
        MediaServerItemRegistry.register(MediaServerItemMapper.registered(rig.store.current().single(), com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto(id = "10", name = "Test Movie", type = "Movie"))!!)
        val provider = MediaServerStreamSourceProvider(rig.store, rig.services)
        val url = provider.resolveDeferredUrl("ms-deferred:emby:embymachine:$embyUser|10|mediasource_10", forceMint = false)!!
        assertEquals("http://nas:8096/Videos/10/stream?Static=true&MediaSourceId=mediasource_10&Container=mkv&PlaySessionId=${url.substringAfter("PlaySessionId=")}", url)
        assertFalse(url.contains("TOKEN"))
        val item = provider.directStreams("ms:emby:embymachine:$embyUser:movie:10").single().streams.single()
        assertEquals("Emby's stream route needs the token: it rides a header", mapOf("X-Emby-Token" to "TOKEN-LIVE"), item.behaviorHints?.proxyHeaders?.request)
    }

    @Test
    fun anUnauthorizedRealAnswerIsAnHttpErrorTheSessionLayerReactsTo() = runTest {
        val rig = rigFor(MediaServerType.JELLYFIN, jfUser, jfMachine, FakeHttp { json("", 401) })
        val err = kotlin.runCatching { rig.services.clientFor(rig.store.current().single())!!.me() }.exceptionOrNull()
        assertTrue((err as MediaServerException.Http).isUnauthorized)
        assertEquals(com.nuvio.tv.core.mediaserver.client.HealthStatus.AUTH_ERROR, rig.services.health(rig.store.current().single()))
    }

    @Test
    fun theDialectDetectionAgreesWithBothRealBodies() {
        assertEquals(MediaBrowserDialect.JELLYFIN, MediaBrowserDialect.detect("Jellyfin Server", false))
        assertEquals(MediaBrowserDialect.EMBY, MediaBrowserDialect.detect(null, true))
    }
}
