package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.nuvio.tv.core.iptv.PlaylistServerFailover
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.dns.PlaylistDns
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * B02 (Wave 2): genuine Ministra has NO `type=series` module. A series is a `type=vod` row with
 * `is_series=1` (stock vod.class.php); get_ordered_list walks it as movie_id -> seasons,
 * +season_id -> episodes (paged), +episode_id -> files whose `cmd` is what create_link plays.
 * Before this, the Series tab asked the missing module and showed "returned nothing" (reported
 * 2026-10-04: "Stalker TV shows do not load, live + movies OK"). XC-family portals keep `type=series`.
 *
 * The fake portal below speaks either dialect, shaped like research/stalker-mock-portal's
 * `--dialect ministra` (which the on-device run uses).
 */
class StalkerMinistraSeriesTest {

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile private var ministra = true

    private val episodesPerSeason = 16   // > one 14-row page: episodes must be paged

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
                requests += q
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

    // --- the fake portal ---------------------------------------------------------------------

    private fun respond(q: Map<String, String>): String {
        val type = q["type"]; val action = q["action"]
        if (action == "handshake") return """{"js":{"token":"T"}}"""
        if (action == "get_profile") return """{"js":{"id":"1","status":0}}"""
        if (action == "get_modules") return """{"js":{"all_modules":[],"disabled_modules":[]}}"""
        if (action == "create_link") return """{"js":{"cmd":"ffmpeg http://media.test/play?c=${q["cmd"]}&s=${q["series"].orEmpty()}"}}"""
        if (type == "series") {
            if (ministra) return """{"js":null}"""
            return when {
                action == "get_categories" -> """{"js":[{"id":"*","title":"All"},{"id":"7","title":"Drama"}]}"""
                q["movie_id"].orEmpty().isNotEmpty() ->
                    """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"900:1","name":"Season 1","cmd":"auto /media/series/900/s1","series":["1","2"]}]}}"""
                else -> """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"900","name":"XC Show","cmd":"","category_id":"7"}]}}"""
            }
        }
        if (type == "vod") {
            if (action == "get_categories") return """{"js":[{"id":"*","title":"All"},{"id":"1","title":"Drama"}]}"""
            val movieId = q["movie_id"].orEmpty()
            val seasonId = q["season_id"].orEmpty()
            val episodeId = q["episode_id"].orEmpty()
            val page = q["p"]?.toIntOrNull() ?: 1
            return when {
                episodeId.isNotEmpty() ->
                    page(listOf("""{"id":"$episodeId","name":"English / HD","is_file":true,"cmd":"/media/file_$episodeId.mpg"}"""), 1)
                seasonId.isNotEmpty() -> {
                    val sid = seasonId.toInt()
                    val all = (1..episodesPerSeason).map { e ->
                        """{"id":"${sid * 100 + e}","season_id":"$sid","series_number":"$e","name":"Episode $e. Title $e","is_episode":true}"""
                    }
                    page(all.drop((page - 1) * 14).take(14), all.size)
                }
                movieId.isNotEmpty() -> {
                    val mid = movieId.toInt()
                    page((1..2).map { n -> """{"id":"${mid * 10 + n}","season_number":"$n","name":"Season $n","is_season":true}""" }, 2)
                }
                else -> {
                    val rows = listOf(
                        """{"id":"101","name":"A Movie","cmd":"/media/101.mpg","category_id":"1","is_series":"0"}""",
                        """{"id":"500","name":"Silent Harbour","cmd":"/media/500.mpg","category_id":"1","is_series":"1","description":"A show."}""",
                        """{"id":"102","name":"Another Movie","cmd":"/media/102.mpg","category_id":"1","is_series":0}""",
                        """{"id":"501","name":"Silent Orbit","cmd":"/media/501.mpg","category_id":"1","is_series":1}""",
                    )
                    val search = q["search"].orEmpty().lowercase()
                    val hits = if (search.isEmpty()) rows else rows.filter { it.lowercase().contains(search) }
                    page(hits, hits.size)
                }
            }
        }
        return """{"js":[]}"""
    }

    private fun page(rows: List<String>, total: Int) =
        """{"js":{"total_items":$total,"max_page_items":14,"data":[${rows.joinToString(",")}]}}"""

