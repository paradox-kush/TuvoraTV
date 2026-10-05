package com.nuvio.tv.core.iptv

import android.net.Uri
import com.nuvio.tv.core.iptv.content.ContentChannel
import com.nuvio.tv.core.iptv.content.ContentEpisode
import com.nuvio.tv.core.iptv.content.ContentSeries
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

    @Test
    fun `the first login-free build keeps where every pre-B64 id moves`() = runTest {
        val acc = account()
        // A pre-B64 catalog as TV used to build it: ordinal ids, e<seq> episodes, raw-name categories.
        db.ingest(acc.id) { w ->
            w.addChannel(ContentChannel(1, "BBC One", null, "bbc.uk", "UK", "http://h:8080/live/alice/OLD/1.ts"))
            w.addChannel(ContentChannel(2, "ITV", null, null, "UK", "http://h:8080/live/alice/OLD/2.ts"))
            val tour = ContentSeries(1, "The Grand Tour", null, "SERIES")
            w.addEpisode(tour, ContentEpisode(1, "e0", 1, 2, "The Grand Tour S01E02", null, "http://h:8080/series/alice/OLD/11.mkv", "mkv"))
        }
        assertEquals("pre-B64 catalog", 1, db.idScheme(acc.id))
        val src = File(app.cacheDir, "ids-legacy.m3u").apply { writeText(ORIGINAL) }
        fileStore.importFrom(acc.id, Uri.fromFile(src))

        client.ensureIngested(acc)   // not forced: the old scheme alone makes it rebuild

        assertEquals("rebuilt under the new ids", M3UClient.M3U_ID_SCHEME, db.idScheme(acc.id))
        val moves = db.legacyIds(acc.id, listOf("live:1", "live:2", "episode:e0", "series:1", "cat:live:UK", "live:404"))
        assertEquals("live:${M3uIdentity.sidOf("http://h:8080/live/1.ts")}", moves["live:1"]?.newId)
        assertEquals("live:${M3uIdentity.sidOf("http://h:8080/live/2.ts")}", moves["live:2"]?.newId)
        assertEquals("episode:${M3uIdentity.sidOf("http://h:8080/series/11.mkv").toString(16)}", moves["episode:e0"]?.newId)
        assertEquals("series:${M3uIdentity.sidOf("series:the grand tour")}", moves["series:1"]?.newId)
        assertEquals("cat:live:${M3uIdentity.sidOf("UK")}", moves["cat:live:UK"]?.newId)
        assertEquals("unknown ids have no move", null, moves["live:404"])
        // And the served catalog is the new one.
        assertEquals(M3uIdentity.sidOf("http://h:8080/live/1.ts"), snapshot(acc).channels["BBC One"])
    }

    /**
     * Device pass 2026-10-05 (emulator): the login-free ids are hashes, and the catalog tables are keyed
     * (and so read back) by sid — the guide numbered and listed an M3U playlist in hash order instead of
     * the file's. Lists keep the file's order (channel numbers included), whatever the ids.
     */
    @Test
    fun `lists keep the file's order although ids are hashes`() = runTest {
        val acc = account()
        val names = listOf("UK: BBC One HD", "UK | BBC Two FHD", "UK: ITV1", "|EN| CNN International", "US: ESPN HD", "DE: Das Erste HD", "FR: TF1 4K", "Random Local")
        val movies = listOf("The Matrix (1999)", "Big Buck Test", "Alien Romulus (2024)", "Zulu (1964)")
        val text = buildString {
            appendLine("#EXTM3U")
            names.forEachIndexed { i, n -> appendLine("#EXTINF:-1 group-title=\"G\",$n"); appendLine("http://h:8080/live/alice/P/${i + 1}.ts") }
            movies.forEachIndexed { i, n -> appendLine("#EXTINF:-1 group-title=\"M\",$n"); appendLine("http://h:8080/movie/alice/P/${900 + i}.mp4") }
        }
        ingest(acc, text)
        assertEquals("live in file order", names, db.channelsFor(acc.id, null).map { it.name })
        assertEquals("live in file order within a category", names, db.channelsFor(acc.id, M3uIngestMapping.categoryId("G")).map { it.name })
        assertEquals("movies in file order", movies, db.vodFor(acc.id, null).map { it.name })
    }
}
