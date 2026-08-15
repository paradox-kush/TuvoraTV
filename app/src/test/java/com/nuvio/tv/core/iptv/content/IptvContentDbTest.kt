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

    // --- EPG (XMLTV) --------------------------------------------------------

    @Test
    fun `ingest captures the url-tvg header and channel tvg-ids`() = runTest {
        db.ingest(pid) { w ->
            w.setTvgUrl("http://epg.example/xmltv.xml.gz")
            w.addChannel(ContentChannel(1, "BBC", null, "BBC.uk", "UK", "http://h/live/1.ts"))
            w.addChannel(ContentChannel(2, "No EPG id", null, null, "UK", "http://h/live/2.ts"))
            w.addChannel(ContentChannel(3, "CNN", null, "cnn.us ", "US", "http://h/live/3.ts"))
        }
        assertEquals("http://epg.example/xmltv.xml.gz", db.tvgUrl(pid))
        // tvg-ids are normalized (trim + lowercase); the null one is excluded.
        assertEquals(setOf("bbc.uk", "cnn.us"), db.channelTvgIds(pid))
        // fresh ingest leaves EPG unbuilt (so it refetches)
        assertNull(db.epgBuiltAt(pid))
    }

    @Test
    fun `replaceEpg stores programmes and stamps epg_built_at last`() = runTest {
        ingestSample()
        db.replaceEpg(pid, builtAtMs = 5_000L) { w ->
            w.add(EpgProgramme("bbc.uk", 1_000L, 2_000L, "Show A", "desc"))
            w.add(EpgProgramme("bbc.uk", 2_000L, 3_000L, "Show B", null))
        }
        assertEquals(5_000L, db.epgBuiltAt(pid))
    }

    @Test
    fun `epgNowNext returns the current programme plus the next`() = runTest {
        ingestSample()
        db.replaceEpg(pid, builtAtMs = 0L) { w ->
            w.add(EpgProgramme("bbc.uk", 1_000L, 2_000L, "Past", null))       // already ended
            w.add(EpgProgramme("bbc.uk", 2_000L, 3_000L, "Now", "current"))   // spans nowMs=2500
            w.add(EpgProgramme("bbc.uk", 3_000L, 4_000L, "Next", null))
            w.add(EpgProgramme("bbc.uk", 4_000L, 5_000L, "Later", null))
        }
        val nowNext = db.epgNowNext(pid, "bbc.uk", nowMs = 2_500L)
        assertEquals(listOf("Now", "Next"), nowNext.map { it.title })
        assertEquals("current", nowNext[0].desc)
    }

    @Test
    fun `epgNowNext during a gap returns the upcoming programme first`() = runTest {
        ingestSample()
        db.replaceEpg(pid, builtAtMs = 0L) { w ->
            w.add(EpgProgramme("bbc.uk", 1_000L, 2_000L, "Earlier", null))
            w.add(EpgProgramme("bbc.uk", 5_000L, 6_000L, "Upcoming", null))
        }
        // nowMs=3000 falls in a schedule gap -> the next upcoming programme leads.
        val nowNext = db.epgNowNext(pid, "bbc.uk", nowMs = 3_000L)
        assertEquals(listOf("Upcoming"), nowNext.map { it.title })
    }

    @Test
    fun `epgNowNext empty for a channel with no EPG`() = runTest {
        ingestSample()
        assertTrue(db.epgNowNext(pid, "unknown.channel", nowMs = 1_000L).isEmpty())
    }

    @Test
    fun `catalog re-ingest keeps old programmes but marks EPG stale`() = runTest {
        ingestSample()
        db.replaceEpg(pid, builtAtMs = 9_000L) { w -> w.add(EpgProgramme("bbc.uk", 1L, 2L, "P", null)) }
        assertEquals(9_000L, db.epgBuiltAt(pid))
        // A fresh catalog ingest resets epg_built_at (finish writes NULL) so the EPG refetches,
        // but the programmes rows survive until that refetch replaces them (no now/next gap).
        db.ingest(pid) { w -> w.addChannel(ContentChannel(1, "BBC", null, "bbc.uk", "UK", "http://h/live/1.ts")) }
        assertNull(db.epgBuiltAt(pid))
        assertEquals(listOf("P"), db.epgNowNext(pid, "bbc.uk", nowMs = 1L).map { it.title })
    }

    // --- WP1: windowed reads, per-channel refill, prune, has_archive, channel flags ---

    @Test
    fun `epg window truncates descriptions and the full-desc getter returns the whole text`() = runTest {
        val longDesc = "x".repeat(2_000)
        db.refillChannelEpg(pid, "bbc.uk", listOf(EpgProgramme("bbc.uk", 1_000L, 2_000L, "Show", longDesc)), fetchedAtMs = 50L)

        val window = db.epgWindow(pid, "bbc.uk", fromMs = 0L, toMs = 10_000L)
        assertEquals(1, window.size)
        // The projection truncates in SQLite — the heap never sees more than 600 chars.
        assertEquals(600, window[0].desc?.length)
        // The single-programme getter still has the whole text.
        assertEquals(2_000, db.epgFullDesc(pid, "bbc.uk", 1_000L)?.length)
    }

    @Test
    fun `epg window returns only programmes overlapping the range in start order`() = runTest {
        db.refillChannelEpg(
            pid, "cnn.us",
            listOf(
                EpgProgramme("cnn.us", 1_000L, 2_000L, "Before", null),
                EpgProgramme("cnn.us", 2_000L, 3_000L, "SpansFrom", null),
                EpgProgramme("cnn.us", 3_000L, 4_000L, "Inside", null),
                EpgProgramme("cnn.us", 4_000L, 5_000L, "SpansTo", null),
                EpgProgramme("cnn.us", 5_000L, 6_000L, "After", null),
            ),
            fetchedAtMs = 50L,
        )
        val titles = db.epgWindow(pid, "cnn.us", fromMs = 2_500L, toMs = 4_500L).map { it.title }
        assertEquals(listOf("SpansFrom", "Inside", "SpansTo"), titles)
    }

    @Test
    fun `channel refill replaces only that channel and stamps its fetch time`() = runTest {
        db.replaceEpg(pid, builtAtMs = 0L) { w ->
            w.add(EpgProgramme("bbc.uk", 1_000L, 2_000L, "Old A", null))
            w.add(EpgProgramme("cnn.us", 1_000L, 2_000L, "Other channel", null))
        }

        db.refillChannelEpg(pid, "bbc.uk", listOf(EpgProgramme("bbc.uk", 2_000L, 3_000L, "New A", null)), fetchedAtMs = 777L)

        // The refilled channel shows ONLY the new batch; the sibling is untouched.
        assertEquals(listOf("New A"), db.epgWindow(pid, "bbc.uk", 0L, 10_000L).map { it.title })
        assertEquals(listOf("Other channel"), db.epgWindow(pid, "cnn.us", 0L, 10_000L).map { it.title })
        // The lazy-fetch gate: stamped for the refilled channel, absent for the other.
        assertEquals(777L, db.epgChannelFetchedAt(pid, "bbc.uk"))
        assertNull(db.epgChannelFetchedAt(pid, "cnn.us"))
    }

    @Test
    fun `an empty refill clears the channel and still stamps the gate`() = runTest {
        db.refillChannelEpg(pid, "bbc.uk", listOf(EpgProgramme("bbc.uk", 1_000L, 2_000L, "Stale", null)), fetchedAtMs = 1L)
        db.refillChannelEpg(pid, "bbc.uk", emptyList(), fetchedAtMs = 900L)
        assertTrue(db.epgWindow(pid, "bbc.uk", 0L, 10_000L).isEmpty())
        // A provider with no guide for the channel is remembered — the gate stops re-asking.
        assertEquals(900L, db.epgChannelFetchedAt(pid, "bbc.uk"))
    }

    @Test
    fun `a wholesale epg replace supersedes the per-channel fetch stamps`() = runTest {
        db.refillChannelEpg(pid, "bbc.uk", emptyList(), fetchedAtMs = 5L)
        assertEquals(5L, db.epgChannelFetchedAt(pid, "bbc.uk"))
        db.replaceEpg(pid, builtAtMs = 10L) { w -> w.add(EpgProgramme("bbc.uk", 1L, 2L, "P", null)) }
        assertNull(db.epgChannelFetchedAt(pid, "bbc.uk"))
    }

    @Test
    fun `prune drops programmes that ended before the cutoff`() = runTest {
        db.refillChannelEpg(
            pid, "bbc.uk",
            listOf(
                EpgProgramme("bbc.uk", 0L, 1_000L, "Long gone", null),
                EpgProgramme("bbc.uk", 1_000L, 2_000L, "Just ended", null),
                EpgProgramme("bbc.uk", 2_000L, 3_000L, "Still relevant", null),
            ),
            fetchedAtMs = 1L,
        )
        db.pruneEpg(pid, cutoffMs = 2_500L)
        assertEquals(listOf("Still relevant"), db.epgWindow(pid, "bbc.uk", 0L, 10_000L).map { it.title })
    }

    @Test
    fun `has archive round-trips through the writer and every epg read`() = runTest {
        db.replaceEpg(pid, builtAtMs = 0L) { w ->
            w.add(EpgProgramme("bbc.uk", 1_000L, 2_000L, "Replayable", null, hasArchive = true))
            w.add(EpgProgramme("bbc.uk", 2_000L, 3_000L, "Live only", null))
        }
        assertEquals(listOf(true, false), db.epgWindow(pid, "bbc.uk", 0L, 10_000L).map { it.hasArchive })
        assertEquals(listOf(true, false), db.epgNowNext(pid, "bbc.uk", nowMs = 1_500L).map { it.hasArchive })
    }

    @Test
    fun `xtream channel flags round-trip through ingest and lineup paths`() = runTest {
        db.ingest(pid) { w ->
            w.addChannel(ContentChannel(1, "Flagged", null, null, "g", "http://h/1.ts", useHttpTmpLink = true, useLoadBalancing = true))
            w.addChannel(ContentChannel(2, "Plain", null, null, "g", "http://h/2.ts"))
        }
        val bySid = db.channelRow(pid, 1)
        assertEquals(true, bySid?.useHttpTmpLink)
        assertEquals(true, bySid?.useLoadBalancing)
        assertEquals(false, db.channelRow(pid, 2)?.useHttpTmpLink)

        val paged = db.pageChannels(pid, categoryId = null, offset = 0, limit = 10).associateBy { it.sid }
        assertEquals(true, paged[1]?.useHttpTmpLink)
        assertEquals(false, paged[2]?.useLoadBalancing)

        // The Stalker lineup path persists them too.
        val other = "m3u:http://flags/lineup"
        db.replaceLiveLineup(other, listOf(ContentChannel(9, "LB", null, null, "g", "http://h/9.ts", useLoadBalancing = true)), categories = listOf("g" to "Group"))
        assertEquals(true, db.channelsFor(other, null).single().useLoadBalancing)
    }
}