    // --- the client --------------------------------------------------------------------------

    private val acc by lazy {
        XtreamAccount(
            id = "stalker|ministra", name = "Ministra", baseUrl = "", username = "", password = "",
            sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = server.url("/stalker_portal/c/").toString(), macAddress = "00:1A:79:00:00:01",
        )
    }

    private fun client() = StalkerClient(
        StalkerSessionManager(OkHttpClient(), PlaylistDns()),
        mockk<IptvContentDb>(relaxed = true),
        PlaylistServerFailover.detached(),
    )

    @Test
    fun `Ministra series categories are the vod categories, not an error`() = runBlocking {
        val cats = client().seriesCategories(acc).getOrThrow()
        assertEquals("vod's categories stand in for the missing module", listOf("1"), cats.map { it.id })
    }

    @Test
    fun `Ministra series are the is_series vod rows, and movies leave them out`() = runBlocking {
        val c = client()
        c.seriesCategories(acc).getOrThrow()
        assertEquals("series = is_series rows", listOf(500, 501), c.series(acc, "1").getOrThrow().map { it.seriesId })
        assertEquals("movies exclude series rows", listOf(101, 102), c.vodMovies(acc, "1").getOrThrow().map { it.streamId })
    }

    @Test
    fun `Ministra series detail walks seasons and every episode page`() = runBlocking {
        val c = client()
        c.seriesCategories(acc).getOrThrow()
        c.series(acc, "1").getOrThrow()
        val detail = c.seriesInfo(acc, 500).getOrThrow()
        assertEquals("2 seasons x 16 episodes", 2 * episodesPerSeason, detail.episodes.size)
        assertEquals("ids keep the seriesId:season:episode shape", "500:2:16", detail.episodes.last().episodeId)
        assertEquals("the portal's episode name", "Episode 16. Title 16", detail.episodes.last().title)
        assertTrue("no type=series request on the detail path",
            requests.filter { it["movie_id"] == "500" }.all { it["type"] == "vod" })
    }

    @Test
    fun `Ministra episode play mints the episode's file cmd`() = runBlocking {
        val c = client()
        c.seriesCategories(acc).getOrThrow()
        val url = c.resolveEpisodeUrl(acc, seriesId = 500, season = 2, episodeNum = 3)
        assertNotNull("an episode on a Ministra portal must play", url)
        val mint = requests.last { it["action"] == "create_link" }
        assertEquals("type", "vod", mint["type"])
        // season id 5002 (500*10+2), episode id 500203 (5002*100+3) in the fake portal.
        assertEquals("the FILE cmd, not a season container", "/media/file_500203.mpg", mint["cmd"])
        assertFalse("no XC series={n} argument", mint.containsKey("series"))
    }

    @Test
    fun `an episode resolves on a cold session without browsing first`() = runBlocking {
        // Continue Watching after a restart: nothing cached, dialect unknown.
        val url = client().resolveEpisodeUrl(acc, seriesId = 501, season = 1, episodeNum = 1)
        assertNotNull(url)
        assertEquals("/media/file_501101.mpg", requests.last { it["action"] == "create_link" }["cmd"])
    }

    @Test
    fun `Ministra series search filters vod search to series rows`() = runBlocking {
        val hits = client().searchSeries(acc, "silent")
        assertEquals("series rows only", listOf(500, 501), hits.map { it.seriesId })
    }

    @Test
    fun `an XC-family portal keeps type=series end to end`() = runBlocking {
        ministra = false
        val c = client()
        assertEquals("series categories", listOf("7"), c.seriesCategories(acc).getOrThrow().map { it.id })
        assertEquals("series list", listOf(900), c.series(acc, "7").getOrThrow().map { it.seriesId })
        c.resolveEpisodeUrl(acc, seriesId = 900, season = 1, episodeNum = 2)
        val mint = requests.last { it["action"] == "create_link" }
        assertEquals("season cmd", "auto /media/series/900/s1", mint["cmd"])
        assertEquals("episode rides as series=n", "2", mint["series"])
        assertTrue("never walked the vod tree on an XC portal", requests.none { it["type"] == "vod" && it.containsKey("season_id") })
    }
}
