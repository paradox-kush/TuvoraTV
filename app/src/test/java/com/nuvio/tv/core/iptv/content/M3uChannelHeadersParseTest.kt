package com.nuvio.tv.core.iptv.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-channel request headers in an M3U (TiviMate / VLC / Kodi conventions). JUnit: (message, expected, actual). */
class M3uChannelHeadersParseTest {

    private fun parse(text: String): List<M3UEntry> {
        val out = mutableListOf<M3UEntry>()
        M3UParser.parseStream(text.trimIndent().reader().buffered()) { out += it }
        return out
    }

    @Test
    fun `EXTINF attributes carry user agent and referrer`() {
        val e = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" http-user-agent="Agent/1.0" http-referrer="https://ref.example/",Chan A
            http://h/live/u/p/1.ts
            #EXTINF:-1 user-agent="Agent/2.0" referer="https://r2.example/",Chan B
            http://h/live/u/p/2.ts
            #EXTINF:-1 http-referer="https://r3.example/",Chan C
            http://h/live/u/p/3.ts
            """,
        )
        assertEquals("http-user-agent", M3uChannelHeaders(userAgent = "Agent/1.0", referer = "https://ref.example/"), e[0].headers)
        assertEquals("user-agent + referer", M3uChannelHeaders(userAgent = "Agent/2.0", referer = "https://r2.example/"), e[1].headers)
        assertEquals("http-referer spelling", M3uChannelHeaders(referer = "https://r3.example/"), e[2].headers)
    }

    @Test
    fun `EXTVLCOPT lines apply whether they come before or after EXTINF`() {
        val e = parse(
            """
            #EXTM3U
            #EXTINF:-1,After
            #EXTVLCOPT:http-user-agent=VLC/3.0.20 LibVLC/3.0.20
            #EXTVLCOPT:http-referrer=https://after.example/
            http://h/live/u/p/1.ts
            #EXTVLCOPT:http-user-agent=Before/1
            #EXTINF:-1,Before
            http://h/live/u/p/2.ts
            #EXTINF:-1,Plain
            http://h/live/u/p/3.ts
            """,
        )
        assertEquals("after EXTINF", M3uChannelHeaders(userAgent = "VLC/3.0.20 LibVLC/3.0.20", referer = "https://after.example/"), e[0].headers)
        assertEquals("before EXTINF", M3uChannelHeaders(userAgent = "Before/1"), e[1].headers)
        assertNull("directives never leak onto the next channel", e[2].headers)
    }

    @Test
    fun `EXTHTTP JSON keeps only the allow-listed headers`() {
        val e = parse(
            """
            #EXTM3U
            #EXTINF:-1,Json
            #EXTHTTP:{"User-Agent":"JsonAgent","Referer":"https://j.example/","Origin":"https://o.example","Cookie":"session=secret","X-Other":"x"}
            http://h/live/u/p/1.ts
            """,
        )
        assertEquals(
            "Cookie and unknown keys are dropped",
            M3uChannelHeaders(userAgent = "JsonAgent", referer = "https://j.example/", origin = "https://o.example"),
            e[0].headers,
        )
    }

    @Test
    fun `garbage EXTHTTP is ignored and the channel still imports`() {
        val e = parse(
            """
            #EXTM3U
            #EXTINF:-1 http-user-agent="Attr/1",Broken
            #EXTHTTP:{"User-Agent": not json
            http://h/live/u/p/1.ts
            #EXTINF:-1,Array
            #EXTHTTP:["User-Agent","x"]
            http://h/live/u/p/2.ts
            """,
        )
        assertEquals("both channels imported", 2, e.size)
        assertEquals("the attribute survives broken JSON", M3uChannelHeaders(userAgent = "Attr/1"), e[0].headers)
        assertNull("a non-object JSON carries nothing", e[1].headers)
    }

    @Test
    fun `values with line breaks or blanks are refused`() {
        val headers = M3UParser.channelHeadersOf(mapOf("user-agent" to "  ", "referer" to "a\r\nX-Evil: 1"))
        assertNull(headers)
    }

    @Test
    fun `the headers never print their values`() {
        val text = M3uChannelHeaders(userAgent = "SecretAgent", referer = "https://private.example/").toString()
        assertFalse(text, text.contains("SecretAgent") || text.contains("private.example"))
    }
}
