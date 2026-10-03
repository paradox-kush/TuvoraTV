package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.nuvio.tv.core.iptv.XtreamAccount
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * B02 at the session (twin of Mobile's StalkerEmptyReplySessionTest): an empty envelope after a
 * re-handshake (genuine Ministra has no type=series) must name the section — not "in use elsewhere" —
 * and must not trip the two-device cooldown.
 */
class StalkerEmptyReplySessionTest {

    private lateinit var server: MockWebServer
    @Volatile private var seriesBody = """{"js":null}"""

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when {
                    request.url.queryParameter("action") == "handshake" -> """{"js":{"token":"T"}}"""
                    request.url.queryParameter("action") == "get_profile" -> """{"js":{"id":"1","status":0}}"""
                    request.url.queryParameter("type") == "series" -> seriesBody
                    else -> """{"js":[]}"""
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        unmockkStatic(Log::class)
    }

    private fun session() = StalkerSession(
        XtreamAccount(
            id = "b02", name = "My portal", baseUrl = "", username = "", password = "", sourceType = "stalker",
            portalUrl = server.url("/").toString().trimEnd('/'), macAddress = "00:1A:79:58:B3:A6",
        ),
        OkHttpClient(),
    )

    private suspend fun failureOf(s: StalkerSession): String = try {
        s.request(mapOf("type" to "series", "action" to "get_categories"))
        fail("expected a failure"); ""
    } catch (e: StalkerSessionUnavailableException) {
        e.message.orEmpty()
    }

    @Test
    fun `an empty envelope names the section and does not blame another device`() = runBlocking {
        val s = session()
        val first = failureOf(s)
        assertTrue(first, first.contains("Series"))
        assertFalse(first, first.contains("elsewhere"))
        val second = failureOf(s)
        assertFalse("no two-device cooldown: $second", second.contains("cooling down"))
        s.shutdown()
    }

    @Test
    fun `an error page is quoted`() = runBlocking {
        seriesBody = "<html><body>Fatal error: unknown module series</body></html>"
        val s = session()
        val msg = failureOf(s)
        assertTrue(msg, msg.contains("Fatal error: unknown module series"))
        s.shutdown()
    }
}
