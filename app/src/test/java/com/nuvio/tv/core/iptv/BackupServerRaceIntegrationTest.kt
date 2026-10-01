package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.iptv.dns.PlaylistDns
import com.nuvio.tv.core.iptv.epg.XmltvClient
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.stalker.StalkerClient
import com.nuvio.tv.core.iptv.stalker.StalkerSessionManager
import com.nuvio.tv.data.remote.api.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Step 0.3b integration — the REAL TV clients racing over real OkHttp sockets ([FakeHttpServer]; distinct
 * `.test` host names on loopback so the race sees different hosts), real (not virtual) time:
 *  - a healthy main costs exactly ONE request; a slow-BODY main wins at its headers; requests queued in
 *    OkHttp's own dispatcher (5 per host) never read as a slow main;
 *  - a hung main loses to a VALID backup; a parked domain's 200 HTML never wins; auth=0 surfaces at once;
 *  - the hung loser's socket is really closed, and it touches neither the breaker nor the stats;
 *  - M3U: the probe reads ≤ 1 KB with Range and closes even when the server ignores Range — one big download;
 *  - Stalker: a cancelled loser's half-built session is discarded (no re-mint), create_link never races.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BackupServerRaceIntegrationTest {

    private val app = RuntimeEnvironment.getApplication()
    private var now = 1_000L
    private val store = InMemoryServerFailoverStateStore()
    private val failover = PlaylistServerFailover(store, clock = { now }, profileId = { 1 })
    private val servers = mutableListOf<FakeHttpServer>()

    /** The production shape of the Xtream lane's client: test DNS + the per-origin breaker interceptor. */
    private val http = OkHttpClient.Builder()
        .dns(FakeHttpServer.TEST_DNS)
        .addInterceptor(com.nuvio.tv.core.di.PanelHostGuardInterceptor())
        .build()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun server(handler: (FakeHttpServer.Exchange) -> Unit) = FakeHttpServer(handler).also { servers += it }

    private val loginOk = """{"user_info":{"auth":1,"status":"Active"},"server_info":{"url":"x"}}"""

    /** A healthy Xtream panel. */
    private fun panel(ex: FakeHttpServer.Exchange) = when (ex.action) {
        null -> ex.respond(200, loginOk)
        "get_live_categories" -> ex.respond(200, """[{"category_id":"1","category_name":"News"}]""")
        "get_live_streams" -> ex.respond(200, """[{"stream_id":7,"name":"BBC One","category_id":"1"}]""")
        else -> ex.respond(200, "[]")
    }

    private fun xtreamClient(): XtreamClient {
        val moshi = com.nuvio.tv.core.di.NetworkModule.provideMoshi()
        val api = Retrofit.Builder()
            .baseUrl("https://placeholder.nuvio.tv/")
            .client(http)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(XtreamApi::class.java)
        return XtreamClient(api, http, moshi, PlaylistDns(), failover)
    }

    private fun xtream(tag: String, main: FakeHttpServer, vararg backups: FakeHttpServer) = XtreamAccount(
        id = "$tag|u", name = "P", baseUrl = main.url("$tag-main.test"), username = "u", password = "p",
        backupUrls = backups.mapIndexed { i, b -> b.url("$tag-b${i + 1}.test") },
    )

    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(what, cond())
    }

    // --- Xtream ------------------------------------------------------------------------------

    @Test
    fun `a healthy main costs exactly one request per call - no probe - backups never contacted`() = runBlocking {
        val main = server(::panel)
        val b1 = server(::panel)
        val b2 = server(::panel)
        val acc = xtream("ok", main, b1, b2)
        val client = xtreamClient()

        assertTrue(client.verify(acc).isSuccess)
        assertEquals(listOf("News"), client.liveCategories(acc).getOrThrow().map { it.name })
        assertEquals("one login + one catalog call, nothing else", 2, main.exchanges.size)
        assertEquals("backup 1 never contacted", 0, b1.exchanges.size)
        assertEquals("backup 2 never contacted", 0, b2.exchanges.size)
        assertEquals(0, failover.activeIndex(acc))
    }

    @Test
    fun `a hung main loses to a valid backup - the parked domain only ever sees the probe - the loser is closed and not blamed`() = runBlocking {
        val main = server { it.hang() }
        val parked = server { it.respond(200, "<html><body>This domain is for sale</body></html>", "text/html") }
        val good = server(::panel)
        val acc = xtream("hung", main, parked, good)
        val client = xtreamClient()

        val t0 = System.currentTimeMillis()
        val cats = client.liveCategories(acc)
        val took = System.currentTimeMillis() - t0
        assertEquals("served by backup 2: ${cats.exceptionOrNull()}", listOf("News"), cats.getOrNull()?.map { it.name })
        assertTrue("well inside one request timeout (took $took ms)", took < 10_000)
        assertEquals(2, failover.activeIndex(acc))

        assertEquals("the parked domain got the probe and nothing else", listOf<String?>(null), parked.exchanges.map { it.action })
        assertEquals("backup 2: probe, then the ONE real request", listOf(null, "get_live_categories"), good.exchanges.map { it.action })
        waitFor("main's in-flight call was really cancelled (the server saw the socket close)") { main.exchanges.single().clientClosed }

        val state = store.read(1, acc.id)
        assertNull("a cancelled loser is not a failure: no lastFailAt for main", state.stats[0]?.lastFailAtMs)
        assertTrue("the parked domain IS remembered as failed", state.stats[1]?.lastFailAtMs != null)
        val mainUrl = acc.baseUrl + "/player_api.php"
        repeat(3) { assertTrue("main's breaker untouched by the cancel", IptvPanelGuard.guard.admit(mainUrl) is PanelAdmission.Allowed) }
    }

    @Test
    fun `auth=0 from a backup probe surfaces at once and nothing fails over`() = runBlocking {
        val main = server { it.hang() }
        val refused = server { it.respond(200, """{"user_info":{"auth":0}}""") }
        val good = server(::panel)
        val acc = xtream("auth0", main, refused, good)

        val t0 = System.currentTimeMillis()
        val outcome = xtreamClient().verify(acc)
        val took = System.currentTimeMillis() - t0
        assertTrue("${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is FailoverAuthRejectedException)
        assertTrue("decided at main's stagger (took $took ms)", took in 1_400..6_000)
        assertEquals("backup 2 is never contacted", 0, good.exchanges.size)
        assertEquals(0, failover.activeIndex(acc))
    }

    @Test
    fun `a slow-body main wins at its response headers - no probe and no second download`() = runBlocking {
        val body = "[" + " ".repeat(30_000) + """{"stream_id":7,"name":"Slow One","category_id":"1"}]"""
        val main = server { ex -> if (ex.action == "get_live_streams") ex.trickle(body, pieces = 30, pauseMs = 100) else panel(ex) }
        val b1 = server(::panel)
        val acc = xtream("slowbody", main, b1)

        val t0 = System.currentTimeMillis()
        val channels = xtreamClient().liveChannels(acc, null).getOrThrow()
        val took = System.currentTimeMillis() - t0
        assertEquals(listOf("Slow One"), channels.map { it.name })
        assertTrue("the body really took longer than the 1.5 s stagger (took $took ms)", took >= 2_500)
        assertEquals("headers arrived inside the stagger: backup 1 never probed", 0, b1.exchanges.size)
        assertEquals(0, failover.activeIndex(acc))
    }

    @Test
    fun `requests queued in OkHttp's dispatcher behind their siblings never read as a slow main`() = runBlocking {
        // A healthy main that answers each catalog call in 1 s (< the 1.5 s stagger). OkHttp runs at most
        // 5 calls per host at once, so 12 concurrent calls queue: the last ones wait ~2 s before they are
        // even sent. That wait is local, not the server — the backup must never be contacted.
        val main = server { ex -> if (ex.action == "get_live_streams") Thread.sleep(1_000); panel(ex) }
        val b1 = server(::panel)
        val acc = xtream("queued", main, b1)
        val client = xtreamClient()

        val results = (1..12).map { async(Dispatchers.IO) { client.liveChannels(acc, null) } }.awaitAll()
        assertTrue(results.joinToString { "${it.exceptionOrNull()}" }, results.all { it.isSuccess })
        assertEquals("queue time is not latency: backup 1 never contacted", 0, b1.exchanges.size)
        assertEquals(0, failover.activeIndex(acc))
    }

    // --- M3U ---------------------------------------------------------------------------------

    @Test
    fun `m3u - the probe reads at most 1 KB with Range and closes a server that ignores Range - one big download`() = runBlocking {
        val playlist = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",BBC One\nhttp://b1.test/live/1.ts\n"
        val probeTotal = 8L * 1024 * 1024
        val main = server { it.hang() }
        val b1 = server { ex ->
            // Ignores Range: a probe gets the start of an 8 MB playlist, trickled; the real download gets the list.
            if (ex.headers["range"] != null) ex.stream(playlist, total = probeTotal, chunk = 1024, pauseMs = 5)
            else ex.respond(200, playlist, "audio/x-mpegurl")
        }
        val db = IptvContentDb(app)
        val dns = PlaylistDns()
        val ingestHttp = OkHttpClient.Builder().dns(FakeHttpServer.TEST_DNS).build()
        val xmltv = XmltvClient(db, ingestHttp, dns, XtreamMatchIndex(app), failover)
        val m3u = M3UClient(db, ingestHttp, M3UFileStore(app), xmltv, dns, failover)
        val acc = XtreamAccount(
            id = "m3u|race", name = "M", baseUrl = main.url("m3u-main.test") + "/list.m3u",
            username = "", password = "", sourceType = XtreamAccount.SOURCE_URL,
            backupUrls = listOf(b1.url("m3u-b1.test") + "/list.m3u"),
        )

        m3u.ensureIngested(acc, force = true)

        assertEquals(1, failover.activeIndex(acc))
        assertEquals(listOf("BBC One"), m3u.liveChannels(acc, null).getOrThrow().map { it.name })
        val probe = b1.exchanges.first()
        assertEquals("the probe asked for the first KB", "bytes=0-1023", probe.headers["range"])
        waitFor("the probe closed the connection instead of draining the body") { probe.clientClosed }
        assertTrue("the server pushed only a sliver of the 8 MB (${probe.bodyBytesSent.get()} B)", probe.bodyBytesSent.get() < probeTotal / 4)
        assertEquals("exactly one real (Range-less) download, on the winner", 1, b1.count { it.headers["range"] == null })
        waitFor("main's hung download was cancelled") { main.exchanges.single().clientClosed }
    }

    // --- Stalker -----------------------------------------------------------------------------

    /** A Stalker portal: every request is logged "action"; [override] may hang/replace one. */
    private fun portal(override: (FakeHttpServer.Exchange) -> Boolean = { false }) = server { ex ->
        if (override(ex)) return@server
        when (ex.action) {
            "handshake" -> ex.respond(200, """{"js":{"token":"T"}}""")
            "get_profile" -> ex.respond(200, """{"js":{"id":"1"}}""")
            "get_genres" -> ex.respond(200, """{"js":[{"id":"g1","title":"News"}]}""")
            "get_events" -> ex.respond(200, """{"js":{"data":{"msgs":0}}}""")
            "get_all_channels" ->
                ex.respond(200, """{"js":{"data":[{"id":"1","name":"Ch 1","tv_genre_id":"g1","cmd":"ffmpeg http://x.test/ch/1","use_http_tmp_link":"1"}]}}""")
            "create_link" -> ex.respond(200, """{"js":{"cmd":"ffmpeg http://x.test/live/1.ts?token=x"}}""")
            else -> ex.respond(200, """{"js":[]}""")
        }
    }

    private fun stalkerClient() = StalkerClient(
        StalkerSessionManager(OkHttpClient.Builder().dns(FakeHttpServer.TEST_DNS).build(), PlaylistDns()),
        IptvContentDb(app),
        failover,
    )

    private fun stalker(tag: String, main: FakeHttpServer, backup: FakeHttpServer) = XtreamAccount(
        id = "stalker|$tag", name = "S", baseUrl = main.url("$tag-main.test"), username = "", password = "",
        sourceType = XtreamAccount.SOURCE_STALKER, portalUrl = main.url("$tag-main.test"), macAddress = "00:1A:79:00:00:01",
        backupUrls = listOf(backup.url("$tag-backup.test")),
    )

    private fun FakeHttpServer.actions() = synchronized(exchanges) { exchanges.map { it.action } }

    @Test
    fun `stalker - a main whose handshake hangs loses to the backup - one handshake each and no re-mint on main`() = runBlocking {
        val main = portal { ex -> if (ex.action == "handshake") { ex.hang(); true } else false }
        val backup = portal()
        val acc = stalker("st-a", main, backup)

        assertTrue(stalkerClient().verify(acc).isSuccess)
        assertEquals(1, failover.activeIndex(acc))
        assertEquals("main: the one (discovery) handshake that lost - nothing else, ever", listOf<String?>("handshake"), main.actions())
        waitFor("main's handshake was really cancelled") { main.exchanges.single().clientClosed }
        // A first authentication is endpoint discovery + the real handshake (2 requests); the real request that
        // follows the winning probe reuses the token - single-flight, no third handshake.
        val b = backup.actions()
        assertEquals("$b", 2, b.count { it == "handshake" })
        assertEquals("$b", 1, b.count { it == "get_profile" })
        assertEquals("$b", 1, b.count { it == "get_genres" })
    }

    @Test
    fun `stalker - a loser cancelled between handshake and profile leaves no half-built session behind`() = runBlocking {
        val mainHealthy = AtomicBoolean(false)
        val main = portal { ex -> if (ex.action == "get_profile" && !mainHealthy.get()) { ex.hang(); true } else false }
        val backup = portal()
        val acc = stalker("st-b", main, backup)
        val client = stalkerClient()

        assertTrue(client.verify(acc).isSuccess)
        assertEquals(1, failover.activeIndex(acc))
        assertEquals("discovery, handshake, profile (hung)", listOf<String?>("handshake", "handshake", "get_profile"), main.actions())

        // The window runs out; main is healthy again. Its session must start from scratch (one ordinary first
        // handshake) - a leftover token with an unfinished profile would skip it and browse on a half-made session.
        mainHealthy.set(true)
        now += ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS
        val before = main.exchanges.size
        val backupBefore = backup.exchanges.size
        assertTrue(client.verify(acc).isSuccess)
        assertEquals(0, failover.activeIndex(acc))
        assertEquals(
            "a clean first authentication on main (endpoint already known: no discovery)",
            listOf<String?>("handshake", "get_profile", "get_genres"),
            main.actions().drop(before).filter { it != "get_events" },
        )
        assertEquals("the backup is not touched when main answers", backupBefore, backup.exchanges.size)
    }

    @Test
    fun `stalker - a portal that answers html is invalid and never becomes active`() = runBlocking {
        // main is slow (past its stagger) and then down, so the backup's probe runs first.
        val main = portal { ex -> if (ex.action == "handshake") { Thread.sleep(2_000); ex.respond(503, "down"); true } else false }
        val parked = server { it.respond(200, "<html><body>parked</body></html>", "text/html") }
        val acc = stalker("st-c", main, parked)
        assertTrue("nothing valid answered", stalkerClient().verify(acc).isFailure)
        assertEquals(0, failover.activeIndex(acc))
        assertTrue("no real request ever reached the parked domain: ${parked.actions()}", parked.actions().none { it == "get_genres" })
    }

    @Test
    fun `stalker - create_link never races - a hung create_link on main never contacts a backup`() = runBlocking {
        val main = portal { ex -> if (ex.action == "create_link") { ex.hang(); true } else false }
        val backup = portal()
        val acc = stalker("st-d", main, backup)
        val client = stalkerClient()
        assertTrue(client.verify(acc).isSuccess)
        assertEquals(listOf("Ch 1"), client.liveChannels(acc, null).getOrThrow().map { it.name })
        val backupBefore = backup.exchanges.size

        val t0 = System.currentTimeMillis()
        val url = withTimeoutOrNull(4_000) { client.resolveStreamUrl(acc, "live", 1, forceFresh = true) }
        assertNull("the hung create_link is still hung (no fallback)", url)
        assertTrue("it really waited past the stagger", System.currentTimeMillis() - t0 >= 3_900)
        assertTrue("create_link went to main: ${main.actions()}", main.actions().contains("create_link"))
        assertEquals("no backup request while create_link hung well past the stagger", backupBefore, backup.exchanges.size)
        assertEquals(0, failover.activeIndex(acc))
        delay(200)
    }
    @Test
    fun `stalker - requests queued behind their own siblings at the per-portal gate never start a backup probe`() = runBlocking {
        // A healthy but not instant portal: every browse call takes 800 ms, and the session lets 2 run at once.
        val main = portal { ex -> if (ex.action == "get_genres") Thread.sleep(800); false }
        val backup = portal()
        val acc = stalker("st-e", main, backup)
        val client = stalkerClient()
        assertTrue(client.verify(acc).isSuccess)   // authenticate first: the burst below is pure browse traffic

        val t0 = System.currentTimeMillis()
        val burst = (1..6).map { async(Dispatchers.IO) { client.verify(acc) } }.awaitAll()
        assertTrue(burst.all { it.isSuccess })
        assertEquals("queue time is not portal latency - the backup is never contacted: ${backup.actions()}", 0, backup.exchanges.size)
        // 6 calls through 2 permits: the last one waited ~1.6 s locally, longer than the 1.5 s stagger.
        assertTrue("the burst really did queue (took ${System.currentTimeMillis() - t0} ms)", System.currentTimeMillis() - t0 >= 2_200)
        assertEquals(0, failover.activeIndex(acc))
    }

    @Test
    fun `stalker - an authenticated backup session still proves it is alive before it wins`() = runBlocking {
        val backupAlive = AtomicBoolean(true)
        val mainSlowOnce = AtomicBoolean(true)
        val main = portal { ex ->
            // Phase 1: main hangs. Phase 2: main is slow past its stagger once, then refuses.
            if (backupAlive.get()) ex.hang() else { if (mainSlowOnce.getAndSet(false)) Thread.sleep(1_700); ex.respond(503, "down") }
            true
        }
        val backup = portal { ex -> if (!backupAlive.get()) { ex.respond(503, "down"); true } else false }
        val acc = stalker("st-f", main, backup)
        val client = stalkerClient()
        assertTrue(client.verify(acc).isSuccess)   // main hangs, the backup authenticates and answers
        assertEquals(1, failover.activeIndex(acc))

        // The backup is warm (token held) and then dies; main still hangs. Its probe must NOT pass on the
        // cached token alone: one harmless get_events ping (a handshake would rotate the MAC's token).
        backupAlive.set(false)
        now += ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS
        val before = backup.exchanges.size
        val outcome = withTimeoutOrNull(30_000) { client.verify(acc) }
        assertTrue("nothing alive answered: $outcome", outcome != null && outcome.isFailure)
        val after = backup.actions().drop(before)
        assertTrue("warm probe pings instead of trusting the token: $after", after.contains("get_events"))
        assertTrue("and never re-handshakes: $after", after.none { it == "handshake" })
    }
}
