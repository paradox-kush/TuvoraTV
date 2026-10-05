package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W2 device pass (T7, P6) — TV twin of NuvioMobile's SavedChannelDisplayNameTest / XtreamPlaylistModelTest vectors. */
class PlaylistDisplayPolicyTest {

    // --- T7: a playlist URL as shown never carries the login -------------------------------------

    @Test
    fun `an m3u link shows with its login masked`() {
        val shown = PlaylistDisplayPolicy.maskedUrl("http://10.0.2.2:8913/playlist.m3u?username=demo&password=demo123")
        assertFalse("no password on screen: $shown", shown.contains("demo123"))
        assertFalse("no username on screen: $shown", shown.contains("=demo&"))
        assertEquals("host and path stay readable", "http://10.0.2.2:8913/playlist.m3u?username=***&password=***", shown)
    }

    @Test
    fun `an xtream route and user-info show masked`() {
        val route = PlaylistDisplayPolicy.maskedUrl("http://panel.example:8080/live/alice/s3cret/12.ts")
        assertFalse("route password masked: $route", route.contains("s3cret"))
        val userInfo = PlaylistDisplayPolicy.maskedUrl("https://alice:s3cret@lists.example/tv.m3u")
        assertFalse("user-info masked: $userInfo", userInfo.contains("s3cret"))
    }

    @Test
    fun `a plain server address is shown as it is`() {
        assertEquals("nothing to mask", "http://panel.example:8080", PlaylistDisplayPolicy.maskedUrl("http://panel.example:8080"))
    }

    // --- P4: a playlist NAME never carries the login (TV twin of Mobile PlaylistLoginMaskingTest) ---

    private val m3u = "http://host.example:8080/get.php?username=alice&password=s3cret&type=m3u_plus"

    private fun assertNoLogin(what: String, shown: String) {
        assertFalse("$what shows the username: $shown", shown.contains("alice"))
        assertFalse("$what shows the password: $shown", shown.contains("s3cret"))
    }

    @Test
    fun `a stored name that is a full address displays as its host`() {
        // The name older builds gave a nameless synced row — and pushed back to the server.
        assertEquals("whole address -> host", "host.example:8080", PlaylistDisplayPolicy.displayName(m3u))
        assertNoLogin("name with an address inside", PlaylistDisplayPolicy.displayName("My list $m3u"))
        assertEquals("ordinary name untouched", "UK Sports", PlaylistDisplayPolicy.displayName("UK Sports"))
        assertEquals("user-info address -> host", "lists.example", PlaylistDisplayPolicy.displayName("https://alice:s3cret@lists.example/tv.m3u"))
    }

    @Test
    fun `a nameless synced row is named after its host not its login`() {
        assertEquals("m3u link", "host.example:8080", PlaylistDisplayPolicy.fallbackName(m3u))
        assertEquals("xtream server", "panel.example:8080", PlaylistDisplayPolicy.fallbackName("http://panel.example:8080"))
        assertNoLogin("user-info link", PlaylistDisplayPolicy.fallbackName("http://alice:s3cret@h.example/l.m3u"))
    }

    @Test
    fun `a paired nameless m3u playlist is not named after its link`() {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("source_type", kotlinx.serialization.json.JsonPrimitive("xtream"))
            put("base_url", kotlinx.serialization.json.JsonPrimitive("http://alice:s3cret@panel.example:8080"))
            put("username", kotlinx.serialization.json.JsonPrimitive("alice"))
            put("password", kotlinx.serialization.json.JsonPrimitive("s3cret"))
        }
        val account = pairingPayloadToXtreamAccount(obj)!!
        assertNoLogin("paired fallback name", account.name)
        assertEquals("host only", "panel.example:8080", account.name)
    }

    // --- P6: favourites / recents show the playlist's cleaned names --------------------------------

    private val clean = XtreamAccount(
        id = "http://c:80|u", name = "Clean", baseUrl = "http://c:80", username = "u", password = "p",
        cleanChannelNames = true,
    )
    private val raw = clean.copy(id = "http://x:80|u", cleanChannelNames = false)

    @Test
    fun `a favourite of a cleaned playlist shows the cleaned name`() {
        val id = XtreamItemRegistry.liveId(clean.id, 7)
        assertEquals("cleaned like the playlist's rows", "Channel 4", PlaylistDisplayPolicy.savedChannelDisplayName("|UK| Channel 4 HD", id, listOf(raw, clean)))
        assertEquals("T8 vector through the same path", "CNN", PlaylistDisplayPolicy.savedChannelDisplayName("|EN| CNN", id, listOf(clean)))
    }

    @Test
    fun `a favourite of an uncleaned or unknown playlist keeps its name`() {
        assertEquals("opted out", "|UK| Channel 4 HD", PlaylistDisplayPolicy.savedChannelDisplayName("|UK| Channel 4 HD", XtreamItemRegistry.liveId(raw.id, 7), listOf(raw, clean)))
        assertEquals("unknown playlist", "|UK| Channel 4 HD", PlaylistDisplayPolicy.savedChannelDisplayName("|UK| Channel 4 HD", XtreamItemRegistry.liveId("gone|x", 7), listOf(raw, clean)))
        assertEquals("not an IPTV id", "|UK| Channel 4 HD", PlaylistDisplayPolicy.savedChannelDisplayName("|UK| Channel 4 HD", "tt0133093", listOf(raw, clean)))
    }

    // --- P6: an edit of the guide sources re-reads the guide ----------------------------------------

    @Test
    fun `an edit that changes the guide sources re-reads the guide`() {
        val old = raw.copy(epgUrl = "http://a.example/epg.xml", name = "Old")
        assertFalse("rename + DNS only", PlaylistDisplayPolicy.guideSourcesChanged(old, old.copy(name = "Renamed", dnsProvider = "google")))
        assertFalse("same list, other whitespace", PlaylistDisplayPolicy.guideSourcesChanged(old, old.copy(epgUrl = "  http://a.example/epg.xml \n")))
        assertTrue("a second EPG URL", PlaylistDisplayPolicy.guideSourcesChanged(old, old.copy(epgUrl = "http://a.example/epg.xml\nhttp://b.example/epg.xml")))
        assertTrue("EPG URL removed", PlaylistDisplayPolicy.guideSourcesChanged(old, old.copy(epgUrl = null)))
        assertTrue("server moved", PlaylistDisplayPolicy.guideSourcesChanged(old, old.copy(baseUrl = "http://moved:80")))
        assertTrue("password rotated", PlaylistDisplayPolicy.guideSourcesChanged(old, old.copy(password = "rotated")))
    }
}
