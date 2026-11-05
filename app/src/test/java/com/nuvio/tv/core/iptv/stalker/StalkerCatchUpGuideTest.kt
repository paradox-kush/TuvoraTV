package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.nuvio.tv.core.iptv.PlaylistServerFailover
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.dns.PlaylistDns
import com.nuvio.tv.ui.screens.iptv.GuideTimeTravel
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Device pass (Android TV emulator): a Stalker portal's Live TV guide showed NO past programmes, so a
 * replay could never be started. Portal JSON in, what the guide can travel to and show out:
 *
 *  - the lineup row's `tv_archive_duration` (HOURS on Ministra) must reach the channel as days, the
 *    unit [GuideTimeTravel] and the replay window speak;
 *  - the bulk `get_epg_info` rows of the past, with their `mark_archive` and programme `id`, must be
 *    stored and readable for the window the guide opens on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StalkerCatchUpGuideTest {

    private lateinit var server: MockWebServer
    private val context = RuntimeEnvironment.getApplication()
    private val contentDb = IptvContentDb(context)
    private val half = 30 * 60_000L
    private val base = System.currentTimeMillis() / half * half

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.url.queryParameterNames.associateWith { request.url.queryParameter(it).orEmpty() }
                return MockResponse.Builder().code(200).body(respond(q)).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        unmockkStatic(Log::class)
    }

    /** 30-minute programmes from now-24h to now+6h, past rows archived, ids like a real Ministra EPG. */
    private fun epgRows(): String = (-48..11).joinToString(",") { i ->
        val start = (base + i * half) / 1000
        val past = i < 0
        """{"id":"${9000 + i + 48}","name":"P$i","descr":"d","start_timestamp":"$start","stop_timestamp":"${start + 1800}","mark_archive":${if (past) 1 else 0}}"""
    }

    private fun respond(q: Map<String, String>): String = when (q["action"]) {
        "handshake" -> """{"js":{"token":"T"}}"""
        "get_profile" -> """{"js":{"id":"1","status":0}}"""
        "get_modules" -> """{"js":{"all_modules":[],"disabled_modules":[]}}"""
        "get_genres" -> """{"js":[{"id":"1","title":"News"}]}"""
        "get_all_channels" -> """{"js":{"data":[
            {"id":"1","name":"Archive 48h","cmd":"ffrt http://localhost/ch/1","tv_genre_id":"1","tv_archive":"1","tv_archive_duration":"48"},
            {"id":"2","name":"Archive 25h","cmd":"ffrt http://localhost/ch/2","tv_genre_id":"1","tv_archive":1,"tv_archive_duration":25},
            {"id":"3","name":"No archive","cmd":"ffrt http://localhost/ch/3","tv_genre_id":"1","tv_archive":"0","tv_archive_duration":"0"}]}}"""
        "get_epg_info" -> """{"js":{"data":{"1":[${epgRows()}]}}}"""
        else -> """{"js":[]}"""
    }

    private val acc by lazy {
        XtreamAccount(
            id = "stalker|catchup", name = "Catch-up", baseUrl = "", username = "", password = "",
            sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = server.url("/stalker_portal/c/").toString(), macAddress = "00:1A:79:00:00:02",
        )
    }

    private fun client() = StalkerClient(
        StalkerSessionManager(OkHttpClient(), PlaylistDns()),
        contentDb,
        PlaylistServerFailover.detached(),
    )

    @Test
    fun `a portal archive depth in hours reaches the channel as days`() = runBlocking {
        val channels = client().liveChannels(acc, null).getOrThrow().associateBy { it.streamId }
        assertEquals("48 hours is 2 days", 2, channels.getValue(1).catchUpDays)
        assertEquals("25 hours rounds UP to 2 days, never down past what the portal keeps", 2, channels.getValue(2).catchUpDays)
        assertEquals("no archive, no depth", 0, channels.getValue(3).catchUpDays)
    }

    @Test
    fun `the guide can travel back exactly as far as the channel's stated archive`() = runBlocking {
        val ch = client().liveChannels(acc, null).getOrThrow().first { it.streamId == 1 }
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        val floor = GuideTimeTravel.earliestWindowStartMs(now, ch.catchUpDays)
        // 48 h of archive: the traveller stops at two days, not at the 8-day storage floor.
        assertTrue("stops at the stated window", floor >= now - 2 * day - GuideTimeTravel.SLOT_MS)
    }

    @Test
    fun `the archived past of the bulk guide is stored with its programme ids`() = runBlocking {
        val c = client()
        c.liveChannels(acc, null).getOrThrow()
        val now = c.shortEpg(acc, 1).getOrThrow()
        assertNotNull(now)
        val past = contentDb.epgWindow(acc.id, "1", base - 2 * 60 * 60_000L, base)
        assertEquals("four past half-hours", 4, past.size)
        assertTrue("every past row is marked archived", past.all { it.hasArchive })
        assertTrue("and carries the portal's programme id", past.all { !it.programmeId.isNullOrBlank() })
    }
}
