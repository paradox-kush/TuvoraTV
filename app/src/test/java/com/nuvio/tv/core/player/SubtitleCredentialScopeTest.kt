package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards `fix(player): keep subtitle-supplied credentials off other hosts`.
 *
 * Two leak vectors this locks down:
 *  (A) a same-host subtitle URL that redirects to a foreign host — creds must be stripped on the hop.
 *  (B) an https stream forwarding creds to an http (downgraded) subtitle hop — must forward nothing.
 */
class SubtitleCredentialScopeTest {

    private val creds = mapOf(
        "Authorization" to "Bearer secret",
        "Cookie" to "sid=abc",
        "X-Debrid-Token" to "tok",
    )

    @Test
    fun `same host, no downgrade, forwards credential headers`() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "https://host.example/stream.mkv",
            subtitleUrl = "https://host.example/sub.srt",
            streamHeaders = creds,
        )
        assertEquals("all credential headers forwarded on a same-host, same-scheme hop", creds, out)
    }

    @Test
    fun `foreign subtitle host forwards nothing`() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "https://host.example/stream.mkv",
            subtitleUrl = "https://opensubtitles.org/sub.srt",
            streamHeaders = creds,
        )
        assertTrue("no stream credentials may cross to a foreign subtitle host", out.isEmpty())
    }

    @Test
    fun `https stream to http subtitle downgrade forwards nothing`() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "https://host.example/stream.mkv",
            subtitleUrl = "http://host.example/sub.srt",
            streamHeaders = creds,
        )
        assertTrue("credentials must not ride an https->http downgrade even on the same host", out.isEmpty())
    }

    @Test
    fun `http stream to http subtitle same host forwards`() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "http://host.example/stream.mkv",
            subtitleUrl = "http://host.example/sub.srt",
            streamHeaders = creds,
        )
        assertEquals("no downgrade when the stream itself is http", creds, out)
    }

    @Test
    fun `hop-by-hop and addressing headers are never forwarded`() {
        val out = SubtitleCredentialScope.forwardableStreamHeaders(
            streamUrl = "https://host.example/stream.mkv",
            subtitleUrl = "https://host.example/sub.srt",
            streamHeaders = mapOf(
                "Authorization" to "Bearer secret",
                "Range" to "bytes=0-",
                "Host" to "host.example",
                "connection" to "keep-alive",
                "Transfer-Encoding" to "chunked",
            ),
        )
        assertEquals("only Authorization survives the hop-by-hop filter", mapOf("Authorization" to "Bearer secret"), out)
    }

    @Test
    fun `non-http subtitle or missing stream url forwards nothing`() {
        assertTrue(
            SubtitleCredentialScope.forwardableStreamHeaders(
                streamUrl = "https://host.example/stream.mkv",
                subtitleUrl = "ftp://host.example/sub.srt",
                streamHeaders = creds,
            ).isEmpty()
        )
        assertTrue(
            SubtitleCredentialScope.forwardableStreamHeaders(
                streamUrl = null,
                subtitleUrl = "https://host.example/sub.srt",
                streamHeaders = creds,
            ).isEmpty()
        )
    }

    @Test
    fun `redirect leaving origin host is detected, staying is not`() {
        val origin = "https://host.example/sub.srt"
        assertFalse(
            "same host (path change only) is not a leaving hop",
            SubtitleCredentialScope.redirectLeavesOriginHost(origin, "https://host.example/cdn/sub.srt")
        )
        assertTrue(
            "a hop to another host is a leaving hop",
            SubtitleCredentialScope.redirectLeavesOriginHost(origin, "https://cdn.other.net/sub.srt")
        )
        assertTrue(
            "an unparseable hop fails closed",
            SubtitleCredentialScope.redirectLeavesOriginHost(origin, "not a url")
        )
    }
}
