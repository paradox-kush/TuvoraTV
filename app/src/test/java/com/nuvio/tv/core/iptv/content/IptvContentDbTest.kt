package com.nuvio.tv.core.iptv.content

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Round-trips the M3U content DB: ingest a small sample into real (Robolectric) SQLite, then query
 * it back through every read path. Mirrors XtreamMatchIndex's test approach.
 *
 * @Config: Robolectric supplies a real framework SQLiteOpenHelper; application = plain Application
 * (the @HiltAndroidApp app can't boot here); sdk 35 (SDK 36 sandbox needs Java 21, repo is 17);
 * ConscryptMode OFF (avoid the conscrypt classpath race — no TLS here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class IptvContentDbTest {

    private val db = IptvContentDb(RuntimeEnvironment.getApplication())
    private val pid = "m3u:http://host/get.php"

    private suspend fun ingestSample() {
        db.ingest(pid) { w ->
            // live: two channels across two categories
            w.addChannel(ContentChannel(1, "BBC One HD", "http://img/bbc.png", "bbc.uk", "UK NEWS", "http://h/live/u/p/1.ts"))
            w.addChannel(ContentChannel(2, "CNN", null, null, "US NEWS", "http://h/live/u/p/2.ts"))
            // vod: two movies, one category
            w.addVod(ContentVod(3, "Alien Romulus (2024)", "http://img/a.jpg", "MOVIES", "http://h/movie/u/p/3.mp4", "mp4"))
            w.addVod(ContentVod(4, "The Accountant (2025)", null, "MOVIES", "http://h/movie/u/p/4.mkv", "mkv"))
            // series: two episodes of one show grouped under a header (created on first sight)
            w.addEpisode("SERIES", "The Grand Tour", 1, 1, "The Grand Tour S01E01", null, "http://h/series/u/p/10.mp4", "mp4")
            w.addEpisode("SERIES", "The Grand Tour", 1, 2, "The Grand Tour S01E02", null, "http://h/series/u/p/11.mp4", "mp4")
        }
    }

    @Test
    fun `ingest then query back channels vod series and categories`() = runTest {
        ingestSample()

        assertNotNull("meta row written last -> built", db.builtAt(pid))

        val channels = db.channelsFor(pid, null)
        assertEquals(2, channels.size)
        val bbc = channels.first { it.sid == 1 }
        assertEquals("BBC One HD", bbc.name)
        assertEquals("http://h/live/u/p/1.ts", bbc.url)
        assertEquals("bbc.uk", bbc.tvgId)

        // category filter narrows the list
        assertEquals(listOf("BBC One HD"), db.channelsFor(pid, "UK NEWS").map { it.name })

        val vod = db.vodFor(pid, "MOVIES")
        assertEquals(2, vod.size)
        assertEquals("mkv", vod.first { it.sid == 4 }.ext)

        // series header grouped from its two episodes
        val series = db.seriesFor(pid, null)
        assertEquals(1, series.size)
        assertEquals("The Grand Tour", series[0].name)

        val episodes = db.episodesFor(pid, series[0].sid)
        assertEquals(2, episodes.size)
        assertEquals(listOf(1, 2), episodes.map { it.episodeNum })   // ordered by season, episode
        assertEquals("http://h/series/u/p/10.mp4", episodes[0].url)

        // categories per type
        assertEquals(setOf("UK NEWS", "US NEWS"), db.categoriesFor(pid, IptvContentDb.TYPE_LIVE).map { it.id }.toSet())
        assertEquals(listOf("MOVIES"), db.categoriesFor(pid, IptvContentDb.TYPE_VOD).map { it.id })
        assertEquals(listOf("SERIES"), db.categoriesFor(pid, IptvContentDb.TYPE_SERIES).map { it.id })
    }

    @Test
    fun `search matches by substring within each type`() = runTest {
        ingestSample()
        assertEquals(listOf("CNN"), db.searchChannels(pid, "cn", 10).map { it.name })
        assertEquals(listOf("Alien Romulus (2024)"), db.searchVod(pid, "alien", 10).map { it.name })
        assertEquals(listOf("The Grand Tour"), db.searchSeries(pid, "grand", 10).map { it.name })
    }

    @Test
    fun `channel and vod url lookups back the deep-link rebuild path`() = runTest {
        ingestSample()
        assertEquals("http://h/live/u/p/2.ts", db.channelUrl(pid, 2))
        assertEquals("http://h/movie/u/p/3.mp4", db.vodUrl(pid, 3))
        assertNull(db.channelUrl(pid, 999))
    }

    @Test
    fun `re-ingest replaces the previous catalog`() = runTest {
        ingestSample()
        assertEquals(2, db.channelsFor(pid, null).size)
        // second ingest with a single channel: old rows are cleared first
        db.ingest(pid) { w -> w.addChannel(ContentChannel(1, "Only One", null, null, "G", "http://h/live/u/p/1.ts")) }
        assertEquals(1, db.channelsFor(pid, null).size)
        assertEquals("Only One", db.channelsFor(pid, null)[0].name)
        assertTrue(db.vodFor(pid, null).isEmpty())
    }

    @Test
    fun `playlists are isolated by id`() = runTest {
        ingestSample()
        val other = "m3u:http://other/get.php"
        db.ingest(other) { w -> w.addChannel(ContentChannel(1, "Other Channel", null, null, "X", "http://o/live/1.ts")) }
        // each playlist sees only its own rows
        assertEquals(2, db.channelsFor(pid, null).size)
        assertEquals(1, db.channelsFor(other, null).size)
        assertEquals("Other Channel", db.channelsFor(other, null)[0].name)
    }
}
