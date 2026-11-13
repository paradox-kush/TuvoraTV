package com.nuvio.tv.core.iptv.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B259: the MAG identity is presented to the portal's own host only, and only when the playlist's
 * stream identity is IDENTITY (StalkerStreamIdentityPolicy). The default everywhere is the request TV
 * sent before 1.21.3: the playlist's User-Agent override or none (the engine's default), nothing else.
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
    fun `foreign host gets the pre-1_21_3 request even when the playlist uses the identity`() {
        val d = decide("http://cdn.other-host.net/live/1.ts?token=abc")
        assertEquals("profile", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER, d.profile)
        assertNull("no UA of our own: the engine default, as before 1.21.3", d.userAgent)
        assertNull("no origin-bound credentials", d.originBound)
        assertTrue("no headers at all", d.headers.isEmpty())
    }

    @Test
    fun `the default on the portal host is the pre-1_21_3 request`() {
        val d = StalkerStreamHeaderPolicy.decide("http://portal.example.com:8080/play/live.php?stream=1", identity, userAgentOverride = null)
        assertEquals("profile", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER, d.profile)
        assertNull("no MAG UA: the engine default", d.userAgent)
        assertTrue("no X-User-Agent, no Accept, nothing", d.headers.isEmpty())
        assertNull("no Cookie/Authorization/Referer/Origin", d.originBound)
        val overridden = StalkerStreamHeaderPolicy.decide("http://portal.example.com:8080/x", identity, userAgentOverride = "VLC/3.0.20")
        assertEquals("only the playlist override", "VLC/3.0.20", overridden.userAgent)
        assertTrue("and nothing else", overridden.headers.isEmpty())
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
        val d = StalkerStreamHeaderPolicy.decide("http://portal.example.com/ch/1", secure, null, streamIdentity = IDENTITY)
        assertEquals("downgrade is foreign", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER, d.profile)
        assertNull("no MAC in clear", d.originBound)
        val upgraded = StalkerStreamHeaderPolicy.decide("https://portal.example.com/ch/1", identity, null, streamIdentity = IDENTITY)
        assertEquals("http portal to https stream is fine", StalkerStreamHeaderPolicy.Profile.PORTAL_IDENTITY, upgraded.profile)
    }

    @Test
    fun `redirect hops carry credentials only to the portal host and never downgraded`() {
        val bound = assertNotNullBound(
            StalkerStreamHeaderPolicy.decide("https://portal.example.com/ch/1", identity.copyPortal("https://portal.example.com"), null, streamIdentity = IDENTITY),
        )
        assertTrue("same host https", bound.allowsHop("https", "portal.example.com"))
        assertFalse("same host http after https", bound.allowsHop("http", "portal.example.com"))
        assertFalse("foreign redirect target", bound.allowsHop("https", "edge.cdn.net"))
        assertFalse("non-http scheme", bound.allowsHop("rtmp", "portal.example.com"))
    }

    @Test
    fun `no token means no Authorization header`() {
        val noToken = StalkerStreamIdentity(identity.portalBase, identity.referer, identity.cookie, "MAG-UA", "X", token = null)
        val bound = assertNotNullBound(StalkerStreamHeaderPolicy.decide("http://portal.example.com/x", noToken, null, streamIdentity = IDENTITY))
        assertFalse("no bearer", bound.headers.containsKey("Authorization"))
        assertEquals("cookie still", noToken.cookie, bound.headers["Cookie"])
    }

    @Test
    fun `no identity and unparseable urls fall back to the neutral profile`() {
        assertEquals("no identity", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER,
            StalkerStreamHeaderPolicy.decide("http://portal.example.com/x", null, null, streamIdentity = IDENTITY).profile)
        assertEquals("garbage url", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER,
            StalkerStreamHeaderPolicy.decide("not a url", identity, null, streamIdentity = IDENTITY).profile)
    }

    @Test
    fun `nothing secret is printable`() {
        val d = decide("http://portal.example.com/x")
        assertFalse("decision", d.toString().contains("tok123") || d.toString().contains("00%3A1A"))
        assertFalse("identity", identity.toString().contains("tok123"))
    }

    @Test
    fun `the neutral stream identity on the portal host carries no MAG identity and no credential`() {
        val d = StalkerStreamHeaderPolicy.decide(
            "http://portal.example.com:8080/play/live.php?stream=1&play_token=x",
            identity,
            userAgentOverride = null,
            streamIdentity = StalkerStreamIdentityPolicy.StreamIdentity.NEUTRAL,
        )
        assertEquals("profile", StalkerStreamHeaderPolicy.Profile.NEUTRAL_PLAYER, d.profile)
        assertNull("no MAG UA: the engine default", d.userAgent)
        assertNull("no box model", d.headers["X-User-Agent"])
        assertNull("no origin-bound Cookie/Authorization/Referer/Origin", d.originBound)
        assertTrue("no headers", d.headers.isEmpty())
    }

    /** The identity-profile cases: the playlist presents the MAG identity (the one-shot fallback). */
    private fun decide(url: String, override: String? = null) =
        StalkerStreamHeaderPolicy.decide(url, identity, override, streamIdentity = IDENTITY)

    private fun assertNotNullBound(d: StalkerStreamHeaderPolicy.Decision) =
        d.originBound.also { assertNotNull("portal identity expected", it) }!!

    private fun StalkerStreamIdentity.copyPortal(base: String) =
        StalkerStreamIdentity(base, "$base/c/", cookie, magUserAgent, magXUserAgent, token)

    private companion object {
        val IDENTITY = StalkerStreamIdentityPolicy.StreamIdentity.IDENTITY
        val SECRET_NAMES = setOf("Cookie", "Authorization", "Referer", "Origin")
    }
}
