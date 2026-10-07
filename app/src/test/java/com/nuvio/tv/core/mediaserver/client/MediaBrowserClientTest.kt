package com.nuvio.tv.core.mediaserver.client

import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.FakeHttp
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserClient
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserClientIdentity
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDialect
import com.nuvio.tv.core.mediaserver.json
import com.nuvio.tv.core.mediaserver.policy.MatchLookupPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Request-shape contract of the client: what goes on the wire for each dialect (the response shapes are pinned by the real-server fixtures). */
class MediaBrowserClientTest {
    private val identity = MediaBrowserClientIdentity("Tuvora", "Pixel 8", "dev-1", "1.0.0")
    private val now = 1_790_000_000_000L // 2026-09-21

    private fun client(http: FakeHttp, dialect: MediaBrowserDialect = MediaBrowserDialect.JELLYFIN, token: String? = "TOK") =
        MediaBrowserClient(http, "http://nas:8096/", dialect, identity, "u1", { token }, { now })

    private val emptyItems = json("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}""")

    private fun query(url: String): Map<String, String> =
        url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun everyAuthenticatedRequestCarriesTheMediaBrowserHeaderWithTheToken() = runTest {
        val http = FakeHttp { json("""{"Id":"u1","Name":"kid"}""") }
        client(http).me()
        val h = http.requests.single().headers
        assertEquals(setOf("Authorization"), h.keys)
        assertTrue(h.getValue("Authorization").endsWith("Token=\"TOK\""))
        val emby = FakeHttp { json("""{"Id":"u1","Name":"kid"}""") }
        client(emby, MediaBrowserDialect.EMBY).me()
        assertEquals(setOf("Authorization", "X-Emby-Token"), emby.requests.single().headers.keys)
        assertEquals("TOK", emby.requests.single().headers.getValue("X-Emby-Token"))
    }

    @Test
    fun aSignedOutClientSendsNoTokenAndTheServerAnswers401() = runTest {
        val http = FakeHttp { json("", 401) }
        val e = kotlin.runCatching { client(http, token = null).me() }.exceptionOrNull()
        assertTrue((e as MediaServerException.Http).isUnauthorized)
        assertFalse(http.requests.single().headers.getValue("Authorization").contains("Token="))
    }

    @Test
    fun theUserAndLibraryRoutesFollowTheDialect() = runTest {
        val jf = FakeHttp { emptyItems }
        client(jf).views()
        assertEquals("http://nas:8096/UserViews?userId=u1", jf.requests.single().url)
        val emby = FakeHttp { emptyItems }
        client(emby, MediaBrowserDialect.EMBY).views()
        assertEquals("http://nas:8096/Users/u1/Views?userId=u1", emby.requests.single().url)
        val me = FakeHttp { json("""{"Id":"u1"}""") }
        client(me).me(); client(me, MediaBrowserDialect.EMBY).me()
        assertEquals(listOf("http://nas:8096/Users/Me", "http://nas:8096/Users/u1"), me.requests.map { it.url })
    }

    @Test
    fun itemsQueryUsesTheMinimalCardPayload() = runTest {
        val http = FakeHttp { emptyItems }
        client(http).items(
            ItemsQuery(parentId = "lib1", includeItemTypes = listOf("Movie", "Series"), startIndex = 40, limit = 20, fields = "Overview", sortBy = "SortName", sortOrder = "Ascending", collapseBoxSetItems = true),
        )
        val q = query(http.requests.single().url)
        assertEquals("/Items", http.requests.single().url.substringAfter("8096").substringBefore('?'))
        assertEquals("u1", q["userId"]); assertEquals("lib1", q["ParentId"]); assertEquals("Movie%2CSeries", q["IncludeItemTypes"])
        assertEquals("40", q["StartIndex"]); assertEquals("20", q["Limit"]); assertEquals("Overview", q["Fields"])
        assertEquals("no per-request total count (cost)", "false", q["EnableTotalRecordCount"])
        assertEquals("1", q["ImageTypeLimit"])
        assertNull(q["AnyProviderIdEquals"])
    }

    @Test
    fun embyItemRowsNameTheFieldsItWithholds() = runTest {
        val http = FakeHttp { emptyItems }
        client(http, MediaBrowserDialect.EMBY).items(ItemsQuery(fields = "Overview"))
        assertTrue(query(http.requests.single().url).getValue("Fields").contains("ProductionYear"))
    }

