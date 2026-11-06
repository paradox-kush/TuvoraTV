package com.nuvio.tv.core.livepause

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

/**
 * The Android capture against a real HTTP server: TS and HLS, over a real directory. Real time on purpose
 * (blocking sockets); only the give-up test uses the virtual clock for its backoff.
 */
class OkHttpLiveCaptureTest {
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

    private fun tsBody(): Buffer = Buffer().write(Ts.concat(Ts.tables(), Ts.stream(0, 20_000, stepMs = 20)))

    private fun spec(
        mint: LiveLinkMinter,
        singleUse: Boolean = false,
    ) = LiveCaptureSpec(
        instanceId = "player-1",
        minter = mint,
        singleUseLink = singleUse,
        keepMs = LivePausePolicy.DEFAULT_KEEP_MS,
        bitrateBps = 4_000_000L,
        maxCaptureBitrateBps = 15_000_000L,
    )

    private fun capture(
        mint: LiveLinkMinter,
        singleUse: Boolean = false,
        free: () -> Long = { 10L * 1024 * 1024 * 1024 },
        parent: CoroutineScope = scope,
        io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = { System.nanoTime() / 1_000_000L },
        reconnectDelayMs: (Long) -> Long = { it },
    ): OkHttpLiveCapture {
        val dir = File(root, "player-1")
        val store = FileLiveSegmentStore(dir)
        return OkHttpLiveCapture(
            spec = spec(mint, singleUse),
            scope = parent,
            store = store,
            clients = LiveCaptureClients { OkHttpClient.Builder().retryOnConnectionFailure(false).build() },
            io = io,
            monotonicMs = clock,
            freeBytes = free,
            cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            reconnectDelayMs = reconnectDelayMs,
        )
    }

    private fun link(path: String) = LiveLink(server.url(path).toString(), mapOf("User-Agent" to "TuvoraTest/1"))

    private suspend fun OkHttpLiveCapture.awaitEvent(predicate: (LiveCaptureEvent) -> Boolean): LiveCaptureEvent =
        withTimeout(20_000) { events.first(predicate) }

    // ---- TS

    @Test
    fun `a TS stream becomes a playable local playlist`() = runBlocking {
        routes["/live"] = { MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp2t").setBody(tsBody()) }
        val capture = capture({ link("/live") })
        capture.start()

        val ready = capture.awaitEvent { it is LiveCaptureEvent.Ready } as LiveCaptureEvent.Ready
        assertTrue("file uri", ready.playlistUri.startsWith("file://"))
        val playlist = File(ready.playlistUri.removePrefix("file://"))
        val text = playlist.readText()
        assertTrue("a playlist", text.startsWith("#EXTM3U"))
        assertTrue("with pieces", text.contains("#EXTINF:"))
        val firstPiece = File(playlist.parentFile, text.lines().first { it.endsWith(".ts") })
        val bytes = firstPiece.readBytes()
        assertEquals("a whole number of TS packets", 0, bytes.size % 188)
        assertEquals("starts on a sync byte", 0x47, bytes[0].toInt())
        assertEquals("the app's UA reached the server", "TuvoraTest/1", server.takeRequest().getHeader("User-Agent"))
        capture.dispose()
    }

    @Test
    fun `dispose deletes the capture directory`() = runBlocking {
        routes["/live"] = { MockResponse().setResponseCode(200).setBody(tsBody()) }
        val capture = capture({ link("/live") })
        capture.start()
        capture.awaitEvent { it is LiveCaptureEvent.Ready }
        assertTrue(File(root, "player-1").isDirectory)

        capture.dispose()
        withTimeout(10_000) { while (File(root, "player-1").exists()) delay(50) }
        assertFalse("directory removed", File(root, "player-1").exists())
    }

    // ---- never re-open a single-use link

    @Test
    fun `a single use link is never requested twice and every re-open mints a new one`() = runBlocking {
        val minted = AtomicInteger()
        routes["/live/1"] = { MockResponse().setResponseCode(200).setBody(tsBody()) }
        routes["/live/2"] = { MockResponse().setResponseCode(200).setBody(tsBody()) }
        val capture = capture({ link("/live/${minted.incrementAndGet()}") }, singleUse = true, reconnectDelayMs = { 0L })
        capture.start()
        // The first body ends (a drop): the second open must be a fresh link.
        withTimeout(20_000) { while (hitsOf("/live/2") == 0) delay(50) }

        assertEquals("first link opened once", 1, hitsOf("/live/1"))
        assertTrue("minted again for the re-open", minted.get() >= 2)
        capture.dispose()
    }

    // ---- HLS

    private fun mediaPlaylist(from: Int, to: Int, ended: Boolean): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:$from\n")
        for (i in from..to) append("#EXTINF:2.000,\nseg$i.ts\n")
        if (ended) append("#EXT-X-ENDLIST\n")
    }

