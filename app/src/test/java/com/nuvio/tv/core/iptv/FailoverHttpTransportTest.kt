package com.nuvio.tv.core.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Step 0.3b — what the failover race needs from TV's OkHttp transports, over REAL loopback sockets
 * (TV twin of NuvioMobile's HttpFailoverTransportTest):
 *  1. cancelling a racing attempt aborts its in-flight call — a bare synchronous `execute()` does NOT
 *     (the bug Mobile/Desktop shipped; TV's M3U, XMLTV and Stalker paths were all bare `execute()`),
 *     [executeCancellable] and Retrofit's suspend call do;
 *  2. `execute()` returns at the response headers with the body still unread;
 *  3. the header signal fires before any body byte, and a parked loser never reads the body;
 *  4. the failover CONNECT timeout is applied per attempt, nothing else changes.
 */
class FailoverHttpTransportTest {

    private val servers = mutableListOf<FakeHttpServer>()
    private val client = OkHttpClient.Builder().dns(FakeHttpServer.TEST_DNS).readTimeout(60, TimeUnit.SECONDS).build()

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun server(handler: (FakeHttpServer.Exchange) -> Unit) = FakeHttpServer(handler).also { servers += it }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(what, cond())
    }

    @Test
    fun `a bare execute ignores coroutine cancellation - the old TV path a racing loser must not use`() = runBlocking {
        val s = server { it.hang() }
        val call = client.newCall(Request.Builder().url(s.url("bare.test")).build())
        val job = launch(Dispatchers.IO) { runCatching { call.execute() } }
        waitFor("request reached the server") { s.exchanges.isNotEmpty() }
        job.cancel()
        delay(1_500)
        assertFalse("the coroutine is cancelled but stays blocked in execute()", job.isCompleted)
        assertFalse("and the hung server still holds the socket", s.exchanges.single().clientClosed)
        call.cancel()   // clean up the way executeCancellable does it
        withTimeout(5_000) { job.join() }
    }

    @Test
    fun `executeCancellable - cancelling the coroutine closes the socket of a server that never answers`() = runBlocking {
        val s = server { it.hang() }
        val job = launch(Dispatchers.IO) {
            client.newCall(Request.Builder().url(s.url("hook.test")).build()).executeCancellable { it.code }
        }
        waitFor("request reached the server") { s.exchanges.isNotEmpty() }
        withTimeout(5_000) { job.cancelAndJoin() }   // not the 60 s read timeout
        assertTrue(job.isCancelled)
        waitFor("the server saw the socket close") { s.exchanges.single().clientClosed }
    }

    private interface RawApi {
        @GET
        suspend fun get(@Url url: String): Response<ResponseBody>
    }

    @Test
    fun `retrofit suspend call - cancelling the coroutine cancels the OkHttp call`() = runBlocking {
        val s = server { it.hang() }
        val api = Retrofit.Builder().baseUrl("https://placeholder.test/").client(client).build().create(RawApi::class.java)
        val job = launch(Dispatchers.IO) { api.get(s.url("retro.test") + "/player_api.php") }
        waitFor("request reached the server") { s.exchanges.isNotEmpty() }
        withTimeout(5_000) { job.cancelAndJoin() }
        waitFor("Retrofit's invokeOnCancellation cancelled the call: the server saw the socket close") { s.exchanges.single().clientClosed }
    }

    @Test
    fun `execute returns at the response headers - the body is still unread on the socket`() = runBlocking {
        // A server that sends headers and then waits: write them by hand through trickle's first piece.
        val headersFirst = server { ex ->
            ex.trickle("0123456789".repeat(100), pieces = 2, pauseMs = 3_000)
        }
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            client.newCall(Request.Builder().url(headersFirst.url("lazy.test")).build()).execute().use { resp ->
                val atHeaders = System.currentTimeMillis() - t0
                assertEquals(200, resp.code)
                assertTrue("execute() returned at the headers ($atHeaders ms), before the 3 s body pause", atHeaders < 2_000)
                assertTrue("most of the body is still on the server", headersFirst.exchanges.single().bodyBytesSent.get() < 1_000)
                resp.body!!.close()
            }
        }
    }

    @Test
    fun `the header signal fires before any body byte and a parked loser never reads the body`() = runBlocking {
        val total = 50L * 1024 * 1024
        val s = server { it.stream("{", total = total, chunk = 1024, pauseMs = 2, contentType = "application/json") }
        val signalled = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        val signal = HttpAttemptSignal(connectTimeoutMs = 1_000, onHeaders = { signalled.countDown(); gate.await() })
        var bodyRead = false
        val job = launch(Dispatchers.IO + signal) {
            client.forFailoverAttempt().newCall(Request.Builder().url(s.url("parked.test")).build()).executeCancellable { resp ->
                bodyRead = true
                resp.body!!.source().readByteString()
            }
        }
        assertTrue("headers were reported while the body was still unread", signalled.await(5, TimeUnit.SECONDS))
        // It lost the race: the executor cancels the attempt AND releases its parked transport thread.
        job.cancel(); gate.cancel()
        withTimeout(5_000) { job.join() }
        assertFalse("the loser's body was never handed to the reader", bodyRead)
        waitFor("the loser's connection was closed (the server saw it go)") { s.exchanges.single().clientClosed }
        assertTrue("the loser never downloaded the body (${s.exchanges.single().bodyBytesSent.get()} B)", s.exchanges.single().bodyBytesSent.get() < total)
    }

    @Test
    fun `the winner's parked transport continues and reads the body`() = runBlocking {
        val s = server { it.respond(200, "the whole body") }
        val gate = CompletableDeferred<Unit>()
        val signal = HttpAttemptSignal(connectTimeoutMs = 1_000, onHeaders = { gate.complete(Unit); gate.await() })
        val body = withContext(Dispatchers.IO + signal) {
            client.forFailoverAttempt().newCall(Request.Builder().url(s.url("winner.test")).build()).executeCancellable { it.body!!.string() }
        }
        assertEquals("the whole body", body)
    }

    @Test
    fun `the failover connect timeout is applied per attempt and nothing else changes`() = runBlocking {
        assertSame("outside a failover walk the client is untouched", client, client.forFailoverAttempt(null))
        val attempt = client.forFailoverAttempt(HttpAttemptSignal(FailoverRace.CONNECT_TIMEOUT_MS))
        assertEquals(8_000, attempt.connectTimeoutMillis)
        assertEquals("the read timeout keeps its value", client.readTimeoutMillis, attempt.readTimeoutMillis)
        assertSame("the connection pool is shared", client.connectionPool, attempt.connectionPool)
        assertSame("the dispatcher (per-host cap) is shared", client.dispatcher, attempt.dispatcher)

        // 192.0.2.1 is TEST-NET-1: nothing answers. With the default it would hang (or fail fast with no route).
        val started = System.currentTimeMillis()
        val outcome = runCatching {
            withContext(Dispatchers.IO + HttpAttemptSignal(connectTimeoutMs = 400)) {
                OkHttpClient().forFailoverAttempt().newCall(Request.Builder().url("http://192.0.2.1:81/").build()).executeCancellable { it.code }
            }
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("connect failed: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is IOException)
        assertTrue("gave up after the 400 ms connect timeout, not 10 s (took $elapsed ms)", elapsed < 5_000)
    }

    @Test
    fun `a probe prefix stops at the cap whatever the server sends`() {
        val source = okio.Buffer().writeUtf8("#EXTM3U\n" + "x".repeat(10_000))
        val prefix = readAtMost(source, 1024)
        assertEquals(1024, prefix.length)
        assertTrue(prefix.startsWith("#EXTM3U"))
        assertEquals("", readAtMost(null, 1024))
    }
}
