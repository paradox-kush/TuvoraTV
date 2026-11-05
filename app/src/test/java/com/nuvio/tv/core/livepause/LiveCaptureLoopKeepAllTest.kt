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
import java.util.concurrent.atomic.AtomicInteger

/** [LiveRetention.KeepAll] (recordings) against a real HTTP server and directory: never trims, stop keeps, no room fails. */
class LiveCaptureLoopKeepAllTest {
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
        routes["/hls/index.m3u8"] = { MockResponse().setResponseCode(200).setBody(playlist(0, 5)) }
        for (i in 0..5) routes["/hls/seg$i.ts"] = { MockResponse().setResponseCode(200).setBody(Buffer().write(seg)) }
    }

    @After
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun hitsOf(path: String) = hits[path]?.get() ?: 0

    private fun playlist(from: Int, to: Int) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:30\n#EXT-X-MEDIA-SEQUENCE:$from\n")
        for (i in from..to) append("#EXTINF:30.000,\nseg$i.ts\n")
    }

    private fun loop(retention: LiveRetention, free: () -> Long = { 10L * 1024 * 1024 * 1024 }) = LiveCaptureLoop(
        spec = LiveCaptureSpec(
            instanceId = "rec",
            minter = { LiveLink(server.url("/hls/index.m3u8").toString(), emptyMap()) },
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
        retention = retention,
    )

    private suspend fun awaitAllSegments() =
        withTimeout(20_000) { while (hitsOf("/hls/seg5.ts") == 0) delay(50) }

    private fun pieceCount() = Regex("#EXTINF").findAll(File(dir, "index.m3u8").readText()).count()

    @Test
    fun `KeepAll keeps every piece past the rewind window and writes an EVENT playlist`() = runBlocking {
        val keepAll = loop(LiveRetention.KeepAll)
        keepAll.start()
        awaitAllSegments()
        delay(500)
        assertEquals("all six pieces stay listed", 6, pieceCount())
        assertTrue("EVENT playlist", File(dir, "index.m3u8").readText().contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        for (i in 0..5) assertTrue("seg$i.ts kept", File(dir, "seg$i.ts").exists())
        keepAll.dispose()
    }

    @Test
    fun `Trim with a short window drops what KeepAll keeps`() = runBlocking {
        val trim = loop(LiveRetention.Trim(keepMs = 60_000))
        trim.start()
        awaitAllSegments()
        delay(500)
        assertTrue("the rewind buffer trimmed", pieceCount() < 6)
        assertFalse("not an EVENT playlist", File(dir, "index.m3u8").readText().contains("EVENT"))
        trim.dispose()
    }

    @Test
    fun `stop finalises the playlist with ENDLIST and keeps the files`() = runBlocking {
        val keepAll = loop(LiveRetention.KeepAll)
        keepAll.start()
        awaitAllSegments()
        keepAll.stop()
        val text = File(dir, "index.m3u8").readText()
        assertTrue("finalised", text.contains("#EXT-X-ENDLIST"))
        assertEquals("every piece still listed", 6, pieceCount())
        for (i in 0..5) assertTrue("seg$i.ts kept", File(dir, "seg$i.ts").exists())
        delay(300)
        assertTrue("the directory is still there after stop", dir.isDirectory)
    }

    @Test
    fun `a full disk fails with NO_SPACE and keeps what was saved`() = runBlocking {
        val calls = AtomicInteger()
        val keepAll = loop(LiveRetention.KeepAll, free = { if (calls.incrementAndGet() <= 3) 10L * 1024 * 1024 * 1024 else 0L })
        keepAll.start()
        val failed = withTimeout(20_000) { keepAll.events.first { it is LiveCaptureEvent.Failed } } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.NO_SPACE, failed.reason)
        assertTrue("the first piece was kept, not dropped for room", File(dir, "seg0.ts").exists())
        assertTrue("the playlist was kept", File(dir, "index.m3u8").exists())
        delay(300)
        assertTrue("still there: Failed does not delete", File(dir, "seg0.ts").exists())
    }
}
