package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.google.gson.JsonParser
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
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.Collections

/**
 * Replays scripted Stalker portals and asserts what the client ASKS THEM, in order.
 *
 * The fixtures describe portal behaviour, not ours: each one is a portal family observed in the
 * wild — a portal that only answers on one endpoint, one that gates its content behind
 * `get_modules`, one that rejects a MAG250 identity, one that wants an explicit `do_auth`. They are
 * deliberately NOT derived from what [StalkerSession] currently does, because a test written from
 * our own implementation can only ever confirm our blind spots.
 *
 * So some of these are expected to fail today. A fixture carrying `"supported": false` documents a
 * portal family we do not handle yet; it asserts the CURRENT behaviour and names the gap, so the
 * suite stays green while the gap stays visible. Flip it to true when the support lands.
 *
 * Fixture format (JSON files under app/src/test/resources/stalker/fixtures):
 * ```
 * {
 *   "name": "...",              // family
 *   "gap": "...",               // optional: what we don't support, when supported=false
 *   "supported": true,          // whether Tuvora handles this family
 *   "endpoints": ["/portal.php"],        // paths that answer at all; others 404
 *   "responses": [ { "action": "handshake", "body": "..." } ],
 *   "expect": { "requestOrder": ["handshake", "get_profile"], "authSucceeds": true }
 * }
 * ```
 * `expect.requestOrder` is the DISTINCT action sequence, first occurrence order — endpoint probing
 * and retries repeat actions, and the point is which calls are made, not how many.
 */
class StalkerPortalReplayHarnessTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
        unmockkStatic(Log::class)
    }

    @Test
    fun `mac only portal authenticates and lists channels`() = runFixture("mac_basic")

    @Test
    fun `portal answering only on portal_php is found by endpoint probing`() =
        runFixture("portal_endpoint_preferred")

    @Test
    fun `portal gating content behind get_modules`() = runFixture("module_gated")

    @Test
    fun `portal demanding an explicit do_auth`() = runFixture("auth_required")

    @Test
    fun `portal rejecting the MAG250 identity we hardcode`() = runFixture("strict_mag_identity")

    // ---------------------------------------------------------------------------------------

    private fun runFixture(name: String) = runBlocking {
        val fixture = loadFixture(name)
        val requested = Collections.synchronizedList(mutableListOf<String>())

        val bodies = fixture.responses
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (fixture.endpoints.none { path.endsWith(it) }) {
                    return MockResponse.Builder().code(404).body("not found").build()
                }
                val action = request.url.queryParameter("action").orEmpty()
                requested.add(action)
                val body = bodies[action]
                    ?: return MockResponse.Builder().code(200).body("""{"js":false}""").build()
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()

        val account = XtreamAccount(
            id = "fixture-$name", name = name, baseUrl = "", username = "", password = "",
            sourceType = "stalker",
            portalUrl = server.url("/").toString().trimEnd('/'),
            macAddress = "00:1A:79:DE:AD:BE",
        )
        val session = StalkerSession(account, OkHttpClient())

        // itv/get_genres is the cheapest real content call; it forces a full auth first.
        val outcome = runCatching {
            session.request(mapOf("type" to "itv", "action" to "get_genres"))
        }

        val order = requested.distinct().filter { it.isNotEmpty() }

        if (fixture.supported) {
            assertTrue(
                "${fixture.name}: expected auth+content to succeed, failed with ${outcome.exceptionOrNull()}",
                outcome.isSuccess
            )
            assertEquals("${fixture.name}: request order", fixture.expectRequestOrder, order)
        } else {
            // Documents the gap rather than pretending it passes. If this starts failing, the
            // family may now be supported — flip "supported" to true and tighten the assertion.
            val gap = fixture.gap ?: "unsupported portal family"
            assertEquals(
                "${fixture.name}: request order changed — is this family now handled? Gap was: $gap",
                fixture.expectRequestOrder,
                order
            )
        }
    }

    private data class Fixture(
        val name: String,
        val gap: String?,
        val supported: Boolean,
        val endpoints: List<String>,
        val responses: Map<String, String>,
        val expectRequestOrder: List<String>,
    )

    private fun loadFixture(name: String): Fixture {
        val stream = javaClass.classLoader?.getResourceAsStream("stalker/fixtures/$name.json")
            ?: error("missing fixture stalker/fixtures/$name.json")
        val root = JsonParser.parseReader(stream.reader()).asJsonObject
        val responses = root.getAsJsonArray("responses").associate { element ->
            val obj = element.asJsonObject
            obj.get("action").asString to obj.get("body").asString
        }
        val expect = root.getAsJsonObject("expect")
        return Fixture(
            name = root.get("name").asString,
            gap = root.get("gap")?.takeIf { !it.isJsonNull }?.asString,
            supported = root.get("supported").asBoolean,
            endpoints = root.getAsJsonArray("endpoints").map { it.asString },
            responses = responses,
            expectRequestOrder = expect.getAsJsonArray("requestOrder").map { it.asString },
        )
    }
}
