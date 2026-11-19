package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.nuvio.tv.core.iptv.PlaylistServerFailover
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.dns.PlaylistDns
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A portal reply whose `js` is not a list (an error string, an object) is a FAILURE, never an empty category list.
 * UX147 end to end through the real client (fake portal over MockWebServer, like [StalkerMinistraSeriesTest]):
 * the series probe's verdict is what the hub's Series tab is built from. A portal that answers "nothing" for
 * `type=series` AND has no series-flagged vod loses the tab; anything less certain keeps it.
 */
class StalkerNonArrayReplyTest {

    private lateinit var server: MockWebServer
    @Volatile private var genresReply = """{"js":[]}"""
    @Volatile private var seriesReply = """{"js":null}"""
    @Volatile private var vodCategories = """{"js":[{"id":"*","title":"All"}]}"""
    @Volatile private var profile = """{"js":{"id":"1","status":0}}"""
    @Volatile private var modules = """{"js":{}}"""

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val type = request.url.queryParameter("type")
                val action = request.url.queryParameter("action")
                val body = when {
                    action == "handshake" -> """{"js":{"token":"T"}}"""
                    action == "get_profile" -> profile
                    action == "get_modules" -> modules
                    type == "itv" && action == "get_genres" -> genresReply
                    type == "series" && action == "get_categories" -> seriesReply
                    type == "vod" && action == "get_categories" -> vodCategories
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

    private val acc by lazy {
        XtreamAccount(
            id = "stalker|absent", name = "Portal", baseUrl = "", username = "", password = "",
            sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = server.url("/stalker_portal/c/").toString(), macAddress = "00:1A:79:00:00:02",
        )
    }

    private val sessions by lazy { StalkerSessionManager(OkHttpClient(), PlaylistDns()) }

    private fun client() = StalkerClient(
        sessions,
        mockk<IptvContentDb>(relaxed = true),
        PlaylistServerFailover.detached(),
    )

    @Test
    fun `a string js for the genres is a failure not an empty list`() = runBlocking {
        genresReply = """{"js":"Authorization failed."}"""
        assertTrue("a non-array reply must fail so the hub shows Retry", client().liveCategories(acc).isFailure)
    }

    @Test
    fun `an object js for the genres is a failure not an empty list`() = runBlocking {
        genresReply = """{"js":{"error":"nope"}}"""
        assertTrue("a non-array reply must fail so the hub shows Retry", client().liveCategories(acc).isFailure)
    }

    @Test
    fun `a real list still loads`() = runBlocking {
        genresReply = """{"js":[{"id":"*","title":"All"},{"id":"3","title":"News"}]}"""
        assertEquals(listOf("3"), client().liveCategories(acc).getOrThrow().map { it.id })
    }

    @Test
    fun `a genuinely empty list is still empty`() = runBlocking {
        genresReply = """{"js":[]}"""
        assertEquals(emptyList<String>(), client().liveCategories(acc).getOrThrow().map { it.id })
    }
}
