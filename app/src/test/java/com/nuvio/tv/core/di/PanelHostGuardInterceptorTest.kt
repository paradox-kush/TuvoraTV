package com.nuvio.tv.core.di

import com.nuvio.tv.core.iptv.PanelHostFastFailException
import com.nuvio.tv.core.iptv.PanelHostFastFailIOException
import com.nuvio.tv.core.iptv.PanelHostGuard
import java.io.File
import java.net.ConnectException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP6 wiring — every Xtream `player_api.php` request through the shared OkHttp client is admitted
 * by [PanelHostGuardInterceptor]'s per-origin breaker. Driven over REAL sockets: a port with
 * nothing listening refuses instantly and deterministically, so the java.net exception the
 * classifier must map (ConnectException -> CONNECTION_FAILURE) is the genuine article.
 *
 * The proof that a fast-fail skipped the wire is the failure TYPE: a real attempt against a dead
 * socket surfaces the transport's own error, while the breaker's refusal is
 * [PanelHostFastFailIOException] (the policy's exception in the IOException clothing the OkHttp
 * chain requires). Each test builds its own guard and its own dead port, so nothing leaks between
 * tests.
 */
class PanelHostGuardInterceptorTest {

    private var now = 0L
    private val guard = PanelHostGuard { now }

    /** A base URL with NOTHING listening: bind an ephemeral port, close it, use it. */
    private fun deadOrigin(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .addInterceptor(PanelHostGuardInterceptor(guard))
        .build()

    private fun get(client: OkHttpClient, url: String): Throwable? =
        runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }.exceptionOrNull()

    @Test
    fun `a dead panel fast fails the second browse`() {
        val base = deadOrigin()
        val client = client()
        // Browse one: two real transport attempts, both refused at the socket.
        val first = get(client, "$base/player_api.php?username=u&password=p&action=get_live_categories")
        val second = get(client, "$base/player_api.php?username=u&password=p&action=get_live_streams")
        assertTrue("first attempt must go to the wire", first is ConnectException)
        assertTrue("second attempt must go to the wire", second is ConnectException)
        // Browse two: the breaker answers instead of the wire — no third transport attempt.
        val third = get(client, "$base/player_api.php?username=u&password=p&action=get_vod_categories")
        assertTrue("the second browse must fast-fail, got $third", third is PanelHostFastFailIOException)
        // The policy's own exception (and its carefully-worded message) rides along as the cause.
        assertTrue((third as PanelHostFastFailIOException).cause is PanelHostFastFailException)
        val message = third.message.orEmpty()
        assertFalse("fast-fail wording must not carry an HTTP code", message.contains("HTTP Error"))
        assertFalse("fast-fail wording must not carry timeout words", message.contains("time", ignoreCase = true))
    }

    @Test
    fun `a user retry resets the breaker and goes to the wire`() {
        val base = deadOrigin()
        val client = client()
        val url = "$base/player_api.php?username=u&password=p&action=get_live_categories"
        get(client, url)
        get(client, url)
        assertTrue("two connection failures must open the breaker", get(client, url) is PanelHostFastFailIOException)
        // What every user-driven Retry affordance calls (IptvPanelGuard.resetForAccount -> reset)
        // BEFORE its first request.
        guard.reset(base)
        val after = get(client, url)
        assertNotNull("the dead socket still fails the request", after)
        assertFalse("after a user reset the request must reach the wire", after is PanelHostFastFailIOException)
    }

    @Test
    fun `only panel api requests are guarded`() {
        val base = deadOrigin()
        val client = client()
        // Open the breaker for the origin via two panel requests.
        get(client, "$base/player_api.php?username=u&password=p")
        get(client, "$base/player_api.php?username=u&password=p")
        assertTrue(get(client, "$base/player_api.php?username=u&password=p") is PanelHostFastFailIOException)
        // A non-panel path on the SAME dead origin is out of scope (playlist/XMLTV/stream traffic
        // never fast-fails): it must go to the wire and fail there.
        assertTrue(get(client, "$base/xmltv.php?username=u&password=p") is ConnectException)
    }

    /**
     * The placement contract with [XtreamCatalogFallbackInterceptor]: the guard sits BELOW it, so
     * an open breaker's fast-fail is caught like any network failure and answered from the stale
     * catalog cache INSTANTLY — browsing a dead panel keeps working from disk, just without the
     * timeout wait. (This is also why the guard must ignore `onlyIfCached` probes: the fallback's
     * stale lookup travels the same chain.)
     */
    @Test
    fun `an open breaker still serves the stale catalog copy`() {
        val cacheDir = File.createTempFile("guard-cache", null).apply { delete(); mkdirs() }
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val url = "$base/player_api.php?username=u&password=p&action=get_live_categories"
            val client = OkHttpClient.Builder()
                .cache(Cache(cacheDir, 10L * 1024 * 1024))
                .connectTimeout(2, TimeUnit.SECONDS)
                // No connection reuse: after the server dies, a pooled socket would surface as a
                // mid-stream reset (inconclusive) instead of a counted connection-refused.
                .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                // Same shape as NetworkModule: force catalog responses disk-cacheable (max-age=0
                // here so every later request must consult the network — the interesting path)...
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.isSuccessful && NetworkModule.isXtreamCatalogUrl(chain.request().url)) {
                        response.newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=0").build()
                    } else response
                }
                // ...then the stale-serving fallback ABOVE the guard, exactly like production.
                .addInterceptor(XtreamCatalogFallbackInterceptor())
                .addInterceptor(PanelHostGuardInterceptor(guard))
                .build()
            // Prime the cache with one healthy catalog response.
            server.enqueue(MockResponse.Builder().code(200).body("""[{"category_id":"1"}]""").build())
            client.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code)
            }
            // Panel dies. The fallback keeps serving the stale copy while the guard — sitting
            // BELOW it — still sees and counts the true connection failures underneath.
            server.close()
            repeat(2) {
                client.newCall(Request.Builder().url(url).build()).execute().use { stale ->
                    assertEquals("the stale copy must be served on a dead panel", 200, stale.code)
                }
            }
            assertTrue(
                "two masked connection failures must still open the breaker",
                guard.admit(url) is com.nuvio.tv.core.iptv.PanelAdmission.FastFail,
            )
            // And an OPEN breaker's fast-fail is caught by the fallback exactly like a network
            // failure: the stale copy is served instantly, without a connect attempt.
            client.newCall(Request.Builder().url(url).build()).execute().use { stale ->
                assertEquals("the stale copy must be served on an open breaker", 200, stale.code)
                assertEquals("""[{"category_id":"1"}]""", stale.body?.string())
            }
        } finally {
            runCatching { server.close() }
            cacheDir.deleteRecursively()
        }
    }
}
