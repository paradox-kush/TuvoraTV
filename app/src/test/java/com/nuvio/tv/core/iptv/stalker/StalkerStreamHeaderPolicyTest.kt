package com.nuvio.tv.core.iptv.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B259: a Stalker stream request presents the portal's identity to the portal's own host only, and a
 * credential-free player profile everywhere else (iptvnator's same-origin rule, our implementation).
 */
class StalkerStreamHeaderPolicyTest {

    private val identity = StalkerStreamIdentity(
        portalBase = "http://portal.example.com:8080",
        referer = "http://portal.example.com:8080/c/",
        cookie = "mac=00%3A1A%3A79%3A00%3A00%3A01; stb_lang=en; timezone=Europe/London",
        magUserAgent = "MAG-UA",
        magXUserAgent = "Model: MAG250; Link: WiFi",
        token = "tok123",
    )

    @Test
    fun `portal host on another port gets the full portal identity`() {
        val d = decide("http://portal.example.com/play/live.php?stream=1&play_token=x")
        assertEquals("profile", StalkerStreamHeaderPolicy.Profile.PORTAL_IDENTITY, d.profile)
        assertEquals("MAG preset UA when no override", "MAG-UA", d.userAgent)
        assertEquals("box model", "Model: MAG250; Link: WiFi", d.headers["X-User-Agent"])
        assertEquals("accept", "*/*", d.headers["Accept"])
        val bound = assertNotNullBound(d)
        assertEquals("cookie", identity.cookie, bound.headers["Cookie"])
        assertEquals("bearer", "Bearer tok123", bound.headers["Authorization"])
        assertEquals("referer", "http://portal.example.com:8080/c/", bound.headers["Referer"])
        assertEquals("origin keeps the portal port", "http://portal.example.com:8080", bound.headers["Origin"])
        assertEquals("bound host", "portal.example.com", bound.host)
        assertFalse("credentials never in the every-hop map", d.headers.keys.any { it in SECRET_NAMES })
    }

    @Test
    fun `playlist user agent override wins on the portal host`() {
        val d = decide("http://portal.example.com:8080/ch/1", override = "VLC/3.0.20 LibVLC/3.0.20")
        assertEquals("override", "VLC/3.0.20 LibVLC/3.0.20", d.userAgent)
        assertEquals("still the portal profile", StalkerStreamHeaderPolicy.Profile.PORTAL_IDENTITY, d.profile)
    }

    @Test
    fun `foreign host gets a credential free neutral player profile`() {
        val d = decide("http://cdn.other-host.net/live/1.ts?token=abc")
        assertEquals("profile", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER, d.profile)
        assertEquals("neutral UA", "Tuvora/1.21.2 (Linux; Android TV)", d.userAgent)
        assertNull("no origin-bound credentials", d.originBound)
        assertEquals("only Accept", mapOf("Accept" to "*/*"), d.headers)
        assertFalse("never claims to be a browser", d.userAgent.contains("Mozilla"))
        assertFalse("no Range, engines own it", d.headers.keys.any { it.equals("Range", true) })
    }

    @Test
    fun `foreign host keeps the playlist override user agent`() {
        val d = decide("http://cdn.other-host.net/live/1.ts", override = "IBOPlayer")
        assertEquals("override wins everywhere", "IBOPlayer", d.userAgent)
        assertNull("still no credentials", d.originBound)
    }

    @Test
    fun `a host that merely ends with the portal host is foreign`() {
        assertFalse("suffix lookalike", StalkerStreamHeaderPolicy.isPortalHost("http://evilportal.example.com/x", "http://portal.example.com"))
        assertFalse("subdomain", StalkerStreamHeaderPolicy.isPortalHost("http://cdn.portal.example.com/x", "http://portal.example.com"))
        assertTrue("case-insensitive", StalkerStreamHeaderPolicy.isPortalHost("http://PORTAL.example.com:9000/x", "http://portal.example.com"))
    }

    @Test
    fun `https portal streamed over http is not the portal`() {
        val secure = identity.copyPortal("https://portal.example.com")
        val d = StalkerStreamHeaderPolicy.decide("http://portal.example.com/ch/1", secure, null, "1.21.2")
        assertEquals("downgrade is foreign", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER, d.profile)
        assertNull("no MAC in clear", d.originBound)
        val upgraded = StalkerStreamHeaderPolicy.decide("https://portal.example.com/ch/1", identity, null, "1.21.2")
        assertEquals("http portal to https stream is fine", StalkerStreamHeaderPolicy.Profile.PORTAL_IDENTITY, upgraded.profile)
    }

    @Test
    fun `redirect hops carry credentials only to the portal host and never downgraded`() {
        val bound = assertNotNullBound(
            StalkerStreamHeaderPolicy.decide("https://portal.example.com/ch/1", identity.copyPortal("https://portal.example.com"), null, "1"),
        )
        assertTrue("same host https", bound.allowsHop("https", "portal.example.com"))
        assertFalse("same host http after https", bound.allowsHop("http", "portal.example.com"))
        assertFalse("foreign redirect target", bound.allowsHop("https", "edge.cdn.net"))
        assertFalse("non-http scheme", bound.allowsHop("rtmp", "portal.example.com"))
    }

    @Test
    fun `no token means no Authorization header`() {
        val noToken = StalkerStreamIdentity(identity.portalBase, identity.referer, identity.cookie, "MAG-UA", "X", token = null)
        val bound = assertNotNullBound(StalkerStreamHeaderPolicy.decide("http://portal.example.com/x", noToken, null, "1"))
        assertFalse("no bearer", bound.headers.containsKey("Authorization"))
        assertEquals("cookie still", noToken.cookie, bound.headers["Cookie"])
    }

    @Test
    fun `no identity and unparseable urls fall back to the neutral profile`() {
        assertEquals("no identity", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER,
            StalkerStreamHeaderPolicy.decide("http://portal.example.com/x", null, null, "1").profile)
        assertEquals("garbage url", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER,
            StalkerStreamHeaderPolicy.decide("not a url", identity, null, "1").profile)
    }

    @Test
    fun `portable headers never carry a credential`() {
        val portal = StalkerStreamHeaderPolicy.portableHeaders("http://portal.example.com/m/1.mkv", identity, null, "1.21.2")
        assertEquals("portal: MAG identity without secrets",
            mapOf("User-Agent" to "MAG-UA", "X-User-Agent" to "Model: MAG250; Link: WiFi", "Accept" to "*/*"), portal)
        val foreign = StalkerStreamHeaderPolicy.portableHeaders("http://cdn.net/m/1.mkv", identity, "UA-X", "1.21.2")
        assertEquals("foreign: override + Accept", mapOf("User-Agent" to "UA-X", "Accept" to "*/*"), foreign)
    }

    @Test
    fun `nothing secret is printable`() {
        val d = decide("http://portal.example.com/x")
        assertFalse("decision", d.toString().contains("tok123") || d.toString().contains("00%3A1A"))
        assertFalse("identity", identity.toString().contains("tok123"))
    }

    @Test
    fun `the VOD player's stream gets portable headers replacing a stale user agent`() {
        val stream = com.nuvio.tv.domain.model.Stream(
            name = "Movie", title = null, description = null, url = "http://portal.example.com/m/1.mkv", ytId = null,
            infoHash = null, fileIdx = null, externalUrl = null,
            behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(null, null, null, null).copy(
                proxyHeaders = com.nuvio.tv.domain.model.ProxyHeaders(request = mapOf("user-agent" to "old", "X-Keep" to "1"), response = null),
            ),
            addonName = "Portal", addonLogo = null,
        )
        val headed = StalkerStreamHeaderPolicy.withPortableHeaders(
            stream, StalkerStreamHeaderPolicy.portableHeaders(stream.url!!, identity, null, "1"),
        )
        val sent = headed.behaviorHints!!.proxyHeaders!!.request!!
        assertEquals("MAG UA replaces the stale one", "MAG-UA", sent["User-Agent"])
        assertFalse("no duplicate lower-case key", sent.containsKey("user-agent"))
        assertEquals("other headers kept", "1", sent["X-Keep"])
        assertFalse("never a credential", sent.keys.any { it in SECRET_NAMES })
    }

    @Test
    fun `a deferred url names its playlist even though playlist ids contain bars`() {
        assertEquals(
            "account id", "stalker|http://portal|00:1A",
            com.nuvio.tv.core.iptv.match.XtreamStreamSource.deferredAccountId("stalker-deferred:stalker|http://portal|00:1A|movie|7|Heat"),
        )
        assertNull("not deferred", com.nuvio.tv.core.iptv.match.XtreamStreamSource.deferredAccountId("http://x/y"))
    }

    private fun decide(url: String, override: String? = null) =
        StalkerStreamHeaderPolicy.decide(url, identity, override, appVersion = "1.21.2")

    private fun assertNotNullBound(d: StalkerStreamHeaderPolicy.Decision) =
        d.originBound.also { assertNotNull("portal identity expected", it) }!!

    private fun StalkerStreamIdentity.copyPortal(base: String) =
        StalkerStreamIdentity(base, "$base/c/", cookie, magUserAgent, magXUserAgent, token)

    private companion object {
        val SECRET_NAMES = setOf("Cookie", "Authorization", "Referer", "Origin")
    }
}
