package com.nuvio.tv.core.iptv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Step 0.3 — the [PlaylistServerFailover] seam over fake attempts. Twin of NuvioMobile's commonTest
 * PlaylistServerFailoverTest (same cases), plus the TV-only cache-evidence cases: TV's Xtream catalog
 * calls can be answered from OkHttp's disk cache, which says nothing about the host.
 */
class PlaylistServerFailoverTest {

    private var now = 1_000L
    private val store = InMemoryServerFailoverStateStore()
    private val failover = PlaylistServerFailover(store, clock = { now }, profileId = { 1 })

    private val acc = XtreamAccount(
        id = "http://main.test|u", name = "P", baseUrl = "http://main.test", username = "u", password = "p",
        backupUrls = listOf("http://b1.test", "http://b2.test"),
    )

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    /** A fake request: each server either answers with its own base or throws [failures]'s throwable. */
    private fun fakeRequest(tried: MutableList<String>, failures: Map<String, Throwable>): suspend (XtreamAccount) -> String = { a ->
        tried += a.baseUrl
        failures[a.baseUrl]?.let { throw it }
        a.baseUrl
    }

    @Test
    fun `main 503 fails over to backup 1 and the stream url follows`() = runBlocking {
        val tried = mutableListOf<String>()
        val served = failover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to HttpStatusException(503, "HTTP 503"))))
        assertEquals("http://b1.test", served)
        assertEquals(listOf("http://main.test", "http://b1.test"), tried)
        assertEquals(1, failover.activeIndex(acc))
        assertEquals("http://b1.test", failover.activeAccount(acc).baseUrl)
        assertEquals(
            "a stream url built on main moves to the active backup",
            "http://b1.test/live/u/p/7.ts",
            failover.rebaseStreamUrl(acc, "http://main.test/live/u/p/7.ts"),
        )
    }

    @Test
    fun `transport failures fail over - dns, refused, timeout, tls, breaker`() = runBlocking {
        for (t in listOf(
            UnknownHostException("main.test"),
            ConnectException("Failed to connect"),
            SocketTimeoutException("timeout"),
            SSLHandshakeException("bad cert"),
            PanelHostFastFailIOException(PanelHostFastFailException("http://main.test", 0L)),
        )) {
            store.clear(1, acc.id)
            val tried = mutableListOf<String>()
            failover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to t)))
            assertEquals("$t", listOf("http://main.test", "http://b1.test"), tried)
        }
    }

    @Test
    fun `401 is the same answer on every server and is thrown without trying a backup`() = runBlocking {
        val tried = mutableListOf<String>()
        val auth = HttpStatusException(401, "HTTP 401")
        val thrown = assertThrows<HttpStatusException> {
            runBlocking { failover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to auth))) }
        }
        assertSame(auth, thrown)
        assertEquals(listOf("http://main.test"), tried)
        assertEquals(0, failover.activeIndex(acc))
    }

    @Test
    fun `waf 456 and 429 do not fail over`() = runBlocking {
        for (code in listOf(456, 429)) {
            val tried = mutableListOf<String>()
            assertThrows<HttpStatusException> {
                runBlocking { failover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to HttpStatusException(code, "HTTP $code")))) }
            }
            assertEquals("HTTP $code", listOf("http://main.test"), tried)
        }
    }

    @Test
    fun `connection reset proves nothing about the host and does not fail over`() = runBlocking {
        val tried = mutableListOf<String>()
        assertThrows<java.net.SocketException> {
            runBlocking { failover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to java.net.SocketException("Connection reset")))) }
        }
        assertEquals(listOf("http://main.test"), tried)
    }

    @Test
    fun `every server down surfaces the main server's error and keeps the state`() = runBlocking {
        val mainErr = HttpStatusException(502, "main 502")
        val tried = mutableListOf<String>()
        val thrown = assertThrows<HttpStatusException> {
            runBlocking {
                failover.run(acc, attempt = fakeRequest(tried, mapOf(
                    "http://main.test" to mainErr,
                    "http://b1.test" to HttpStatusException(503, "b1 503"),
                    "http://b2.test" to HttpStatusException(404, "b2 404"),
                )))
            }
        }
        assertSame(mainErr, thrown)
        assertEquals(3, tried.size)
        assertEquals(ServerFailoverState(), store.read(1, acc.id))
    }

    @Test
    fun `a streamed body that already delivered rows is never replayed on a backup`() = runBlocking {
        val tried = mutableListOf<String>()
        var delivered = false
        assertThrows<HttpStatusException> {
            runBlocking {
                failover.run(acc, canRetry = { !delivered }) { a ->
                    tried += a.baseUrl
                    delivered = true
                    throw HttpStatusException(503, "mid-body")
                }
            }
        }
        assertEquals(listOf("http://main.test"), tried)
    }

    @Test
    fun `inside the window the backup leads and after it main is retried and wins back`() = runBlocking {
        val tried = mutableListOf<String>()
        failover.run(acc, attempt = fakeRequest(tried, mapOf("http://main.test" to HttpStatusException(503, "x"))))
        tried.clear()
        now += 60_000
        failover.run(acc, attempt = fakeRequest(tried, emptyMap()))
        assertEquals("no request to main inside the window", listOf("http://b1.test"), tried)
        tried.clear()
        now = 1_000L + ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS
        failover.run(acc, attempt = fakeRequest(tried, emptyMap()))
        assertEquals(listOf("http://main.test"), tried)
        assertEquals(0, failover.activeIndex(acc))
        assertEquals("http://main.test/live/u/p/7.ts", failover.rebaseStreamUrl(acc, "http://b1.test/live/u/p/7.ts"))
    }

    @Test
    fun `the walk stops once the time budget is spent`() = runBlocking {
        val tried = mutableListOf<String>()
        val thrown = assertThrows<HttpStatusException> {
            runBlocking {
                failover.run(acc) { a ->
                    tried += a.baseUrl
                    now += PlaylistServerFailover.SINGLE_REQUEST_TIMEOUT_MS * 3   // one slow failure eats the whole budget
                    throw HttpStatusException(504, a.baseUrl)
                }
            }
        }
        assertEquals(listOf("http://main.test"), tried)
        assertEquals("http://main.test", thrown.message)
    }

    @Test
    fun `a playlist without backups takes the plain path and stores nothing`() = runBlocking {
        for (single in listOf(acc.copy(backupUrls = emptyList()), acc.copy(backupUrls = null))) {
            val err = HttpStatusException(503, "x")
            assertThrows<HttpStatusException> { runBlocking { failover.run(single) { throw err } } }
            assertTrue(store.all(1).isEmpty())
            assertEquals("http://main.test/x", failover.rebaseStreamUrl(single, "http://main.test/x"))
        }
    }

    @Test
    fun `m3u and stalker stream urls are never rebased`() {
        store.write(1, "m3u", ServerFailoverState(1, 99_000))
        val m3u = acc.copy(
            id = "m3u", sourceType = XtreamAccount.SOURCE_URL, baseUrl = "http://main.test/list.m3u",
            backupUrls = listOf("http://b1.test/list.m3u"),
        )
        assertEquals("http://main.test/live/1.ts", failover.rebaseStreamUrl(m3u, "http://main.test/live/1.ts"))
    }

    @Test
    fun `a stalker playlist walks its portal urls and its active account points the portal at the backup`() = runBlocking {
        val stalker = XtreamAccount(
            id = "stalker|http://portal.test|00:1A:79:00:00:01", name = "S", baseUrl = "http://portal.test",
            username = "", password = "", sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = "http://portal.test", macAddress = "00:1A:79:00:00:01",
            backupUrls = listOf("http://portal2.test"),
        )
        val tried = mutableListOf<String>()
        failover.run(stalker) { a ->
            tried += a.portalUrl
            if (a.portalUrl == "http://portal.test") throw ConnectException("down")
        }
        assertEquals(listOf("http://portal.test", "http://portal2.test"), tried)
        assertEquals("http://portal2.test", failover.activeAccount(stalker).portalUrl)
        assertEquals("http://portal2.test", failover.activeAccount(stalker).baseUrl)
    }

    @Test
    fun `an m3u file has no servers to walk`() = runBlocking {
        val file = acc.copy(id = "f", sourceType = XtreamAccount.SOURCE_FILE, baseUrl = "", backupUrls = listOf("http://b1.test"))
        val tried = mutableListOf<String>()
        assertThrows<ConnectException> {
            runBlocking { failover.run(file, attempt = fakeRequest(tried, mapOf("" to ConnectException("x")))) }
        }
        assertEquals(listOf(""), tried)
    }

    // --- TV-only: OkHttp disk-cache evidence ------------------------------------------------------

    @Test
    fun `a stale-cache fallback from a dead main is not a success - the backup answers live`() = runBlocking {
        val tried = mutableListOf<String>()
        val served = failover.run(
            acc,
            evidence = { r: String -> if (r == "http://main.test") ServedFrom.STALE_FALLBACK else ServedFrom.NETWORK },
            attempt = fakeRequest(tried, emptyMap()),
        )
        assertEquals("http://b1.test", served)
        assertEquals(listOf("http://main.test", "http://b1.test"), tried)
        assertEquals(1, failover.activeIndex(acc))
    }

    @Test
    fun `when every server is down the stale copy is still served and the state is kept`() = runBlocking {
        val tried = mutableListOf<String>()
        val served = failover.run(
            acc,
            evidence = { r: String -> if (r == "stale-main") ServedFrom.STALE_FALLBACK else ServedFrom.NETWORK },
        ) { a ->
            tried += a.baseUrl
            if (a.baseUrl == "http://main.test") "stale-main" else throw ConnectException("down")
        }
        assertEquals("stale-main", served)
        assertEquals(3, tried.size)
        assertEquals(ServerFailoverState(), store.read(1, acc.id))
    }

    @Test
    fun `a fresh cache hit is returned as-is without moving the active server`() = runBlocking {
        store.write(1, acc.id, ServerFailoverState(1, 500L))   // on backup 1, window already over
        val tried = mutableListOf<String>()
        val served = failover.run(acc, evidence = { _: String -> ServedFrom.CACHE }, attempt = fakeRequest(tried, emptyMap()))
        assertEquals("http://main.test", served)
        assertEquals("one request, no extra walking", listOf("http://main.test"), tried)
        assertEquals("a cache hit proves nothing about main", ServerFailoverState(1, 500L), store.read(1, acc.id))
    }

    // --- lifecycle helpers --------------------------------------------------------------------------

    @Test
    fun `an edited server list resets to main, an options-only edit does not`() {
        val onBackup = ServerFailoverState(1, 99_000L)
        store.write(1, acc.id, onBackup)
        failover.onPlaylistEdited(acc, acc.copy(name = "Renamed"))
        assertEquals(onBackup, store.read(1, acc.id))
        failover.onPlaylistEdited(acc, acc.copy(backupUrls = listOf("http://b2.test", "http://b1.test")))
        assertEquals(ServerFailoverState(), store.read(1, acc.id))
        store.write(1, acc.id, onBackup)
        failover.onPlaylistEdited(acc, acc.copy(baseUrl = "http://moved.test"))
        assertEquals(ServerFailoverState(), store.read(1, acc.id))
    }

    @Test
    fun `a pull that changed a playlist's servers resets it and leaves the rest alone`() {
        val other = acc.copy(id = "other")
        val onBackup = ServerFailoverState(1, 99_000L)
        store.write(1, acc.id, onBackup)
        store.write(1, other.id, onBackup)
        failover.onPulled(before = listOf(acc, other), after = listOf(acc.copy(backupUrls = listOf("http://b9.test")), other))
        assertEquals(ServerFailoverState(), store.read(1, acc.id))
        assertEquals(onBackup, store.read(1, other.id))
    }

    @Test
    fun `forget drops one playlist's state for one profile`() {
        val onBackup = ServerFailoverState(1, 99_000L)
        store.write(1, acc.id, onBackup)
        store.write(2, acc.id, onBackup)
        failover.forget(1, acc.id)
        assertEquals(ServerFailoverState(), store.read(1, acc.id))
        assertEquals(onBackup, store.read(2, acc.id))
    }

    @Test
    fun `active indexes list only playlists off their main server`() {
        val other = acc.copy(id = "other")
        store.write(1, acc.id, ServerFailoverState(2, 99_000L))
        assertEquals(mapOf(acc.id to 2), failover.activeIndexes(listOf(acc, other)))
    }
}