    @Test
    fun `an HLS stream saves each segment once`() = runBlocking {
        val seg = Buffer().write(Ts.concat(Ts.tables(), Ts.stream(0, 1_500, stepMs = 100))).readByteString()
        routes["/hls/index.m3u8"] = { n ->
            val body = when (n) {
                1 -> mediaPlaylist(0, 1, false)
                2 -> mediaPlaylist(0, 2, false)
                else -> mediaPlaylist(0, 2, true)
            }
            MockResponse().setResponseCode(200).setBody(body)
        }
        for (i in 0..2) routes["/hls/seg$i.ts"] = { MockResponse().setResponseCode(200).setBody(Buffer().write(seg)) }
        val capture = capture({ link("/hls/index.m3u8") })
        capture.start()

        // 3 segments in all; the stream ends with ENDLIST, so nothing more is fetched.
        withTimeout(20_000) { while (hitsOf("/hls/seg2.ts") == 0 || hitsOf("/hls/index.m3u8") < 3) delay(50) }
        delay(700)
        for (i in 0..2) assertEquals("segment $i fetched once", 1, hitsOf("/hls/seg$i.ts"))
        val text = File(root, "player-1/index.m3u8").readText()
        assertEquals("three pieces listed", 3, Regex("#EXTINF").findAll(text).count())
        assertTrue("no half-written part left behind", File(root, "player-1").listFiles().orEmpty().none { it.name.endsWith(".part") })
        capture.dispose()
    }

    @Test
    fun `an HLS master picks the variant under the bitrate cap`() = runBlocking {
        routes["/m/master.m3u8"] = {
            MockResponse().setBody(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=30000000,RESOLUTION=3840x2160\nuhd.m3u8\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080\nhd.m3u8\n",
            )
        }
        routes["/m/hd.m3u8"] = { MockResponse().setBody(mediaPlaylist(0, 0, true)) }
        routes["/m/uhd.m3u8"] = { MockResponse().setBody(mediaPlaylist(0, 0, true)) }
        routes["/m/seg0.ts"] = { MockResponse().setBody(Buffer().write(Ts.concat(Ts.tables(), Ts.stream(0, 1_000, stepMs = 100)))) }
        val capture = capture({ link("/m/master.m3u8") })
        capture.start()
        capture.awaitEvent { it is LiveCaptureEvent.Ready }
        assertEquals("the 15 Mbps cap rules out the 30 Mbps variant", 0, hitsOf("/m/uhd.m3u8"))
        assertEquals(1, hitsOf("/m/hd.m3u8"))
        capture.dispose()
    }

    // ---- failure

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a dead server gives up after the capped retries`() = runTest {
        routes["/live"] = { MockResponse().setResponseCode(503) }
        val capture = capture(
            { link("/live") },
            parent = this,
            io = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime },
        )
        capture.start()
        advanceUntilIdle()

        val failed = capture.events.replayCache.filterIsInstance<LiveCaptureEvent.Failed>().single()
        assertEquals(LivePauseFailure.PROVIDER_REFUSED, failed.reason)
        assertEquals("first try + 3 retries, not forever", 4, hitsOf("/live"))
        capture.dispose()
    }

    @Test
    fun `no free space at the start fails as NO_SPACE before touching the network`() = runBlocking {
        routes["/live"] = { MockResponse().setBody(tsBody()) }
        val capture = capture({ link("/live") }, free = { 50L * 1024 * 1024 })
        capture.start()
        val failed = capture.awaitEvent { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.NO_SPACE, failed.reason)
        assertEquals("no request made", 0, hitsOf("/live"))
        capture.dispose()
    }

    @Test
    fun `the disk filling up mid-capture fails as NO_SPACE`() = runBlocking {
        routes["/live"] = { MockResponse().setBody(tsBody()) }
        val calls = AtomicInteger()
        // diskStart + the first chunk's check see space; later checks see none.
        val capture = capture({ link("/live") }, free = { if (calls.incrementAndGet() <= 2) 10L * 1024 * 1024 * 1024 else 0L })
        capture.start()
        val failed = capture.awaitEvent { it is LiveCaptureEvent.Failed } as LiveCaptureEvent.Failed
        assertEquals(LivePauseFailure.NO_SPACE, failed.reason)
        capture.dispose()
    }

    @Test
    fun `the startup sweep removes only capture directories older than six hours`() {
        val old = File(root, "old").apply { mkdirs(); File(this, "seg0.ts").writeText("x") }
        val fresh = File(root, "fresh").apply { mkdirs(); File(this, "seg0.ts").writeText("x") }
        val now = System.currentTimeMillis()
        old.listFiles()!!.forEach { it.setLastModified(now - 7 * 3600_000L) }
        old.setLastModified(now - 7 * 3600_000L)

        val removed = FileLiveSegmentStore.sweepStale(root, now)

        assertEquals(1, removed)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }
}