    @Test
    fun searchIsOneItemsCallForBothDialects() = runTest {
        for (d in MediaBrowserDialect.entries) {
            val http = FakeHttp { emptyItems }
            client(http, d).search("the matrix", 20)
            val q = query(http.requests.single().url)
            assertEquals("the%20matrix", q["SearchTerm"])
            assertEquals("Movie%2CSeries", q["IncludeItemTypes"]); assertEquals("20", q["Limit"])
        }
    }

    @Test
    fun lookupUsesProviderIdFilterOnEmbyAndTitleSearchOnJellyfin() = runTest {
        val emby = FakeHttp { emptyItems }
        client(emby, MediaBrowserDialect.EMBY).lookup(MatchLookupPolicy.Query.ByProviderId(listOf("tmdb.603", "imdb.tt0133093"), "Movie"))
        assertEquals("tmdb.603,imdb.tt0133093".replace(",", "%2C"), query(emby.requests.single().url)["AnyProviderIdEquals"])
        val jf = FakeHttp { emptyItems }
        client(jf).lookup(MatchLookupPolicy.Query.ByTitle("The Matrix", listOf(1998, 1999, 2000), "Movie"))
        val q = query(jf.requests.single().url)
        assertEquals("The%20Matrix", q["SearchTerm"]); assertEquals("1998%2C1999%2C2000", q["Years"]); assertNull(q["AnyProviderIdEquals"])
    }

    @Test
    fun aMissingItemIsNullNotAnError() = runTest {
        val http = FakeHttp { json("", 404) }
        assertNull(client(http).item("nope"))
        assertEquals("Jellyfin: /Items/{id}?userId=", "http://nas:8096/Items/nope?userId=u1", http.requests.single().url)
        val emby = FakeHttp { json("", 404) }
        assertNull(client(emby, MediaBrowserDialect.EMBY).item("10"))
        assertEquals("Emby has only the user-scoped form", "http://nas:8096/Users/u1/Items/10?userId=u1", emby.requests.single().url)
        val http2 = FakeHttp { json("", 500) }
        assertTrue(kotlin.runCatching { client(http2).item("x") }.exceptionOrNull() is MediaServerException.Http)
    }

    @Test
    fun jellyfinHomeShelvesFetchOnlyTheEnabledRows() = runTest {
        val http = FakeHttp { r -> if (r.url.contains("/Items/Latest")) json("[]") else emptyItems }
        client(http).homeShelves(setOf(MediaServerHomeRow.NEXT_UP), 20, "Overview")
        assertEquals("a delta, not the world", 1, http.requests.size)
        assertTrue(http.requests.single().url.contains("/Shows/NextUp?"))
        val q = query(http.requests.single().url)
        assertEquals("false", q["EnableResumable"]); assertEquals("false", q["EnableTotalRecordCount"])
        assertTrue("a year back from the clock: ${q["NextUpDateCutoff"]}", q.getValue("NextUpDateCutoff").startsWith("2025-09-2"))
        val all = FakeHttp { r -> if (r.url.contains("/Items/Latest")) json("[]") else emptyItems }
        client(all).homeShelves(MediaServerHomeRow.entries.toSet(), 20, "Overview")
        assertEquals(setOf("/UserItems/Resume", "/Shows/NextUp", "/Items/Latest"), all.requests.map { "/" + it.url.substringAfter("8096/").substringBefore('?') }.toSet())
        val none = FakeHttp { error("no request expected") }
        client(none).homeShelves(emptySet(), 20, "Overview")
        assertTrue(none.requests.isEmpty())
    }

    @Test
    fun embySplitsItsMixedResumeRouteAndNeedsNoSecondCallForNextUp() = runTest {
        val started = """{"Id":"ep1","Name":"E1","Type":"Episode","UserData":{"PlaybackPositionTicks":600000000}}"""
        val next = """{"Id":"ep2","Name":"E2","Type":"Episode","UserData":{"PlaybackPositionTicks":0}}"""
        val movie = """{"Id":"m1","Name":"M","Type":"Movie","UserData":{"PlaybackPositionTicks":900000000}}"""
        val http = FakeHttp { json("""{"Items":[$started,$next,$movie],"TotalRecordCount":3}""") }
        val shelves = client(http, MediaBrowserDialect.EMBY).homeShelves(setOf(MediaServerHomeRow.CONTINUE_WATCHING, MediaServerHomeRow.NEXT_UP), 20, "Overview")
        assertEquals("Emby: both shelves ride the one resume request", 1, http.requests.size)
        assertTrue(http.requests.single().url.contains("/Users/u1/Items/Resume?"))
        assertEquals(listOf("ep1", "m1"), shelves.continueWatching.map { it.id })
        assertEquals("a zero-position episode is Next Up", listOf("ep2"), shelves.nextUp.map { it.id })
    }

