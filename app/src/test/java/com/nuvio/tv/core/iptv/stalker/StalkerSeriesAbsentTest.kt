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
 * UX147 end to end through the real client (fake portal over MockWebServer, like [StalkerMinistraSeriesTest]):
 * the series probe's verdict is what the hub's Series tab is built from. A portal that answers "nothing" for
 * `type=series` AND has no series-flagged vod loses the tab; anything less certain keeps it.
 */
class StalkerSeriesAbsentTest {

    private lateinit var server: MockWebServer
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
    fun `a portal with no series module and no series vod is reported absent`() = runBlocking {
        val c = client()
        assertTrue("the card path: the portal said nothing for Series", c.seriesCategories(acc).isFailure)
        assertTrue(c.seriesAbsent(acc))
    }

    @Test
    fun `a Ministra portal that serves series through vod is not absent`() = runBlocking {
        vodCategories = """{"js":[{"id":"*","title":"All"},{"id":"1","title":"Drama"}]}"""
        val c = client()
        assertEquals(listOf("1"), c.seriesCategories(acc).getOrThrow().map { it.id })
        assertFalse(c.seriesAbsent(acc))
    }

    @Test
    fun `an XC portal with series is not absent`() = runBlocking {
        seriesReply = """{"js":[{"id":"*","title":"All"},{"id":"7","title":"Drama"}]}"""
        val c = client()
        assertEquals(listOf("7"), c.seriesCategories(acc).getOrThrow().map { it.id })
        assertFalse(c.seriesAbsent(acc))
    }

    @Test
    fun `an empty list without a module report is unknown and keeps the tab`() = runBlocking {
        seriesReply = """{"js":[]}"""
        val c = client()
        c.seriesCategories(acc)
        assertFalse("empty alone is not positive evidence", c.seriesAbsent(acc))
    }

    // A module-gated portal (auth_access=false) is asked for get_modules during the session bootstrap; its
    // answer is the capability signal, so these run the payload THROUGH the session.
    private val moduleGated = """{"js":{"id":"1","status":0,"auth_access":false}}"""

    @Test
    fun `get_modules reporting series disabled plus an empty answer hides the tab`() = runBlocking {
        profile = moduleGated
        modules = """{"js":{"all_modules":["tv","vod","series"],"disabled_modules":["series"]}}"""
        seriesReply = """{"js":[]}"""
        val c = client()
        c.seriesCategories(acc)
        assertEquals("the payload was parsed: series is listed as disabled", true, sessions.sessionFor(acc).seriesModuleDisabled)
        assertTrue("module off + empty + no series vod = positive evidence", c.seriesAbsent(acc))
    }

    @Test
    fun `get_modules listing series as available keeps the tab even when the list is empty`() = runBlocking {
        profile = moduleGated
        modules = """{"js":{"all_modules":["tv","vod","series"],"disabled_modules":[]}}"""
        seriesReply = """{"js":[]}"""
        val c = client()
        c.seriesCategories(acc)
        assertEquals("parsed: series is understood as available (not merely unknown)", false, sessions.sessionFor(acc).seriesModuleDisabled)
        assertFalse(c.seriesAbsent(acc))
    }

    @Test
    fun `a get_modules payload we do not recognise is unknown and keeps the tab`() = runBlocking {
        profile = moduleGated
        modules = """{"js":"nope"}"""
        seriesReply = """{"js":[]}"""
        val c = client()
        c.seriesCategories(acc)
        assertEquals("an unrecognised payload stays unknown", null, sessions.sessionFor(acc).seriesModuleDisabled)
        assertFalse(c.seriesAbsent(acc))
    }

    @Test
    fun `a refreshed playlist forgets the verdict`() = runBlocking {
        val c = client()
        c.seriesCategories(acc)
        assertTrue(c.seriesAbsent(acc))
        c.forgetSeriesVerdict(acc.id)
        assertFalse(c.seriesAbsent(acc))
    }
}
