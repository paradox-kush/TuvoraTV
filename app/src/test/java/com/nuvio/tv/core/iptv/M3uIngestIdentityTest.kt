package com.nuvio.tv.core.iptv

import android.net.Uri
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.iptv.epg.XmltvClient
import com.nuvio.tv.core.iptv.identity.M3uIdentity
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * B64 — the ids an M3U catalog is stored under on TV. Regressions: channel / movie / series ids were the
 * entry's ORDINAL in the file (a provider reorder re-pointed favourites and Continue Watching at other
 * items), episode ids were `e<sequence>`, category ids the raw group name — none matched the phone, so
 * nothing carried across devices. Ids are now Mobile's scheme over the login-free stream URL
 * ([M3uIdentity]), series grouping is the shared [M3uSeriesGrouping], category ids are Mobile's hash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class M3uIngestIdentityTest {

    private val app = RuntimeEnvironment.getApplication()
    private val db = IptvContentDb(app)
    private val fileStore = M3UFileStore(app)
    private val dns = com.nuvio.tv.core.iptv.dns.PlaylistDns()
    private val matchIndex = com.nuvio.tv.core.iptv.match.XtreamMatchIndex(app)
    private val xmltv = XmltvClient(db, OkHttpClient(), dns, matchIndex, PlaylistServerFailover.detached())
    private val client = M3UClient(db, OkHttpClient(), fileStore, xmltv, dns, PlaylistServerFailover.detached())

    private val ORIGINAL = """
        #EXTM3U
        #EXTINF:-1 tvg-id="bbc.uk" group-title="UK",BBC One
        http://h:8080/live/alice/OLD/1.ts
        #EXTINF:-1 group-title="UK",ITV
        http://h:8080/live/alice/OLD/2.ts
        #EXTINF:-1 group-title="MOVIES",Alien Romulus (2024)
        http://h:8080/movie/alice/OLD/385215.mp4
        #EXTINF:-1 group-title="SERIES",The Grand Tour S01E02
        http://h:8080/series/alice/OLD/11.mkv
        #EXTINF:-1 group-title="SERIES",Breaking Bad S01E03
        http://h:8080/movie/alice/OLD/12.mp4
    """.trimIndent()

    /** Same catalog, provider-reordered, after a password change. */
    private val REORDERED_NEW_PASSWORD = """
        #EXTM3U
        #EXTINF:-1 group-title="SERIES",Breaking Bad S01E03
        http://h:8080/movie/alice/NEW/12.mp4
        #EXTINF:-1 group-title="MOVIES",Alien Romulus (2024)
        http://h:8080/movie/alice/NEW/385215.mp4
        #EXTINF:-1 group-title="UK",ITV
        http://h:8080/live/alice/NEW/2.ts
        #EXTINF:-1 group-title="SERIES",The Grand Tour S01E02
        http://h:8080/series/alice/NEW/11.mkv
        #EXTINF:-1 tvg-id="bbc.uk" group-title="UK",BBC One
        http://h:8080/live/alice/NEW/1.ts
    """.trimIndent()

    private fun account() = m3uAccountFromFile(newM3UFilePlaylistId("ids.m3u"), fileName = "ids.m3u", name = "Ids")

    private suspend fun ingest(acc: XtreamAccount, text: String) {
        val src = File(app.cacheDir, "ids-${System.nanoTime()}.m3u").apply { writeText(text) }
        fileStore.importFrom(acc.id, Uri.fromFile(src))
        src.delete()
        client.ensureIngested(acc, force = true)
    }

    private data class Snapshot(val channels: Map<String, Int>, val vod: Map<String, Int>, val series: Map<String, Int>, val episodes: Map<String, String>, val categories: Map<String, String>)

    private suspend fun snapshot(acc: XtreamAccount): Snapshot {
        val series = db.seriesFor(acc.id, null)
        return Snapshot(
            channels = db.channelsFor(acc.id, null).associate { it.name to it.sid },
            vod = db.vodFor(acc.id, null).associate { it.name to it.sid },
            series = series.associate { it.name to it.sid },
            episodes = series.flatMap { s -> db.episodesFor(acc.id, s.sid) }.associate { it.title to it.episodeSid },
            categories = (db.categoriesFor(acc.id, IptvContentDb.TYPE_LIVE) + db.categoriesFor(acc.id, IptvContentDb.TYPE_VOD) +
                db.categoriesFor(acc.id, IptvContentDb.TYPE_SERIES)).associate { it.name to it.id },
        )
    }

    @Test
    fun `a provider reorder and a password change keep every id`() = runTest {
        val acc = account()
        ingest(acc, ORIGINAL)
        val before = snapshot(acc)
        ingest(acc, REORDERED_NEW_PASSWORD)
        assertEquals("ids survive reorder + password change", before, snapshot(acc))
    }

    @Test
    fun `ids are the phone's ids for the same lines`() = runTest {
        val acc = account()
        ingest(acc, ORIGINAL)
        val s = snapshot(acc)
        assertEquals(M3uIdentity.sidOf("http://h:8080/live/1.ts"), s.channels["BBC One"])
        assertEquals(M3uIdentity.sidOf("http://h:8080/movie/385215.mp4"), s.vod["Alien Romulus (2024)"])
        assertEquals(M3uIdentity.sidOf("series:the grand tour"), s.series["The Grand Tour"])
        assertEquals(M3uIdentity.sidOf("http://h:8080/series/11.mkv").toString(16), s.episodes["The Grand Tour S01E02"])
        assertEquals(M3uIdentity.sidOf("UK").toString(), s.categories["UK"])
    }

    @Test
    fun `a movie row named like an episode joins the shared series`() = runTest {
        val acc = account()
        ingest(acc, ORIGINAL)
        val s = snapshot(acc)
        assertEquals(M3uIdentity.sidOf("series:breaking bad"), s.series["Breaking Bad"])
        assertEquals(M3uIdentity.sidOf("http://h:8080/movie/12.mp4").toString(16), s.episodes["Breaking Bad S01E03"])
    }
}
