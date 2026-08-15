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
 *   "responses": [ { "action": "handshake", "body": "...", "match": {"param": "value"} } ],
 *   "expect": {
 *     "requestOrder": ["handshake", "get_profile"],
 *     "authSucceeds": true,                       // default true; false = the bootstrap must throw
 *     "errorType": "StalkerDeviceConflictException",  // exact exception simple name when it throws
 *     "errorContains": "nother device",           // substring the surfaced message must carry
 *     "paramSequence": {                          // one param's values across a repeated action,
 *       "action": "get_profile",                  // in request order (pins auth_second_step: the
 *       "param": "auth_second_step",              // retry after do_auth is the ONLY one sending 1)
 *       "values": ["0", "1"]
 *     }
 *   }
 * }
 * ```
 * `expect.requestOrder` is the DISTINCT action sequence, first occurrence order — endpoint probing
 * and retries repeat actions, and the point is which calls are made, not how many.
 *
 * When `get_events` appears in the expected order, the harness also pins the watchdog wire shape
 * (the portal's own c/watchdog.js: type=watchdog, cur_play_type, event_active_id, init=1 first).
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

    /** status=1 + a device-binding phrase → the actionable device-conflict error, not "empty portal". */
    @Test
    fun `portal refusing with a device conflict message`() = runFixture("device_conflict")

    /** A bare {"status":1} with no message is a refusal, never a success. */
    @Test
    fun `portal refusing with a bare status one`() = runFixture("status1_bare")

    // ---------------------------------------------------------------------------------------

    private fun runFixture(name: String) = runBlocking {
        val fixture = loadFixture(name)
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val requestedUrls = Collections.synchronizedList(mutableListOf<okhttp3.HttpUrl>())
        val satisfied = Collections.synchronizedSet(mutableSetOf<String>())

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
                requestedUrls.add(request.url)
                // First scripted response whose `match` params all agree with the request wins, so a
                // fixture can model a portal that accepts one STB identity and rejects another.
                val scripted = bodies.firstOrNull { candidate ->
                    candidate.action == action && candidate.match.all { (k, v) ->
                        request.url.queryParameter(k) == v
                    }
                } ?: return MockResponse.Builder().code(200).body("""{"js":false}""").build()

                // A real portal serves nothing until its bootstrap is satisfied — the profile
                // activated, the modules fetched, the explicit auth done. Modelling that is what
                // makes these fixtures discriminate: a client that skips a required step has to be
                // seen failing on the CONTENT call, not quietly appearing to work.
                if (!scripted.body.contains(AUTH_FAILED)) satisfied.add(action)
                val unmet = fixture.contentRequiresActions.filterNot { it in satisfied }
                if (unmet.isNotEmpty() && action !in BOOTSTRAP_ACTIONS) {
                    return MockResponse.Builder().code(200).body(AUTH_FAILED).build()
                }
                return MockResponse.Builder().code(200).body(scripted.body).build()
            }
        }
        server.start()

        val account = XtreamAccount(
            id = "fixture-$name", name = name, baseUrl = "", username = "", password = "",
            sourceType = "stalker",
            portalUrl = server.url("/").toString().trimEnd('/'),
            macAddress = "00:1A:79:DE:AD:BE",
            stalkerUsername = fixture.stalkerUsername,
            stalkerPassword = fixture.stalkerPassword,
        )
        val session = StalkerSession(account, OkHttpClient())

        // itv/get_genres is the cheapest real content call; it forces a full auth first.
        val outcome = try {
            runCatching { session.request(mapOf("type" to "itv", "action" to "get_genres")) }
        } finally {
            session.shutdown()   // stop the watchdog loop so tests never leak a ticking coroutine
        }

        val order = requested.distinct().filter { it.isNotEmpty() }

        if (fixture.supported && fixture.expectAuthSucceeds) {
            assertTrue(
                "${fixture.name}: expected auth+content to succeed, failed with ${outcome.exceptionOrNull()}",
                outcome.isSuccess
            )
            assertEquals("${fixture.name}: request order", fixture.expectRequestOrder, order)
        } else if (fixture.supported) {
            // A REFUSAL family: the bootstrap must throw the exact typed error, with the
            // actionable wording — a refusal surfacing as "empty portal" is the old bug.
            val error = outcome.exceptionOrNull()
            assertTrue("${fixture.name}: expected the bootstrap to throw, got success", error != null)
            fixture.expectErrorType?.let {
                assertEquals("${fixture.name}: error type", it, error!!.javaClass.simpleName)
            }
            fixture.expectErrorContains?.let {
                assertTrue(
                    "${fixture.name}: error message must contain \"$it\", was: ${error!!.message}",
                    error.message.orEmpty().contains(it)
                )
            }
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

        // One param's value sequence across a repeated action — pins auth_second_step: the initial
        // profile sends 0 and ONLY the post-do_auth retry sends 1.
        fixture.expectParamSequence?.let { pin ->
            val values = requestedUrls.filter { it.queryParameter("action") == pin.action }
                .map { it.queryParameter(pin.param).orEmpty() }
            assertEquals("${fixture.name}: ${pin.action}.${pin.param} sequence", pin.values, values)
        }

        // Watchdog wire shape whenever the family expects the keep-alive: the portal's own
        // c/watchdog.js sends type=watchdog with cur_play_type/event_active_id, init=1 first.
        if ("get_events" in fixture.expectRequestOrder) {
            val pings = requestedUrls.filter { it.queryParameter("action") == "get_events" }
            assertTrue("${fixture.name}: expected at least one get_events ping", pings.isNotEmpty())
            pings.forEachIndexed { i, url ->
                assertEquals("${fixture.name}: ping type", "watchdog", url.queryParameter("type"))
                assertEquals("${fixture.name}: ping cur_play_type", "0", url.queryParameter("cur_play_type"))
                assertEquals("${fixture.name}: ping event_active_id", "0", url.queryParameter("event_active_id"))
                assertEquals(
                    "${fixture.name}: init flag (1 only on the activation ping)",
                    if (i == 0) "1" else "0",
                    url.queryParameter("init")
                )
            }
        }
    }

    private companion object {
        const val AUTH_FAILED = "Authorization failed."
        val BOOTSTRAP_ACTIONS = setOf("handshake", "get_profile", "get_modules", "do_auth", "get_main_info")
    }

    private data class ScriptedResponse(
        val action: String,
        val body: String,
        /** Query params that must match for this response to apply. Empty = matches any. */
        val match: Map<String, String>,
    )

    private data class ParamSequence(
        val action: String,
        val param: String,
        val values: List<String>,
    )

    private data class Fixture(
        val name: String,
        val gap: String?,
        val supported: Boolean,
        val endpoints: List<String>,
        val responses: List<ScriptedResponse>,
        val expectRequestOrder: List<String>,
        val expectAuthSucceeds: Boolean,
        /** Exact exception simple name when authSucceeds=false (null = any throw). */
        val expectErrorType: String?,
        /** Substring the surfaced error message must carry (actionable wording pin). */
        val expectErrorContains: String?,
        val expectParamSequence: ParamSequence?,
        /** Actions that must have succeeded before this portal serves any content. */
        val contentRequiresActions: List<String>,
        val stalkerUsername: String,
        val stalkerPassword: String,
    )

    private fun loadFixture(name: String): Fixture {
        val stream = javaClass.classLoader?.getResourceAsStream("stalker/fixtures/$name.json")
            ?: error("missing fixture stalker/fixtures/$name.json")
        val root = JsonParser.parseReader(stream.reader()).asJsonObject
        val responses = root.getAsJsonArray("responses").map { element ->
            val obj = element.asJsonObject
            ScriptedResponse(
                action = obj.get("action").asString,
                body = obj.get("body").asString,
                match = obj.getAsJsonObject("match")?.entrySet()
                    ?.associate { (k, v) -> k to v.asString }
                    .orEmpty(),
            )
        }
        val expect = root.getAsJsonObject("expect")
        val device = root.getAsJsonObject("device")
        return Fixture(
            name = root.get("name").asString,
            gap = root.get("gap")?.takeIf { !it.isJsonNull }?.asString,
            supported = root.get("supported").asBoolean,
            endpoints = root.getAsJsonArray("endpoints").map { it.asString },
            responses = responses,
            expectRequestOrder = expect.getAsJsonArray("requestOrder").map { it.asString },
            expectAuthSucceeds = expect.get("authSucceeds")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
            expectErrorType = expect.get("errorType")?.takeIf { !it.isJsonNull }?.asString,
            expectErrorContains = expect.get("errorContains")?.takeIf { !it.isJsonNull }?.asString,
            expectParamSequence = expect.getAsJsonObject("paramSequence")?.let { pin ->
                ParamSequence(
                    action = pin.get("action").asString,
                    param = pin.get("param").asString,
                    values = pin.getAsJsonArray("values").map { it.asString },
                )
            },
            contentRequiresActions = root.getAsJsonArray("contentRequiresActions")
                ?.map { it.asString }.orEmpty(),
            stalkerUsername = device?.get("stalkerUsername")?.asString.orEmpty(),
            stalkerPassword = device?.get("stalkerPassword")?.asString.orEmpty(),
        )
    }
}
