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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * B02: a STRICT stock-Ministra portal. create_link answers only `/media/<id>.mpg` and
 * `/media/file_<id>.mpg`; a custom URL as cmd gets `nothing_to_play` (vod.class.php:36-39).
 */
class StalkerStrictMinistraVodTest {

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile private var movieRow = ""
    @Volatile private var fileRows = "[]"
    @Volatile private var relativeReply = false

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

    private fun page(rows: String) = """{"js":{"total_items":1,"max_page_items":14,"data":$rows}}"""

    private fun respond(q: Map<String, String>): String {
        val action = q["action"]
        if (action == "handshake") return """{"js":{"token":"T"}}"""
        if (action == "get_profile") return """{"js":{"id":"1","status":0}}"""
        if (action == "get_modules") return """{"js":{"all_modules":[],"disabled_modules":[]}}"""
        if (action == "create_link") {
            val cmd = q["cmd"].orEmpty()
            return if (Regex("^/media/(file_)?\\d+\\.mpg$").matches(cmd)) {
                if (relativeReply) """{"js":{"cmd":"$cmd?token=abc"}}"""
                else """{"js":{"cmd":"ffmpeg http://media.test/play$cmd"}}"""
            } else """{"js":{"cmd":"","error":"nothing_to_play"}}"""
        }
        if (q["type"] == "series") return """{"js":null}"""
        if (action == "get_categories") return """{"js":[{"id":"*","title":"All"},{"id":"1","title":"Drama"}]}"""
        if (q["type"] == "vod") {
            val movieId = q["movie_id"].orEmpty()
            val seasonId = q["season_id"].orEmpty()
            val episodeId = q["episode_id"].orEmpty()
            return when {
                episodeId.isNotEmpty() && episodeId != "0" -> page(fileRows)
                seasonId.isNotEmpty() && seasonId != "0" ->
                    page("""[{"id":"$episodeIdOf","season_id":"$seasonId","series_number":"1","name":"Ep 1","is_episode":true}]""")
                movieId == "70" -> page("""[{"id":"700","season_number":"1","name":"Season 1","is_season":true}]""")
                movieId.isNotEmpty() -> page(fileRows)
                else -> page("[$movieRow]")
            }
        }
        return """{"js":[]}"""
    }

    private val episodeIdOf = 7001

    private val acc by lazy {
        XtreamAccount(
            id = "stalker|strict", name = "Strict", baseUrl = "", username = "", password = "",
            sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = server.url("/stalker_portal/c/").toString(), macAddress = "00:1A:79:00:00:01",
        )
    }

    private fun client() = StalkerClient(
        StalkerSessionManager(OkHttpClient(), PlaylistDns()),
        mockk<IptvContentDb>(relaxed = true),
        PlaylistServerFailover.detached(),
    )

    private fun mints() = requests.filter { it["action"] == "create_link" }.map { it["cmd"] }

    @Test
    fun `A - a movie with a custom rtsp_url mints the movie id cmd`() = runBlocking {
        movieRow = """{"id":"10","name":"M","cmd":"http://cdn.test/a.mkv","rtsp_url":"http://cdn.test/a.mkv","has_files":0,"category_id":"1","is_series":"0"}"""
        val c = client()
        c.vodMovies(acc, "1").getOrThrow()
        val url = c.resolveStreamUrl(acc, "movie", 10, false)
        assertEquals("cmds sent", listOf<String?>("/media/10.mpg"), mints())
        assertEquals("link", "http://media.test/play/media/10.mpg", url)
    }

    @Test
    fun `B - a multi-file movie resolves the file id first`() = runBlocking {
        movieRow = """{"id":"10","name":"M","cmd":"http://cdn.test/a.mkv","has_files":1,"category_id":"1","is_series":"0"}"""
        fileRows = """[{"id":"100","is_file":true,"protocol":"custom","cmd":"http://cdn.test/f.mkv"}]"""
        val c = client()
        c.vodMovies(acc, "1").getOrThrow()
        val url = c.resolveStreamUrl(acc, "movie", 10, false)
        assertEquals("the only create_link cmd", listOf<String?>("/media/file_100.mpg"), mints())
        assertNotNull("link", url)
    }

    @Test
    fun `F - a multi-file movie with a movie-form cmd still goes to the file row first`() = runBlocking {
        movieRow = """{"id":"10","name":"M","cmd":"/media/10.mpg","has_files":1,"category_id":"1","is_series":"0"}"""
        fileRows = """[{"id":"100","is_file":true,"cmd":"/media/file_100.mpg"}]"""
        val c = client()
        c.vodMovies(acc, "1").getOrThrow()
        assertNotNull("link", c.resolveStreamUrl(acc, "movie", 10, false))
        assertEquals("file id first, no movie-form mint", listOf<String?>("/media/file_100.mpg"), mints())
    }

    @Test
    fun `C - a Ministra episode with a custom url file row mints the file id cmd`() = runBlocking {
        movieRow = """{"id":"70","name":"S","cmd":"/media/70.mpg","category_id":"1","is_series":"1"}"""
        fileRows = """[{"id":"9001","is_file":true,"protocol":"custom","cmd":"http://cdn.test/e.mkv"}]"""
        val c = client()
        c.seriesCategories(acc).getOrThrow()
        val url = c.resolveEpisodeUrl(acc, seriesId = 70, season = 1, episodeNum = 1)
        assertEquals("cmds sent", listOf<String?>("/media/file_9001.mpg"), mints())
        assertNotNull("link", url)
    }

    @Test
    fun `D - a relative reply resolves against the portal`() = runBlocking {
        relativeReply = true
        movieRow = """{"id":"10","name":"M","cmd":"/media/10.mpg","category_id":"1","is_series":"0"}"""
        val c = client()
        c.vodMovies(acc, "1").getOrThrow()
        val url = c.resolveStreamUrl(acc, "movie", 10, false)
        assertEquals("link", server.url("/media/10.mpg?token=abc").toString(), url)
    }

    @Test
    fun `E - a refusal surfaces its reason instead of a silent null`() = runBlocking {
        movieRow = """{"id":"11","name":"M","cmd":"opaque-cmd","category_id":"1","is_series":"0"}"""
        val c = client()
        c.vodMovies(acc, "1").getOrThrow()
        assertNull("no link", c.resolveStreamUrl(acc, "movie", 11, false))
        assertNotNull("reason", c.lastResolveError)
    }
}
