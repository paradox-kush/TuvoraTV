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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * B294: a hub Movies/Series row costs ONE get_ordered_list request per window. The old row paged up to
 * 70 items (5 requests) sequentially before returning anything, which timed out behind the session's
 * small concurrency cap.
 */
class StalkerRowWindowTest {

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile private var failPages = false
    @Volatile private var ministra = false
    /** Pages (1-based) whose rows are all flagged is_series; null = default portal rows. */
    @Volatile private var seriesPages: Set<Int>? = null

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
                val q = request.url.queryParameterNames.associateWith { request.url.queryParameter(it).orEmpty() }
                requests += q
                return MockResponse.Builder().code(200).body(respond(q)).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        unmockkStatic(Log::class)
    }

    private fun rows(type: String, page: Int): String =
        (1..14).joinToString(",", "[", "]") { i ->
            val id = (page - 1) * 14 + i
            """{"id":"$id","name":"$type $id","cmd":"/media/$id.mpg","category_id":"5","is_series":"0"}"""
        }

    private fun respond(q: Map<String, String>): String {
        when (q["action"]) {
            "handshake" -> return """{"js":{"token":"T"}}"""
            "get_profile" -> return """{"js":{"id":"1","status":0}}"""
            "get_modules" -> return """{"js":{"all_modules":[],"disabled_modules":[]}}"""
            "get_categories" -> return if (ministra && q["type"] == "series") """{"js":[]}""" else """{"js":[{"id":"*","title":"All"},{"id":"5","title":"Drama"}]}"""
        }
        if (q["action"] == "get_ordered_list") {
            if (failPages) return """{"js":"nope"}"""
            val page = q["p"]?.toInt() ?: 1
            seriesPages?.let { sp ->
                val flag = if (page in sp) "1" else "0"
                val data = (1..14).joinToString(",", "[", "]") { i ->
                    val id = page * 100 + i
                    """{"id":"$id","name":"R$id","cmd":"/media/$id.mpg","category_id":"5","is_series":"$flag"}"""
                }
                return """{"js":{"total_items":200,"max_page_items":14,"data":$data}}"""
            }
            return """{"js":{"total_items":100,"max_page_items":14,"data":${rows(q["type"].orEmpty(), page)}}}"""
        }
        return """{"js":[]}"""
    }

    private val acc by lazy {
        XtreamAccount(
            id = "stalker|window", name = "Window", baseUrl = "", username = "", password = "",
            sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = server.url("/stalker_portal/c/").toString(), macAddress = "00:1A:79:00:00:02",
        )
    }

    private fun client() = StalkerClient(
        StalkerSessionManager(OkHttpClient(), PlaylistDns()),
        mockk<IptvContentDb>(relaxed = true),
        PlaylistServerFailover.detached(),
    )

    private fun lists(type: String) =
        requests.filter { it["action"] == "get_ordered_list" && it["type"] == type }

    @Test
    fun `the first series window costs exactly one request`() = runBlocking {
        val c = client()
        c.seriesCategories(acc).getOrThrow()   // the hub learns the dialect before any row loads
        requests.clear()
        val w = c.seriesWindow(acc, "5", 0)
        assertEquals("one get_ordered_list", 1, requests.count { it["action"] == "get_ordered_list" })
        assertEquals("asked page 1", "1", lists("series").single()["p"])
        assertEquals("items", 14, w.items.size)
        assertTrue("more pages", w.hasMore)
        assertEquals("consumed raw rows", 14, w.consumed)
    }

    @Test
    fun `the next series window asks the next page`() = runBlocking {
        val c = client()
        c.seriesCategories(acc).getOrThrow()
        c.seriesWindow(acc, "5", 0)
        requests.clear()
        val w = c.seriesWindow(acc, "5", 14)
        assertEquals("one request", 1, lists("series").size)
        assertEquals("asked page 2", "2", lists("series").single()["p"])
        assertEquals("first id of page 2", 15, w.items.first().seriesId)
    }

    @Test
    fun `a failed series page throws instead of reading as empty`() = runBlocking {
        val c = client()
        c.seriesCategories(acc).getOrThrow()
        failPages = true
        try {
            c.seriesWindow(acc, "5", 0)
            fail("a failed page must throw")
        } catch (e: Exception) {
            // expected: the row shows Retry
        }
        Unit
    }

    @Test
    fun `the first movie window costs exactly one request`() = runBlocking {
        val c = client()
        val w = c.vodMoviesWindow(acc, "5", 0)
        assertEquals("one get_ordered_list", 1, lists("vod").size)
        assertEquals("asked page 1", "1", lists("vod").single()["p"])
        assertEquals("items", 14, w.items.size)
        assertTrue("more pages", w.hasMore)
        assertEquals("consumed raw rows", 14, w.consumed)
    }

    @Test
    fun `the next movie window asks the next page and a failed page throws`() = runBlocking {
        val c = client()
        c.vodMoviesWindow(acc, "5", 0)
        requests.clear()
        c.vodMoviesWindow(acc, "5", 14)
        assertEquals("asked page 2", "2", lists("vod").single()["p"])
        failPages = true
        try {
            c.vodMoviesWindow(acc, "5", 28)
            fail("a failed page must throw")
        } catch (e: Exception) {
            // expected
        }
        Unit
    }

    @Test
    fun `a ministra movie window that shows nothing reads the next page`() = runBlocking {
        ministra = true
        seriesPages = setOf(1)
        val c = client()
        val w = c.vodMoviesWindow(acc, "5", 0)
        assertEquals("pages asked", listOf("1", "2"), lists("vod").map { it["p"] })
        assertEquals("page two movies", 14, w.items.size)
        assertEquals("first id", 201, w.items.first().streamId)
        assertEquals("raw rows of both pages", 28, w.consumed)
        assertTrue("more pages", w.hasMore)
    }

    @Test
    fun `a ministra series window that shows nothing reads the next page`() = runBlocking {
        ministra = true
        seriesPages = setOf(2)
        val c = client()
        c.seriesCategories(acc).getOrThrow()
        requests.clear()
        val w = c.seriesWindow(acc, "5", 0)
        assertEquals("pages asked", listOf("1", "2"), lists("vod").map { it["p"] })
        assertEquals("items", 14, w.items.size)
        assertEquals("first id", 201, w.items.first().seriesId)
        assertEquals("raw rows of both pages", 28, w.consumed)
        assertTrue("more pages", w.hasMore)
    }

    @Test
    fun `a window that keeps showing nothing stops after five pages`() = runBlocking {
        ministra = true
        seriesPages = (1..20).toSet()
        val c = client()
        val w = c.vodMoviesWindow(acc, "5", 0)
        assertEquals("pages asked", listOf("1", "2", "3", "4", "5"), lists("vod").map { it["p"] })
        assertEquals("no items", 0, w.items.size)
        assertEquals("raw rows of five pages", 70, w.consumed)
        assertTrue("portal says more", w.hasMore)
    }

    @Test
    fun `an xc series window stays one request`() = runBlocking {
        val c = client()
        c.seriesCategories(acc).getOrThrow()
        requests.clear()
        c.seriesWindow(acc, "5", 0)
        assertEquals("one get_ordered_list", 1, requests.count { it["action"] == "get_ordered_list" })
    }
}