    @Test
    fun playbackInfoNegotiatesWithTheDeviceProfileAndTheUserId() = runTest {
        val http = FakeHttp { json("""{"MediaSources":[{"Id":"s1","Protocol":"File","Container":"mkv","SupportsDirectPlay":true,"SupportsDirectStream":true,"SupportsTranscoding":true}],"PlaySessionId":"ps1"}""") }
        val n = client(http).playbackInfo("item1", PlaybackInfoRequest(mediaSourceId = "s1", maxStreamingBitrate = 8_000_000, startTimeTicks = 600_000_000))
        assertEquals("ps1", n.playSessionId)
        assertEquals("s1", n.sources.single().id)
        val req = http.requests.single()
        assertEquals("POST", req.method)
        assertTrue(req.url.startsWith("http://nas:8096/Items/item1/PlaybackInfo?"))
        val q = query(req.url)
        assertEquals("u1", q["userId"]); assertEquals("8000000", q["MaxStreamingBitrate"]); assertEquals("600000000", q["StartTimeTicks"])
        val body = Json.parseToJsonElement(req.body!!).jsonObject
        assertEquals("u1", body.getValue("UserId").jsonPrimitive.content)
        assertEquals("s1", body.getValue("MediaSourceId").jsonPrimitive.content)
        val profile = body.getValue("DeviceProfile").jsonObject
        assertEquals(8_000_000L, profile.getValue("MaxStreamingBitrate").jsonPrimitive.long)
        assertEquals("hls", profile.getValue("TranscodingProfiles").jsonArray.single().jsonObject.getValue("Protocol").jsonPrimitive.content)
        assertTrue(profile.getValue("DirectPlayProfiles").jsonArray.single().jsonObject.getValue("Container").jsonPrimitive.content.contains("mkv"))
    }

    @Test
    fun aForcedTranscodeDisablesDirectPlayAndDirectStreamInTheRequest() = runTest {
        val http = FakeHttp { json("""{"MediaSources":[{"Id":"s1","SupportsTranscoding":true,"TranscodingUrl":"/videos/i/master.m3u8"}],"PlaySessionId":"p"}""") }
        client(http).playbackInfo("item1", PlaybackInfoRequest(forceTranscode = true))
        val req = http.requests.single()
        assertEquals("false", query(req.url)["EnableDirectPlay"]); assertEquals("false", query(req.url)["EnableDirectStream"])
        val body = Json.parseToJsonElement(req.body!!).jsonObject
        assertFalse(body.getValue("EnableDirectPlay").jsonPrimitive.boolean); assertFalse(body.getValue("EnableDirectStream").jsonPrimitive.boolean)
        val plain = FakeHttp { json("""{"MediaSources":[{"Id":"s1"}]}""") }
        client(plain).playbackInfo("item1", PlaybackInfoRequest())
        assertNull("a normal negotiation leaves the choice to the server", query(plain.requests.single().url)["EnableDirectPlay"])
    }

    @Test
    fun theDeviceProfileOffersExternalSubtitlesUnlessTheyAreBurnedIn() = runTest {
        fun methods(burn: Boolean): List<String> {
            val p = com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDeviceProfile.build(null, burn)
            return p.getValue("SubtitleProfiles").jsonArray.map { it.jsonObject.getValue("Method").jsonPrimitive.content }
        }
        assertTrue("External" in methods(false))
        assertFalse("External" in methods(true))
        assertTrue("uncapped direct play sends no cap", "MaxStreamingBitrate" !in com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDeviceProfile.build(null, false).keys)
    }

    private fun lastBody(http: FakeHttp): JsonObject = Json.parseToJsonElement(http.requests.last().body!!).jsonObject

    @Test
    fun startAndProgressReportsCarryTheTruePlayMethodAndTheSession() = runTest {
        val http = FakeHttp { json("", 204) }
        val c = client(http)
        c.report(PlaybackReport(PlaybackReportKind.START, "item1", "s1", 0, false, "DirectPlay", "ps1"))
        assertEquals("http://nas:8096/Sessions/Playing", http.requests.last().url)
        c.report(PlaybackReport(PlaybackReportKind.PROGRESS, "item1", "s1", 600_000_000, true, "Transcode", "ps1", audioStreamIndex = 1))
        assertEquals("http://nas:8096/Sessions/Playing/Progress", http.requests.last().url)
        val b = lastBody(http)
        assertEquals("Transcode", b.getValue("PlayMethod").jsonPrimitive.content)
        assertTrue(b.getValue("IsPaused").jsonPrimitive.boolean)
        assertEquals(600_000_000L, b.getValue("PositionTicks").jsonPrimitive.long)
        assertEquals("ps1", b.getValue("PlaySessionId").jsonPrimitive.content)
        assertEquals(1, b.getValue("AudioStreamIndex").jsonPrimitive.int)
        assertTrue(b.getValue("CanSeek").jsonPrimitive.boolean)
    }

