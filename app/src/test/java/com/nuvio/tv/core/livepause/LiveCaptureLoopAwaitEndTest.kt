package com.nuvio.tv.core.livepause

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** [LiveCaptureLoop.awaitEnd] returns when the capture coroutine is over, for any reason, and not before. */
class LiveCaptureLoopAwaitEndTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val routes = ConcurrentHashMap<String, (Int) -> MockResponse>()
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File

    @Before
    fun start() {
        dir = File(tmp.newFolder("rec"), "one")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val n = hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                return routes[path]?.invoke(n) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val seg = Buffer().write(Ts.concat(Ts.tables(), Ts.stream(0, 1_500, stepMs = 100))).readByteString()
        routes["/hls/index.m3u8"] = { MockResponse().setResponseCode(200).setBody(playlist(ended = false)) }
        for (i in 0..1) routes["/hls/seg$i.ts"] = { MockResponse().setResponseCode(200).setBody(Buffer().write(seg)) }
    }

    @After
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun hitsOf(path: String) = hits[path]?.get() ?: 0

    private fun playlist(ended: Boolean) =
        "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:30\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:30.000,\nseg0.ts\n#EXTINF:30.000,\nseg1.ts\n" +
            if (ended) "#EXT-X-ENDLIST\n" else ""

    private fun loop(
        path: String = "/hls/index.m3u8",
        free: () -> Long = { 10L * 1024 * 1024 * 1024 },
    ) = LiveCaptureLoop(
        spec = LiveCaptureSpec(
            instanceId = "rec",
            minter = { LiveLink(server.url(path).toString(), emptyMap()) },
            singleUseLink = false,
            keepMs = LivePausePolicy.DEFAULT_KEEP_MS,
            bitrateBps = 4_000_000L,
            maxCaptureBitrateBps = 15_000_000L,
        ),
        scope = scope,
        store = FileLiveSegmentStore(dir),
        clients = LiveCaptureClients { OkHttpClient.Builder().retryOnConnectionFailure(false).build() },
        io = Dispatchers.IO,
        monotonicMs = { System.nanoTime() / 1_000_000L },
        freeBytes = free,
        cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        retention = LiveRetention.KeepAll,
        reconnectDelayMs = { 0L },
    )

    private suspend fun awaitFirstSegment() = withTimeout(20_000) { while (hitsOf("/hls/seg0.ts") == 0) delay(20) }

    @Test
    fun `awaitEnd does not return while the loop is running`() = runBlocking {
        val l = loop()
        l.start()
        awaitFirstSegment()
        assertNull("still capturing, so still waiting", withTimeoutOrNull(600) { l.awaitEnd() })
        l.dispose()
    }

    @Test
    fun `awaitEnd returns after stop`() = runBlocking {
        val l = loop()
        l.start()
        awaitFirstSegment()
        l.stop()
        assertNotNull("returned", withTimeoutOrNull(5_000) { l.awaitEnd(); true })
    }

    @Test
    fun `awaitEnd returns after dispose`() = runBlocking {
        val l = loop()
        l.start()
        awaitFirstSegment()
        l.dispose()
        assertNotNull("returned", withTimeoutOrNull(5_000) { l.awaitEnd(); true })
    }

    @Test
    fun `awaitEnd returns after a failure and the Failed event was emitted first`() = runBlocking {
        val calls = AtomicInteger()
        val l = loop(free = { if (calls.incrementAndGet() <= 3) 10L * 1024 * 1024 * 1024 else 0L })
        l.start()
        assertNotNull("returned", withTimeoutOrNull(10_000) { l.awaitEnd(); true })
        val failed = l.events.replayCache.filterIsInstance<LiveCaptureEvent.Failed>()
        assertEquals("one Failed already emitted", 1, failed.size)
        assertEquals(LivePauseFailure.NO_SPACE, failed.single().reason)
    }

    @Test
    fun `awaitEnd returns after a clean provider end with no Failed event`() = runBlocking {
        routes["/hls/index.m3u8"] = { MockResponse().setResponseCode(200).setBody(playlist(ended = true)) }
        val l = loop()
        l.start()
        assertNotNull("returned", withTimeoutOrNull(10_000) { l.awaitEnd(); true })
        assertTrue("no Failed", l.events.replayCache.none { it is LiveCaptureEvent.Failed })
    }

    @Test
    fun `a dead provider gives up fast with an injected reconnect delay`() = runBlocking {
        routes["/live"] = { MockResponse().setResponseCode(503) }
        val l = loop(path = "/live")
        val started = System.nanoTime()
        l.start()
        assertNotNull("returned", withTimeoutOrNull(10_000) { l.awaitEnd(); true })
        val tookMs = (System.nanoTime() - started) / 1_000_000
        val failed = l.events.replayCache.filterIsInstance<LiveCaptureEvent.Failed>().single()
        assertEquals(LivePauseFailure.PROVIDER_REFUSED, failed.reason)
        assertEquals("first try + 3 retries", 4, hitsOf("/live"))
        assertTrue("no 2/4/8 s backoff (took $tookMs ms)", tookMs < 3_000)
    }
}
