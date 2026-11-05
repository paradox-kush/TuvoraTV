package com.nuvio.tv.core.livepause

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The capture's edges against a real server: repeated resets, a blocked read being cancelled, a full disk on HLS. */
class OkHttpLiveCaptureEdgeTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val routes = ConcurrentHashMap<String, (Int) -> MockResponse>()
    private lateinit var scope: CoroutineScope
    private lateinit var root: File

    private fun hitsOf(path: String) = hits[path]?.get() ?: 0

    @Before
    fun start() {
        root = tmp.newFolder("live-pause")
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
    }

    @After
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun capture(path: String, free: () -> Long = { 10L * 1024 * 1024 * 1024 }): OkHttpLiveCapture {
        val spec = LiveCaptureSpec(
            instanceId = "player-1",
            minter = { LiveLink(server.url(path).toString(), emptyMap()) },
            singleUseLink = false,
            keepMs = LivePausePolicy.DEFAULT_KEEP_MS,
            bitrateBps = 4_000_000L,
            maxCaptureBitrateBps = 15_000_000L,
        )
        return OkHttpLiveCapture(
            spec = spec,
            scope = scope,
            store = FileLiveSegmentStore(File(root, "player-1")),
            clients = LiveCaptureClients { OkHttpClient.Builder().retryOnConnectionFailure(false).build() },
            io = Dispatchers.IO,
            monotonicMs = { System.nanoTime() / 1_000_000L },
            freeBytes = free,
            cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    private fun tsSegment(): Buffer = Buffer().write(Ts.concat(Ts.tables(), Ts.stream(0, 1_000, stepMs = 100)))

    private suspend fun OkHttpLiveCapture.awaitEvent(predicate: (LiveCaptureEvent) -> Boolean): LiveCaptureEvent =
        withTimeout(30_000) { events.first(predicate) }

    @Test
    fun `more than three sequence resets in one capture fail it as SEQUENCE_RESET`() = runBlocking {
        val starts = listOf(1000, 800, 600, 400, 200)
        routes["/hls/index.m3u8"] = { n ->
            val from = starts[(n - 1).coerceAtMost(starts.lastIndex)]
            MockResponse().setBody(
                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:$from\n" +
                    "#EXTINF:1.000,\nseg$from.ts\n#EXTINF:1.000,\nseg${from + 1}.ts\n",
            )
        }
        for (from in starts) for (i in from..from + 1) routes["/hls/seg$i.ts"] = { MockResponse().setBody(tsSegment()) }
        val capture = capture("/hls/index.m3u8")
        capture.start()

        val failed = capture.awaitEvent { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed

        assertEquals(LivePauseFailure.SEQUENCE_RESET, failed.reason)
        assertEquals("the fourth restart is refused before its segments are fetched", 0, hitsOf("/hls/seg201.ts"))
        capture.dispose()
    }

    @Test
    fun `disposing while a body read is blocked on the socket ends the capture and deletes the files promptly`() = runBlocking {
        // 188 bytes arrive, then the server goes quiet for 30 s: the read is blocked.
        routes["/live"] = {
            MockResponse().setBody(Buffer().write(Ts.concat(Ts.tables(), Ts.stream(0, 20_000, stepMs = 20))))
                .throttleBody(188, 30, TimeUnit.SECONDS)
        }
        val capture = capture("/live")
        capture.start()
        withTimeout(10_000) { while (hitsOf("/live") == 0) delay(20) }
        delay(500)

        val started = System.nanoTime()
        capture.dispose()
        withTimeout(8_000) { while (File(root, "player-1").exists()) delay(20) }

        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancel reached the blocked read (took $tookMs ms, the socket read timeout is 10 s)", tookMs < 5_000)
    }

    @Test
    fun `the disk filling up mid-segment on HLS fails as NO_SPACE and leaves no half file`() = runBlocking {
        routes["/hls/index.m3u8"] = {
            MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2.000,\nseg0.ts\n#EXTINF:2.000,\nseg1.ts\n")
        }
        for (i in 0..1) routes["/hls/seg$i.ts"] = { MockResponse().setBody(tsSegment()) }
        val calls = AtomicInteger()
        // diskStart and the segment's up-front estimate see space; the first chunk's check sees none.
        val capture = capture("/hls/index.m3u8", free = { if (calls.incrementAndGet() <= 2) 10L * 1024 * 1024 * 1024 else 0L })
        capture.start()

        val failed = capture.awaitEvent { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed

        assertEquals(LivePauseFailure.NO_SPACE, failed.reason)
        val leftovers = File(root, "player-1").listFiles().orEmpty().map { it.name }
        assertFalse("no .part left behind: $leftovers", leftovers.any { it.endsWith(".part") })
        capture.dispose()
    }
}