    @Test
    fun stoppedReportsAreTheMinimalShapeAndTheHeaderIsPlainJson() = runTest {
        val http = FakeHttp { json("", 204) }
        client(http).report(PlaybackReport(PlaybackReportKind.STOPPED, "item1", "s1", 5_000_000_000, false, "DirectPlay", "ps1"))
        val r = http.requests.single()
        assertEquals("http://nas:8096/Sessions/Playing/Stopped", r.url)
        val b = lastBody(http)
        assertEquals(setOf("ItemId", "MediaSourceId", "PositionTicks", "PlaySessionId", "Failed"), b.keys)
        assertFalse(b.getValue("Failed").jsonPrimitive.boolean)
    }

    @Test
    fun embyNeverSendsAProgressReportWithoutAPlaySessionId() = runTest {
        val emby = FakeHttp { json("", 204) }
        client(emby, MediaBrowserDialect.EMBY).report(PlaybackReport(PlaybackReportKind.PROGRESS, "item9", null, 1, false, "DirectPlay", null))
        assertEquals("Emby answers 400 without one", "tuvora-item9", lastBody(emby).getValue("PlaySessionId").jsonPrimitive.content)
        val jf = FakeHttp { json("", 204) }
        client(jf).report(PlaybackReport(PlaybackReportKind.PROGRESS, "item9", null, 1, false, "DirectPlay", null))
        assertFalse("Jellyfin accepts none", "PlaySessionId" in lastBody(jf).keys)
    }

    @Test
    fun playedFlagUsesTheDialectRoute() = runTest {
        val jf = FakeHttp { json("", 200) }
        client(jf).setPlayed("i1", true); client(jf).setPlayed("i1", false)
        assertEquals(listOf("POST http://nas:8096/UserPlayedItems/i1?userId=u1", "DELETE http://nas:8096/UserPlayedItems/i1?userId=u1"), jf.requests.map { "${it.method} ${it.url}" })
        val emby = FakeHttp { json("", 200) }
        client(emby, MediaBrowserDialect.EMBY).setPlayed("i1", true)
        assertEquals("POST http://nas:8096/Users/u1/PlayedItems/i1?userId=u1", "${emby.requests.single().method} ${emby.requests.single().url}")
    }

    @Test
    fun approvingAnotherDevicesCodeUsesThisSessionAndIsJellyfinOnly() = runTest {
        val http = FakeHttp { json("", 204) }
        client(http).authorizeQuickConnect(" 123456 ")
        val r = http.requests.single()
        assertEquals("POST http://nas:8096/QuickConnect/Authorize?code=123456", "${r.method} ${r.url}")
        assertTrue("approved with THIS device's session; the other device gets its own token", r.headers.getValue("Authorization").contains("Token=\"TOK\""))
        val emby = FakeHttp { error("no request") }
        val e = kotlin.runCatching { client(emby, MediaBrowserDialect.EMBY).authorizeQuickConnect("123456") }.exceptionOrNull()
        assertTrue((e as MediaServerException.Http).isNotFound)
    }

    @Test
    fun aPlaybackInfoRefusalWithNoSourcesIsMalformedNotEmpty() = runTest {
        val http = FakeHttp { json("""{"MediaSources":[],"ErrorCode":"NoCompatibleStream"}""") }
        assertTrue(kotlin.runCatching { client(http).playbackInfo("i", PlaybackInfoRequest()) }.exceptionOrNull() is MediaServerException.Malformed)
    }

    @Test
    fun seasonsAndEpisodesAreSeriesScoped() = runTest {
        val http = FakeHttp { emptyItems }
        client(http).seasons("ser1"); client(http).episodes("ser1", "sea1")
        assertEquals(listOf("/Shows/ser1/Seasons", "/Shows/ser1/Episodes"), http.requests.map { it.url.substringAfter("8096").substringBefore('?') })
        assertEquals("sea1", query(http.requests.last().url)["SeasonId"])
    }
}
