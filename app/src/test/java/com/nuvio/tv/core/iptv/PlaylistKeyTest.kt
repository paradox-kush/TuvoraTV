package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 0 — the shared playlist-key builder's golden vectors. The table below is byte-identical in
 * NuvioMobile/NuvioDesktop (`PlaylistKeyTest`, kotlin.test) and nuvio-web (`playlistKey.test.ts`):
 * a playlist added on any of them must get the same permanent id.
 */
class PlaylistKeyTest {

    private data class V(val type: String, val a: String, val b: String, val expected: String?)

    // ---- GOLDEN VECTORS (keep identical across Kotlin + TS) ----
    private val vectors = listOf(
        V("xtream", "http://example.com:8080", "user", "http://example.com:8080|user"),
        V("xtream", "HTTP://Example.COM:8080", "user", "http://example.com:8080|user"),
        V("xtream", "http://example.com:80", "user", "http://example.com|user"),
        V("xtream", "https://example.com:443", "user", "https://example.com|user"),
        V("xtream", "https://example.com:80", "user", "https://example.com:80|user"),
        V("xtream", "http://example.com:443", "user", "http://example.com:443|user"),
        V("xtream", "http://example.com:8080/", "user", "http://example.com:8080|user"),
        V("xtream", "http://example.com:8080/player_api.php?username=u&password=p", "user", "http://example.com:8080|user"),
        V("xtream", "example.com:8080", "user", "http://example.com:8080|user"),
        V("xtream", "Example.com", "user", "http://example.com|user"),
        V("xtream", "  http://example.com:8080  ", "  User Name  ", "http://example.com:8080|User Name"),
        V("xtream", "http://192.168.1.10:25461/c/", "u", "http://192.168.1.10:25461|u"),
        V("xtream", "http://[2001:DB8::1]:8080/", "u", "http://[2001:db8::1]:8080|u"),
        V("xtream", "http://example.com:", "u", "http://example.com|u"),
        V("xtream", "http://example.com:99999", "u", null),
        V("xtream", "", "u", null),
        V("xtream", "http://example.com", "   ", null),
        V("m3u", "http://example.com/list.m3u?user=a&pass=b", "", "m3u|http://example.com/list.m3u|ue40c292c"),
        V("m3u", "example.com/list.m3u", "", "m3u|http://example.com/list.m3u"),
        V("m3u", "HTTPS://Example.com/List.m3u", "", "m3u|HTTPS://Example.com/List.m3u"),
        V("m3u", "  http://example.com/a.m3u  ", "", "m3u|http://example.com/a.m3u"),
        V("m3u", "http://example.com:80/a.m3u", "", "m3u|http://example.com:80/a.m3u"),
        V("m3u", "   ", "", null),
        V("stalker", "http://portal.example.com:8080/stalker_portal/c/", "00:1a:79:ab:cd:ef", "stalker|http://portal.example.com:8080|00:1A:79:AB:CD:EF"),
        V("stalker", "Portal.Example.com", " 00:1A:79:AB:CD:EF ", "stalker|http://portal.example.com|00:1A:79:AB:CD:EF"),
        V("stalker", "https://portal.example.com:443/c", "00:1a:79:00:00:01", "stalker|https://portal.example.com|00:1A:79:00:00:01"),
        V("stalker", "http://portal.example.com", "", null),
        V("m3u_file", "My List.m3u", "1719000000000", "m3u_file|My List.m3u|1719000000000"),
        V("m3u_file", "  tv.m3u ", "42", "m3u_file|tv.m3u|42"),
        V("m3u_file", "", "42", null),
    )
    // ---- END GOLDEN VECTORS ----

    private fun build(v: V): String? = when (v.type) {
        "xtream" -> PlaylistKey.xtream(v.a, v.b)
        "m3u" -> PlaylistKey.m3uUrl(v.a)
        "stalker" -> PlaylistKey.stalker(v.a, v.b)
        "m3u_file" -> PlaylistKey.m3uFile(v.a, v.b.toLong())
        else -> error("unknown vector type ${v.type}")
    }

    @Test
    fun `every golden vector builds its expected key`() {
        val failures = vectors.mapNotNull { v ->
            val got = build(v)
            if (got == v.expected) null else "$v -> $got"
        }
        assertTrue("golden vector mismatches:\n" + failures.joinToString("\n"), failures.isEmpty())
        assertEquals("the table is the shared oracle — keep its size in step with the twins", 30, vectors.size)
    }

    @Test
    fun `the add-playlist builders mint ids with the shared key builder`() {
        assertEquals("http://panel.example.com|u", xtreamAccountFromFields("HTTP://Panel.Example.com:80/", "u", "p")!!.id)
        assertEquals("http://panel.example.com:8080|u", parseXtreamAccount("http://Panel.Example.com:8080/get.php?username=u&password=p")!!.id)
        assertEquals("m3u|http://example.com/l.m3u?x=1", m3uAccountFromUrl("example.com/l.m3u?x=1")!!.id)
        assertEquals("m3u_file|tv.m3u|7", newM3UFilePlaylistId("tv.m3u", nowMs = 7L))
    }
}
