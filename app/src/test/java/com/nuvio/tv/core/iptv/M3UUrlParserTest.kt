package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the M3U-URL -> XtreamAccount field mapping (URL in baseUrl, UA in username, stable id). */
class M3UUrlParserTest {

    @Test
    fun `full playlist url is kept verbatim in baseUrl`() {
        val a = m3uAccountFromUrl("http://host.example.com:8080/get.php?username=u1&password=p1&type=m3u_plus")!!
        assertEquals("http://host.example.com:8080/get.php?username=u1&password=p1&type=m3u_plus", a.baseUrl)
        assertEquals(XtreamAccount.SOURCE_URL, a.sourceType)
        assertTrue(a.isM3U())
    }

    @Test
    fun `id derives from scheme host port path and excludes query and UA`() {
        val a = m3uAccountFromUrl("http://host.example.com:8080/get.php?username=u1&password=p1", userAgent = "VLC/3.0")!!
        // creds (query) MUST NOT leak into the id; UA MUST NOT change it (stored separately)
        assertEquals("m3u:http://host.example.com:8080/get.php", a.id)
        val b = m3uAccountFromUrl("http://host.example.com:8080/get.php?username=u1&password=p1", userAgent = "Different/UA")!!
        assertEquals(a.id, b.id)  // same playlist, different UA -> same id (stable across UA edits)
    }

    @Test
    fun `optional user-agent is stored in username password stays empty`() {
        val withUa = m3uAccountFromUrl("http://h/x.m3u8", userAgent = "MyPlayer/1.0")!!
        assertEquals("MyPlayer/1.0", withUa.username)
        assertEquals("", withUa.password)
        val noUa = m3uAccountFromUrl("http://h/x.m3u8")!!
        assertEquals("", noUa.username)
    }

    @Test
    fun `bare host gets http scheme and default port is omitted from id`() {
        val a = m3uAccountFromUrl("host.example.net/playlist.m3u")!!
        assertEquals("http://host.example.net/playlist.m3u", a.baseUrl)
        assertEquals("m3u:http://host.example.net/playlist.m3u", a.id)
    }

    @Test
    fun `custom name honored else host used`() {
        assertEquals("My List", m3uAccountFromUrl("http://h/x.m3u", name = "My List")!!.name)
        assertEquals("h", m3uAccountFromUrl("http://h/x.m3u")!!.name)
    }

    @Test
    fun `blank or unparseable url returns null`() {
        assertNull(m3uAccountFromUrl(""))
        assertNull(m3uAccountFromUrl("   "))
        assertNull(m3uAccountFromUrl("not a url"))
    }

    @Test
    fun `isM3U false for an xtream account`() {
        val xtream = XtreamAccount(id = "http://h|u", name = "P", baseUrl = "http://h", username = "u", password = "p")
        assertFalse(xtream.isM3U())
    }
}
