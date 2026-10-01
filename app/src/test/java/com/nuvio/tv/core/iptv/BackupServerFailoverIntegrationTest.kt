package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.iptv.dns.PlaylistDns
import com.nuvio.tv.core.iptv.epg.XmltvClient
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.stalker.StalkerClient
import com.nuvio.tv.core.iptv.stalker.StalkerSessionManager
import com.nuvio.tv.data.remote.api.XtreamApi
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.Collections

/**
 * Step 0.3 integration — the REAL TV clients over real OkHttp sockets (MockWebServer). The MAIN
 * server is a closed port (connection refused — exactly what OkHttp throws for a dead panel) while
 * the backup answers:
 *  - Xtream: login + catalog load from the backup, active = 1, stream / catch-up URLs on the backup
 *    host; after the retry window with main healthy again, the next catalog call goes to main and
 *    URLs move back.
 *  - M3U link: the playlist download fails over; the channel URLs are the backup's lines.
 *  - Stalker: browse fails over, create_link mints on the active backup; a create_link failure on
 *    main NEVER tries a backup and never moves the active server.
 * Twin of NuvioMobile's androidHostTest BackupServerFailoverIntegrationTest (same scenarios).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BackupServerFailoverIntegrationTest {

    private val app = RuntimeEnvironment.getApplication()
    private var now = 1_000L
    private val store = InMemoryServerFailoverStateStore()
    private val failover = PlaylistServerFailover(store, clock = { now }, profileId = { 1 })
    private val servers = mutableListOf<MockWebServer>()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
    }

    /** A minimal Xtream panel + M3U host; every request is logged as "port action". */
    private fun panelDispatcher(failCreateLink: Boolean = false) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val port = request.url.port
            val action = request.url.queryParameter("action")
            requests += "$port $action"
            val self = "http://${request.url.host}:$port"
            val body = when {
                request.url.encodedPath.endsWith(".m3u") ->
                    "#EXTM3U\n#EXTINF:-1 group-title=\"News\",BBC One\n$self/live/1.ts\n"
                request.url.encodedPath.endsWith("player_api.php") -> when (action) {
                    null -> """{"user_info":{"auth":1,"status":"Active"},"server_info":{}}"""
                    "get_live_categories" -> """[{"category_id":"1","category_name":"News"}]"""
                    "get_live_streams" -> """[{"stream_id":7,"name":"BBC One","category_id":"1"}]"""
                    else -> "[]"
                }
                // Stalker portal (any endpoint path).
                else -> when (action) {
                    "handshake" -> """{"js":{"token":"T"}}"""
                    "get_profile" -> """{"js":{"id":"1","watchdog_timeout":120}}"""
                    "get_genres" -> """{"js":[{"id":"g1","title":"News"}]}"""
                    "get_all_channels" ->
                        """{"js":{"data":[{"id":"1","name":"Ch 1","tv_genre_id":"g1","cmd":"ffmpeg $self/ch/1","use_http_tmp_link":"1"}]}}"""
                    "create_link" ->
                        if (failCreateLink) return MockResponse.Builder().code(503).body("down").build()
                        else """{"js":{"cmd":"ffmpeg $self/live/1.ts?token=x"}}"""
                    else -> """{"js":[]}"""
                }
            }
            return MockResponse.Builder().code(200).body(body).build()
        }
    }

    private fun startServer(port: Int = 0, failCreateLink: Boolean = false): MockWebServer =
        MockWebServer().also {
            it.dispatcher = panelDispatcher(failCreateLink)
            it.start(port)
            servers += it
        }

    /** A port that refuses connections: bound once, then closed. */
    private fun deadPort(): Int {
        val s = MockWebServer()
        s.start()
        val port = s.port
        s.close()
        return port
    }

    private fun base(port: Int) = "http://127.0.0.1:$port"

    private fun xtreamClient(): XtreamClient {
        // The app's own Moshi (NetworkModule.provideMoshi) — the DTOs need both flex adapters.
        val moshi = com.nuvio.tv.core.di.NetworkModule.provideMoshi()
        val api = Retrofit.Builder()
            .baseUrl("https://placeholder.nuvio.tv/")
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(XtreamApi::class.java)
        return XtreamClient(api, OkHttpClient(), moshi, PlaylistDns(), failover)
    }

    @Test
    fun `xtream main down - backup serves the catalog and streams - main wins back after the window`() = runBlocking {
        val mainPort = deadPort()
        val backup = startServer()
        val acc = XtreamAccount(
            id = "${base(mainPort)}|u", name = "P", baseUrl = base(mainPort), username = "u", password = "p",
            backupUrls = listOf(base(backup.port)),
        )
        val client = xtreamClient()

        val verified = client.verify(acc)
        assertTrue("login fails over to the backup: ${verified.exceptionOrNull()}", verified.isSuccess)
        assertEquals(1, failover.activeIndex(acc))
        assertEquals(ServerFailoverState(1, 1_000L + ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS), store.read(1, acc.id))

        requests.clear()
        assertEquals(listOf("News"), client.liveCategories(acc).getOrThrow().map { it.name })
        val channels = client.liveChannels(acc, "1").getOrThrow()
        assertEquals("${base(backup.port)}/live/u/p/7.ts", channels.single().streamUrl)
        assertTrue("inside the window main is not retried: $requests", requests.all { it.startsWith("${backup.port} ") })
        assertEquals("${base(backup.port)}/live/u/p/7.ts", client.buildStreamUrl(acc, "live", 7))
        assertEquals("${base(backup.port)}/movie/u/p/9.mkv", client.buildStreamUrl(acc, "movie", 9, "mkv"))
        assertTrue(client.liveTimeshiftUrls(acc, 7, 0L, 60).all { it.startsWith("${base(backup.port)}/") })

        // The window runs out and main is healthy again: the next natural catalog call goes to main.
        val main = startServer(port = mainPort)
        now = 1_000L + ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS
        requests.clear()
        client.liveCategories(acc).getOrThrow()
        assertEquals(listOf("${main.port} get_live_categories"), requests.toList())
        assertEquals(0, failover.activeIndex(acc))
        assertEquals("${base(mainPort)}/live/u/p/7.ts", client.buildStreamUrl(acc, "live", 7))
        assertEquals(
            "a stream url cached while on the backup is moved back to main",
            "${base(mainPort)}/live/u/p/7.ts",
            failover.rebaseStreamUrl(acc, channels.single().streamUrl),
        )
    }

    @Test
    fun `building a stream url never makes a request or moves the active server`() {
        val acc = XtreamAccount(
            id = "http://xt2-main.test|u", name = "P", baseUrl = "http://xt2-main.test", username = "u", password = "p",
            backupUrls = listOf("http://xt2-backup.test"),
        )
        store.write(1, acc.id, ServerFailoverState(1, now + 60_000))
        val client = xtreamClient()
        repeat(3) { client.buildStreamUrl(acc, "live", 7) }
        assertTrue(requests.isEmpty())
        assertEquals(ServerFailoverState(1, now + 60_000), store.read(1, acc.id))
    }

    @Test
    fun `m3u link download fails over to the backup url`() = runBlocking {
        val mainPort = deadPort()
        val backup = startServer()
        val db = IptvContentDb(app)
        val dns = PlaylistDns()
        val xmltv = XmltvClient(db, OkHttpClient(), dns, XtreamMatchIndex(app), failover)
        val m3u = M3UClient(db, OkHttpClient(), M3UFileStore(app), xmltv, dns, failover)
        val acc = XtreamAccount(
            id = "m3u|${base(mainPort)}/list.m3u", name = "M", baseUrl = "${base(mainPort)}/list.m3u",
            username = "", password = "", sourceType = XtreamAccount.SOURCE_URL,
            backupUrls = listOf("${base(backup.port)}/list.m3u"),
        )

        m3u.ensureIngested(acc, force = true)

        assertEquals(1, failover.activeIndex(acc))
        assertEquals(
            "an m3u stream url is the line the serving host gave",
            "${base(backup.port)}/live/1.ts",
            m3u.liveChannels(acc, null).getOrThrow().single().streamUrl,
        )
    }

    // --- Stalker: browse fails over, create_link never does ---------------------------------------

    private fun stalkerClient() = StalkerClient(
        StalkerSessionManager(OkHttpClient(), PlaylistDns()),
        IptvContentDb(app),
        failover,
    )

    private fun stalker(mainPort: Int, backupPort: Int) = XtreamAccount(
        id = "stalker|${base(mainPort)}|00:1A:79:00:00:01", name = "S", baseUrl = base(mainPort),
        username = "", password = "", sourceType = XtreamAccount.SOURCE_STALKER,
        portalUrl = base(mainPort), macAddress = "00:1A:79:00:00:01",
        backupUrls = listOf(base(backupPort)),
    )

    @Test
    fun `stalker browse fails over and create_link mints on the active backup portal`() = runBlocking {
        val mainPort = deadPort()
        val backup = startServer()
        val acc = stalker(mainPort, backup.port)
        val client = stalkerClient()

        assertTrue("handshake/profile/genres fail over", client.verify(acc).isSuccess)
        assertEquals(1, failover.activeIndex(acc))
        assertEquals(listOf("Ch 1"), client.liveChannels(acc, null).getOrThrow().map { it.name })

        requests.clear()
        val url = client.resolveStreamUrl(acc, "live", 1, forceFresh = true)
        assertEquals("${base(backup.port)}/live/1.ts?token=x", url)
        assertEquals(listOf("${backup.port} create_link"), requests.toList())
    }

    @Test
    fun `a failed create_link on main never tries a backup and never moves the active server`() = runBlocking {
        val main = startServer(failCreateLink = true)
        val backup = startServer()
        val acc = stalker(main.port, backup.port)
        val client = stalkerClient()
        assertTrue(client.verify(acc).isSuccess)
        assertEquals(0, failover.activeIndex(acc))
        client.liveChannels(acc, null).getOrThrow()

        requests.clear()
        assertNull(client.resolveStreamUrl(acc, "live", 1, forceFresh = true))
        assertTrue("no backup request: $requests", requests.none { it.startsWith("${backup.port} ") })
        assertTrue("create_link went to main: $requests", requests.any { it == "${main.port} create_link" })
        assertEquals(0, failover.activeIndex(acc))
        assertEquals(ServerFailoverState(), store.read(1, acc.id))
    }
}
