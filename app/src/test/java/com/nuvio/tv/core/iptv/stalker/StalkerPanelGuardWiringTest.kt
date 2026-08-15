package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.nuvio.tv.core.iptv.PanelAdmission
import com.nuvio.tv.core.iptv.PanelHostFastFailException
import com.nuvio.tv.core.iptv.PanelHostGuard
import com.nuvio.tv.core.iptv.PanelRequestOutcome
import com.nuvio.tv.core.iptv.XtreamAccount
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP6 wiring — every [StalkerSession] portal request is admitted through the injected
 * [PanelHostGuard], connection failures open the breaker, an open breaker fast-fails with the
 * policy's own distinct exception, a user reset goes back to the wire, and the endpoint-probe
 * ladder carries the discovery exemption.
 *
 * The portal is a real [MockWebServer]; killing it frees the port, so later attempts are genuine
 * connection-refused failures — the java.net exception the classifier must map. Transport
 * attempts are counted by a client-side interceptor (it fires before the connect, so refused
 * attempts count too), which makes the fast-fail assertion "the counter did not move", not just
 * an exception type. The guard is injected fresh per test with a manual clock.
 */
class StalkerPanelGuardWiringTest {

    private var now = 0L
    private val guard = PanelHostGuard { now }

    private lateinit var server: MockWebServer
    private val handshakes = AtomicInteger(0)
    private val transportAttempts = AtomicInteger(0)

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
                val body = when (request.url.queryParameter("action")) {
                    "handshake" -> """{"js":{"token":"T${handshakes.incrementAndGet()}"}}"""
                    "get_profile" -> """{"js":{"watchdog_timeout":120}}"""
                    else -> """{"js":[]}"""   // empty list = valid (no channels)
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        unmockkStatic(Log::class)
    }

    private fun portalBase() = server.url("/").toString().trimEnd('/')

    private fun account(portal: String) = XtreamAccount(
        id = "t", name = "portal", baseUrl = "", username = "", password = "",
        sourceType = "stalker",
        portalUrl = portal,
        macAddress = "00:1A:79:58:B3:A6",
    )

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        // No connection reuse: after the server dies, a pooled socket would surface as a mid-
        // stream reset (inconclusive) instead of the deterministic connection-refused this test
        // is about. A fresh connect per request always hits the closed port.
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .addInterceptor { chain ->
            transportAttempts.incrementAndGet()
            chain.proceed(chain.request())
        }
        .build()

    private val genres = mapOf("type" to "itv", "action" to "get_genres")

    @Test
    fun `a dead panel fast fails the second browse`() = runBlocking<Unit> {
        val portal = portalBase()
        val session = StalkerSession(account(portal), client(), guard)
        // Healthy bootstrap + first browse: the portal was alive, the breaker record is clear.
        session.request(genres)
        server.close()   // the port refuses from here on
        // Browse one against the now-dead portal: two real transport attempts, both refused.
        val first = runCatching { session.request(genres) }.exceptionOrNull()
        val second = runCatching { session.request(genres) }.exceptionOrNull()
        assertTrue("first attempt must go to the wire, got $first", first is ConnectException)
        assertTrue("second attempt must go to the wire, got $second", second is ConnectException)
        val attemptsBefore = transportAttempts.get()
        // Browse two: the breaker answers — the transport is NOT tried a third time.
        val third = runCatching { session.request(genres) }.exceptionOrNull()
        assertTrue("the second browse must fast-fail, got $third", third is PanelHostFastFailException)
        assertEquals("a fast-fail must not touch the transport", attemptsBefore, transportAttempts.get())
    }

    @Test
    fun `a user retry resets the breaker and goes to the wire`() = runBlocking<Unit> {
        val portal = portalBase()
        val session = StalkerSession(account(portal), client(), guard)
        session.request(genres)
        server.close()
        runCatching { session.request(genres) }
        runCatching { session.request(genres) }
        assertTrue(
            "two connection failures must open the breaker",
            runCatching { session.request(genres) }.exceptionOrNull() is PanelHostFastFailException,
        )
        // What the Retry affordances call (IptvPanelGuard.resetForAccount -> guard.reset) BEFORE
        // their first request — against the same normalized portal base the session admits with.
        guard.reset(StalkerProtocol.normalizePortalBase(portal))
        val attemptsBefore = transportAttempts.get()
        val after = runCatching { session.request(genres) }.exceptionOrNull()
        assertNotNull("the dead portal still fails the request", after)
        assertFalse("after a user reset the request must reach the wire", after is PanelHostFastFailException)
        assertTrue("the reset attempt must reach the transport", transportAttempts.get() > attemptsBefore)
    }

    @Test
    fun `discovery probes bypass an open breaker`() = runBlocking<Unit> {
        val portal = portalBase()
        // Open the breaker for the portal's origin before the session ever bootstraps.
        fun failOnce() {
            val allowed = guard.admit(portal) as PanelAdmission.Allowed
            guard.report(allowed, PanelRequestOutcome.CONNECTION_FAILURE)
        }
        failOnce()
        failOnce()
        assertTrue("precondition: the breaker is open", guard.admit(portal) is PanelAdmission.FastFail)
        // A fresh session's endpoint-probe ladder must still reach the portal (discovery flag),
        // and its SUCCESS must clear the record so the rest of the bootstrap proceeds.
        val session = StalkerSession(account(portal), client(), guard)
        session.request(genres)   // would throw a fast-fail if discovery were blocked
        assertTrue("the probe must have reached the portal", handshakes.get() > 0)
        assertTrue("the probe's success must clear the record", guard.admit(portal) is PanelAdmission.Allowed)
    }
}
