package com.nuvio.tv.playback.media3

import com.nuvio.tv.playback.core.OriginBoundHeaders
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * B259: a Stalker portal's MAC cookie, token, Referer and Origin go to the portal host only. OkHttp
 * follows redirects itself and re-sends request headers to the new host (it drops only Authorization),
 * so the credentials live in a network interceptor that decides per hop — redirects included.
 */
class OriginBoundHeaderInterceptorTest {
    private lateinit var portal: MockWebServer
    private lateinit var foreign: MockWebServer

    @Before
    fun setUp() {
        portal = MockWebServer().also(MockWebServer::start)
        foreign = MockWebServer().also(MockWebServer::start)
    }

    @After
    fun tearDown() {
        portal.close()
        foreign.close()
    }

    // The two servers share a machine; "localhost" vs "127.0.0.1" makes them different HOSTS.
    private val bound get() = OriginBoundHeaders(
        host = "localhost",
        secureOrigin = false,
        headers = mapOf(
            "Cookie" to "mac=00%3A1A%3A79; stb_lang=en; timezone=Europe/London",
            "Authorization" to "Bearer tok",
            "Referer" to "http://localhost/c/",
            "Origin" to "http://localhost",
        ),
    )

    private fun client() = OkHttpClient.Builder().addNetworkInterceptor(OriginBoundHeaderInterceptor(bound)).build()

    @Test
    fun `portal host request carries the identity`() {
        portal.enqueue(MockResponse.Builder().code(200).body("ts").build())
        client().newCall(Request.Builder().url("http://localhost:${portal.port}/play/live.php").build()).execute().close()
        val sent = portal.takeRequest().headers
        assertEquals("cookie", bound.headers["Cookie"], sent["Cookie"])
        assertEquals("token", "Bearer tok", sent["Authorization"])
        assertEquals("referer", "http://localhost/c/", sent["Referer"])
        assertEquals("origin", "http://localhost", sent["Origin"])
    }

    @Test
    fun `a redirect to a foreign host strips every credential`() {
        portal.enqueue(
            MockResponse.Builder().code(302)
                .setHeader("Location", "http://127.0.0.1:${foreign.port}/edge/live.ts").build(),
        )
        foreign.enqueue(MockResponse.Builder().code(200).body("ts").build())

        client().newCall(Request.Builder().url("http://localhost:${portal.port}/play/live.php").build()).execute().close()

        assertEquals("first hop had the cookie", bound.headers["Cookie"], portal.takeRequest().headers["Cookie"])
        val edge = foreign.takeRequest().headers
        assertNull("no cookie on the foreign hop", edge["Cookie"])
        assertNull("no token on the foreign hop", edge["Authorization"])
        assertNull("no referer on the foreign hop", edge["Referer"])
        assertNull("no origin on the foreign hop", edge["Origin"])
    }

    @Test
    fun `credentials a caller put on the request are removed on a foreign host`() {
        foreign.enqueue(MockResponse.Builder().code(200).body("ts").build())
        client().newCall(
            Request.Builder().url("http://127.0.0.1:${foreign.port}/seg.ts").header("Cookie", "mac=leak").build(),
        ).execute().close()
        assertNull("stripped", foreign.takeRequest().headers["Cookie"])
    }
}
