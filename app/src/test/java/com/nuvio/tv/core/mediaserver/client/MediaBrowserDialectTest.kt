package com.nuvio.tv.core.mediaserver.client

import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserAuth
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserClientIdentity
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDialect
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserPaths
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserUrls
import com.nuvio.tv.core.mediaserver.client.mediabrowser.percentEncode
import org.junit.Test
import org.junit.Assert.assertEquals
import com.nuvio.tv.core.mediaserver.assertFailsWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaBrowserDialectTest {
    @Test
    fun capabilityTableMatchesTheVerifiedServers() {
        val jf = MediaBrowserDialect.JELLYFIN
        val emby = MediaBrowserDialect.EMBY
        assertTrue(jf.supportsQuickConnect); assertFalse(emby.supportsQuickConnect)
        assertTrue(jf.supportsGlobalNextUp); assertFalse(emby.supportsGlobalNextUp)
        assertTrue(jf.resumeReturnsOnlyStartedItems); assertFalse(emby.resumeReturnsOnlyStartedItems)
        assertFalse(jf.requiresPlaySessionId); assertTrue(emby.requiresPlaySessionId)
        assertFalse(jf.supportsProviderIdFilter); assertTrue(emby.supportsProviderIdFilter)
        assertFalse(jf.requiresUserScopedRoutes); assertTrue(emby.requiresUserScopedRoutes)
        assertTrue(jf.supportsTrickplay && jf.supportsMediaSegments); assertFalse(emby.supportsTrickplay || emby.supportsMediaSegments)
        assertEquals("ApiKey", jf.tokenQueryParam); assertEquals("api_key", emby.tokenQueryParam)
        assertNull(jf.extraTokenHeader); assertEquals("X-Emby-Token", emby.extraTokenHeader)
        assertEquals(listOf(8096), jf.httpsPortGuesses); assertEquals(listOf(8920, 8096), emby.httpsPortGuesses)
        assertEquals(MediaBrowserDialect.EMBY, MediaBrowserDialect.of(MediaServerType.EMBY))
        assertEquals(MediaBrowserDialect.JELLYFIN, MediaBrowserDialect.of(MediaServerType.JELLYFIN))
        assertEquals("Jellyfin", jf.productName)
    }

    @Test
    fun dialectIsDetectedFromThePublicSystemInfo() {
        assertEquals(MediaBrowserDialect.JELLYFIN, MediaBrowserDialect.detect("Jellyfin Server", false))
        assertEquals(MediaBrowserDialect.EMBY, MediaBrowserDialect.detect("Emby Server", false))
        assertEquals("Emby 4.9 omits ProductName", MediaBrowserDialect.EMBY, MediaBrowserDialect.detect(null, hasRemoteAddresses = true))
        assertNull("keep what the user picked", MediaBrowserDialect.detect(null, false))
        assertEquals(MediaBrowserDialect.JELLYFIN, MediaBrowserDialect.detect("Jellyfin Server", hasRemoteAddresses = true))
    }

    @Test
    fun embyWithholdsRowFieldsThatJellyfinVolunteers() {
        assertEquals("Overview", MediaBrowserDialect.JELLYFIN.withRowFields("Overview"))
        assertEquals(
            "Overview,ProductionYear,OfficialRating,PremiereDate,DateCreated,UserDataLastPlayedDate",
            MediaBrowserDialect.EMBY.withRowFields("Overview"),
        )
        assertEquals("fields already named are not repeated", "ProductionYear,Overview,OfficialRating,PremiereDate,DateCreated,UserDataLastPlayedDate", MediaBrowserDialect.EMBY.withRowFields("ProductionYear,Overview"))
        assertEquals("ProductionYear,OfficialRating,PremiereDate,DateCreated,UserDataLastPlayedDate", MediaBrowserDialect.EMBY.withRowFields(""))
    }

    @Test
    fun jellyfinUsesTheUnprefixedRoutesAndEmbyTheUserScopedOnes() {
        val jf = MediaBrowserPaths(MediaBrowserDialect.JELLYFIN, "u1")
        val emby = MediaBrowserPaths(MediaBrowserDialect.EMBY, "u1")
        assertEquals("/Users/Me", jf.currentUser); assertEquals("/Users/u1", emby.currentUser)
        assertEquals("/UserViews", jf.views); assertEquals("/Users/u1/Views", emby.views)
        assertEquals("/UserItems/Resume", jf.resumeItems); assertEquals("/Users/u1/Items/Resume", emby.resumeItems)
        assertEquals("/UserPlayedItems/i9", jf.playedItem("i9")); assertEquals("/Users/u1/PlayedItems/i9", emby.playedItem("i9"))
        assertEquals("/Items/i9", jf.item("i9")); assertEquals("/Users/u1/Items/i9", emby.item("i9"))
        assertEquals("/Items/Latest", jf.latest); assertEquals("/Users/u1/Items/Latest", emby.latest)
    }

    @Test
    fun idsAreEncodedIntoPaths() {
        assertEquals("/Users/a%2Fb/Items/x%20y", MediaBrowserPaths(MediaBrowserDialect.EMBY, "a/b").item("x y"))
    }

    @Test
    fun percentEncodingMatchesEncodeUriComponent() {
        assertEquals("Bj%C3%B8rn%27s%20PC".replace("%27", "'"), percentEncode("Bjørn's PC"))
        assertEquals("a%22b%2Cc%3Dd", percentEncode("a\"b,c=d"))
        assertEquals("AZaz09-_.!~*'()", percentEncode("AZaz09-_.!~*'()"))
    }

    private val identity = MediaBrowserClientIdentity("Tuvora", "Pixel 8", "dev-install-0042", "1.10.0")

    @Test
    fun theTokenlessHeaderCarriesAllFourIdentityFields() {
        assertEquals(
            "MediaBrowser Client=\"Tuvora\", Device=\"Pixel%208\", DeviceId=\"dev-install-0042\", Version=\"1.10.0\"",
            MediaBrowserAuth.authorization(identity),
        )
    }

    @Test
    fun theAuthenticatedHeaderAddsTheToken() {
        assertEquals(
            "MediaBrowser Client=\"Tuvora\", Device=\"Pixel%208\", DeviceId=\"dev-install-0042\", Version=\"1.10.0\", Token=\"abc123\"",
            MediaBrowserAuth.authorization(identity, "abc123"),
        )
    }

    @Test
    fun hostileValuesCannotBreakTheHeaderGrammar() {
        val h = MediaBrowserAuth.authorization(identity.copy(device = "Evil\", Token=\"stolen\r\nX-Injected: 1"), "tok")
        assertFalse(h, h.contains("\r") || h.contains("\n"))
        assertEquals("only OUR token pair: $h", 1, Regex("Token=").findAll(h).count())
        assertTrue(h, h.contains("%22"))
    }

    @Test
    fun blankFieldsFallBackButADeviceIdIsMandatory() {
        val h = MediaBrowserAuth.authorization(MediaBrowserClientIdentity("", "  ", "id1", ""))
        assertEquals("MediaBrowser Client=\"Tuvora\", Device=\"Tuvora\", DeviceId=\"id1\", Version=\"1.0\"", h)
        assertFailsWith<IllegalArgumentException> { MediaBrowserAuth.authorization(identity.copy(deviceId = "  ")) }
    }

    @Test
    fun embyGetsItsDocumentedTokenHeaderToo() {
        val jf = MediaBrowserAuth.authenticatedHeaders(MediaBrowserDialect.JELLYFIN, identity, "tok")
        assertEquals(setOf("Authorization"), jf.keys)
        val emby = MediaBrowserAuth.authenticatedHeaders(MediaBrowserDialect.EMBY, identity, "tok")
        assertEquals(setOf("Authorization", "X-Emby-Token"), emby.keys)
        assertEquals("tok", emby["X-Emby-Token"])
    }

    @Test
    fun theDirectStreamUrlNeverCarriesAToken() {
        val url = MediaBrowserUrls.directStream("http://nas:8096/", "item1", "src 1", container = "mkv")
        assertEquals("http://nas:8096/Videos/item1/stream?Static=true&MediaSourceId=src%201&Container=mkv", url)
        assertFalse(url.contains("ApiKey", ignoreCase = true) || url.contains("api_key") || url.contains("token", ignoreCase = true))
        assertEquals("http://nas/jf/Videos/i/stream?Static=true", MediaBrowserUrls.directStream("http://nas/jf", "i", null))
        assertEquals("http://nas/Videos/i/stream?Static=true&MediaSourceId=s&PlaySessionId=p", MediaBrowserUrls.directStream("http://nas", "i", "s", playSessionId = "p"))
    }

    @Test
    fun serverBuiltUrlsAreResolvedAgainstTheBaseAndKeepTheirOwnQuery() {
        assertEquals("http://nas:8096/videos/1/master.m3u8?ApiKey=server", MediaBrowserUrls.resolve("http://nas:8096/", "/videos/1/master.m3u8?ApiKey=server"))
        assertEquals("http://nas/jf/videos/1/master.m3u8", MediaBrowserUrls.resolve("http://nas/jf", "videos/1/master.m3u8"))
        assertEquals("https://cdn.example/x.mp4", MediaBrowserUrls.resolve("http://nas", "https://cdn.example/x.mp4"))
    }

    @Test
    fun imageUrlsAreAlwaysCredentialFreeForBothProducts() {
        // verified against Jellyfin 12.2 and Emby 4.10: image routes answer anonymously
        assertEquals("http://nas:8096/Items/i1/Images/Primary?tag=t1&maxWidth=300&quality=90", MediaBrowserUrls.image("http://nas:8096/", "i1", tag = "t1", maxWidth = 300))
        assertEquals("http://nas:8096/Items/i1/Images/Backdrop", MediaBrowserUrls.image("http://nas:8096", "i1", "Backdrop", quality = null))
        assertFalse(MediaBrowserUrls.image("http://nas", "i1", tag = "t").contains("key", ignoreCase = true))
    }
}
