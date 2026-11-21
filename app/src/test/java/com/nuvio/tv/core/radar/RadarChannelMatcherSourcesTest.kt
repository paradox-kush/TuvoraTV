package com.nuvio.tv.core.radar

import com.nuvio.tv.core.iptv.IptvClient
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.CategorySelections
import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.content.EpgProgramme
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.identity.IptvIdentity
import com.nuvio.tv.core.iptv.overlay.ChannelOverlay
import com.nuvio.tv.core.iptv.overlay.CategoryOverlay
import com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository
import com.nuvio.tv.core.iptv.overlay.OverlaySnapshot
import com.nuvio.tv.data.local.XtreamAccountStore
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Sports Centre matches every playlist type, not just Xtream panels: assembly routes through
 * [IptvClientFactory], so a Stalker portal's lineup is a candidate like any other. Stalker lists
 * channels with a BLANK url because the portal mints a single-use link only inside playback.
 */
class RadarChannelMatcherSourcesTest {

    @Test
    fun `hidden channels never reach partial matches or provider probes for any source`() = runTest {
        for (account in listOf(xtreamAccount(), m3uAccount(), stalkerAccount())) {
            val hidden = channel(1, "Spain vs Austria", "")
            val client = clientWith(hidden)
            val overlay = OverlaySnapshot(channels = mapOf(
                IptvIdentity.entityId(account.id, hidden.name, hidden.epgChannelId) to ChannelOverlay(hidden = true),
            ))
            val matcher = matcher(account to client, overlay = { overlay })
            val partials = mutableListOf<RadarChannelMatcher.ChannelMatch>()
            assertTrue(matcher.match(FIXTURE, null) { partials += it }.isEmpty())
            assertTrue(partials.isEmpty())
            coVerify(exactly = 0) { client.shortEpg(any(), any(), any()) }
        }
    }

    @Test
    fun `warming the lineup moves the one-time provider load out of the first match`() = runTest {
        val account = xtreamAccount()
        val client = clientWith(channel(1, "Spain vs Austria", XTREAM_URL))
        val matcher = matcher(account to client)
        matcher.warm()
        coVerify(exactly = 1) { client.liveChannels(any(), any()) }
        assertEquals(1, matcher.match(FIXTURE, null).size)
        coVerify(exactly = 1) { client.liveChannels(any(), any()) }
    }

    @Test
    fun `a lookup that gives up does not restart the provider load - the next attempt reuses it`() = runTest {
        val account = xtreamAccount()
        val client = clientWith(channel(1, "Spain vs Austria", XTREAM_URL))
        coEvery { client.liveChannels(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(400) // real time: runs in the matcher's own IO scope
            Result.success(listOf(channel(1, "Spain vs Austria", XTREAM_URL)))
        }
        val matcher = matcher(account to client)
        // The caller's bounded lookup gives up long before the slow panel answers (virtual timeout).
        assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(50) { matcher.match(FIXTURE, null) })
        // The second attempt waits on the SAME load and gets the result.
        assertEquals(1, matcher.match(FIXTURE, null).size)
        coVerify(exactly = 1) { client.liveChannels(any(), any()) }
    }

    @Test
    fun `a hung panel download ends as a surfaced failure and leaves nothing running`() = runTest {
        val account = xtreamAccount()
        val client = clientWith(channel(1, "Spain vs Austria", XTREAM_URL))
        coEvery { client.liveChannels(any(), any()) } coAnswers { kotlinx.coroutines.delay(5_000); Result.success(emptyList()) }
        val matcher = matcher(account to client).also { it.lineupDeadlineMs = 150 }
        val error = runCatching { matcher.match(FIXTURE, null) }.exceptionOrNull()
        assertTrue("surfaced: $error", error is IllegalStateException)
        assertEquals("no leaked job", 0, matcher.inFlightLineupLoads())
    }

    @Test
    fun `a profile switch cancels an in-flight download`() = runTest {
        val account = xtreamAccount()
        val client = clientWith(channel(1, "Spain vs Austria", XTREAM_URL))
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { client.liveChannels(any(), any()) } coAnswers { started.complete(Unit); kotlinx.coroutines.delay(5_000); Result.success(emptyList()) }
        val matcher = matcher(account to client)
        val caller = launch { matcher.match(FIXTURE, null) }
        withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(5_000) { started.await() } }
        caller.cancel() // the caller gives up mid-download
        assertEquals("download still running after the caller gave up", 1, matcher.inFlightLineupLoads())
        matcher.resetForProfile()
        assertEquals("cancelled by the reset", 0, matcher.inFlightLineupLoads())
    }

    @Test
    fun `one slow playlist does not block matching on the fast ones and joins the next lookup`() = runTest {
        val fast = xtreamAccount()
        val slow = xtreamAccount().copy(id = "slow", name = "Slow panel", baseUrl = "http://slow.example")
        val fastClient = clientWith(channel(1, "Spain vs Austria", XTREAM_URL))
        val slowClient = clientWith(channel(2, "Spain vs Austria HD", XTREAM_URL))
        coEvery { slowClient.liveChannels(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(600) // real time, in the matcher's own scope
            Result.success(listOf(channel(2, "Spain vs Austria HD", XTREAM_URL)))
        }
        val matcher = matcher(slow to slowClient, fast to fastClient).also { it.accountWaitMs = 150 }
        val first = matcher.match(FIXTURE, null)
        assertEquals("fast playlist answers at once", listOf("Spain vs Austria"), first.map { it.channel.name })
        assertEquals("slow download still running", 1, matcher.inFlightLineupLoads())
        withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(900) }
        assertEquals("slow playlist joined the next lookup", 2, matcher.match(FIXTURE, null).size)
    }

    @Test
    fun `a warm racing the first match still fetches the lineup once`() = runTest {
        val account = xtreamAccount()
        val client = clientWith(channel(1, "Spain vs Austria", XTREAM_URL))
        val matcher = matcher(account to client)
        coroutineScope { listOf(async { matcher.warm() }, async { matcher.match(FIXTURE, null) }, async { matcher.warm() }).awaitAll() }
        coVerify(exactly = 1) { client.liveChannels(any(), any()) }
    }

    @Test
    fun `hide and unhide reread the profile overlay without refetching the cached lineup`() = runTest {
        val account = xtreamAccount()
        val row = channel(1, "Spain vs Austria", XTREAM_URL)
        val client = clientWith(row)
        var overlay = OverlaySnapshot.EMPTY
        val matcher = matcher(account to client, overlay = { overlay })
        assertEquals(1, matcher.match(FIXTURE, null).size)
        overlay = OverlaySnapshot(channels = mapOf(IptvIdentity.entityId(account.id, row.name, row.epgChannelId) to ChannelOverlay(hidden = true)))
        assertTrue(matcher.match(FIXTURE, null).isEmpty())
        overlay = OverlaySnapshot.EMPTY
        assertEquals(1, matcher.match(FIXTURE, null).size)
        coVerify(exactly = 1) { client.liveChannels(any(), any()) }
        coVerify(exactly = 0) { client.liveCategories(any()) }
    }

    @Test
    fun `hidden group applies before broadcaster results and category names are cached`() = runTest {
        val account = xtreamAccount()
        val client = clientWith(channel(1, "Sky Sports", XTREAM_URL).copy(categoryId = "sports"))
        coEvery { client.liveCategories(any()) } returns Result.success(listOf(XtreamCategory("sports", "Sports")))
        val overlay = OverlaySnapshot(categories = mapOf(IptvIdentity.categoryKey(account.id, "live", "Sports") to CategoryOverlay(hidden = true)))
        val matcher = matcher(account to client, overlay = { overlay })
        repeat(2) {
            val partials = mutableListOf<RadarChannelMatcher.ChannelMatch>()
            assertTrue(matcher.match(FIXTURE, null, stations = listOf(RadarTvStation(channel = "Sky Sports", country = "United Kingdom"))) { partials += it }.isEmpty())
            assertTrue(partials.isEmpty())
        }
        coVerify(exactly = 1) { client.liveCategories(any()) }
        coVerify(exactly = 0) { client.shortEpg(any(), any(), any()) }
    }

    @Test
    fun `explicitly deselected live categories do not fetch the provider lineup`() = runTest {
        val client = clientWith(channel(1, "Spain vs Austria", XTREAM_URL))
        val matcher = matcher(xtreamAccount().copy(categorySelections = CategorySelections(live = emptyList())) to client)
        assertTrue(matcher.match(FIXTURE, null).isEmpty())
        coVerify(exactly = 0) { client.liveChannels(any(), any()) }
    }

    @Test
    fun `catalog loading and scored partials do not run on the caller thread`() = runTest {
        var catalogThread = ""
        var partialThread = ""
        val client = clientWith(channel(1, "Spain Austria Sports", XTREAM_URL))
        coEvery { client.liveChannels(any(), any()) } coAnswers {
            catalogThread = Thread.currentThread().name
            Result.success(listOf(channel(1, "Spain Austria Sports", XTREAM_URL)))
        }
        val matcher = matcher(xtreamAccount() to client)
        val caller = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sports-ui-test") }
            .asCoroutineDispatcher()
        try {
            val callerThread = withContext(caller) {
                val name = Thread.currentThread().name
                matcher.match(FIXTURE, league = null) {
                    partialThread = Thread.currentThread().name
                }
                name
            }

            assertTrue(catalogThread.isNotBlank())
            assertTrue(partialThread.isNotBlank())
            assertNotEquals(callerThread, catalogThread)
            assertNotEquals(callerThread, partialThread)
        } finally {
            caller.close()
        }
    }

    @Test
    fun `channels from xtream m3u and stalker playlists all match`() = runTest {
        val matcher = matcher(
            xtreamAccount() to clientWith(channel(1, "Spain Austria Sports", XTREAM_URL)),
            m3uAccount() to clientWith(channel(2, "Spain v Austria Stream", M3U_URL)),
            stalkerAccount() to clientWith(channel(3, "Austria Spain Live", "")),
        )

        val matched = matcher.match(FIXTURE, league = null)

        assertEquals(
            setOf(XTREAM_ID, M3U_ID, STALKER_ID),
            matched.map { it.channel.playlistId }.toSet(),
        )
    }

    @Test
    fun `a disabled playlist contributes nothing`() = runTest {
        val matcher = matcher(
            stalkerAccount().copy(enabled = false) to clientWith(channel(3, "Austria Spain Live", "")),
        )

        assertEquals(emptyList<RadarChannelMatcher.ChannelMatch>(), matcher.match(FIXTURE, league = null))
    }


    /**
     * The bug the provider-EPG tier exists for: a channel whose NAME says nothing about the
     * fixture used to be dropped before any guide was consulted, so a Liga MX match on a
     * Mexican channel was unfindable and the sheet fell through to generic "has the word
     * sport in it" hits. A guide entry naming the teams must be enough on its own.
     */
    @Test
    fun `a channel matches on the provider guide even when its name says nothing`() = runTest {
        val epg = mockk<IptvContentDb>()
        coEvery { epg.epgSearch(any(), any(), any(), any(), any()) } returns listOf(
            EpgProgramme(
                channelId = "canal5.mx",
                startMs = FIXTURE.startEpochMs!!,
                endMs = FIXTURE.startEpochMs!! + 2 * 60 * 60 * 1000L,
                title = "Futbol: Spain vs Austria",
                desc = null,
            ),
        )
        val matcher = matcher(
            xtreamAccount() to clientWith(
                channel(1, "Canal 5", XTREAM_URL).copy(epgChannelId = "canal5.mx"),
            ),
            contentDb = epg,
        )

        val match = matcher.match(FIXTURE, league = null).single()

        assertEquals("Canal 5", match.channel.name)
        assertEquals(RadarChannelMatcher.MatchVia.EPG, match.via)
    }

    /**
     * The guide hit is attributed to the channel whose guide id it names, and only that one.
     * Asserting the emptiness of the other channel alone would pass just as well if the whole
     * tier were dead — which is how the dead-string bug above survived.
     */
    @Test
    fun `a guide hit lands only on the channel whose guide id it names`() = runTest {
        val epg = mockk<IptvContentDb>()
        coEvery { epg.epgSearch(any(), any(), any(), any(), any()) } returns listOf(
            EpgProgramme(
                channelId = "canal5.mx",
                startMs = FIXTURE.startEpochMs!!,
                endMs = FIXTURE.startEpochMs!! + 2 * 60 * 60 * 1000L,
                title = "Futbol: Spain vs Austria",
                desc = null,
            ),
        )
        val matcher = matcher(
            xtreamAccount() to clientWith(
                channel(1, "Canal 5", XTREAM_URL).copy(epgChannelId = "canal5.mx"),
                channel(2, "Canal 7", XTREAM_URL).copy(epgChannelId = "canal7.mx"),
            ),
            contentDb = epg,
        )

        val match = matcher.match(FIXTURE, league = null).single()

        assertEquals("Canal 5", match.channel.name)
    }

    @Test
    fun shared_guide_id_retains_all_stream_variants() = runTest {
        val db = mockk<IptvContentDb>(relaxed = true)
        coEvery { db.epgSearch(any(), any(), any(), any(), any()) } returns listOf(
            EpgProgramme("shared", FIXTURE.startEpochMs!!, FIXTURE.startEpochMs!! + 2 * 60 * 60_000L, "Spain vs Austria", null))
        val matcher = matcher(xtreamAccount() to clientWith(
            channel(1,"Canal 5 HD",XTREAM_URL).copy(epgChannelId="shared"),
            channel(2,"Canal 5 SD",XTREAM_URL).copy(epgChannelId="shared"),
            channel(3,"Canal 7",XTREAM_URL).copy(epgChannelId="other")), contentDb=db)
        assertEquals(setOf(1,2),matcher.match(FIXTURE,null).map { it.channel.streamId }.toSet())
    }

    @Test
    fun committed_catalog_revision_reloads_renamed_event_feeds() = runTest {
        var revision=1L
        var channels=listOf(channel(1,"Spain vs Austria",XTREAM_URL))
        val client=clientWith(*channels.toTypedArray())
        coEvery { client.liveChannels(any(),any()) } coAnswers { Result.success(channels) }
        val matcher=matcher(xtreamAccount() to client,catalogRevision={revision})
        assertEquals("Spain vs Austria",matcher.match(FIXTURE,null).single().channel.name)
        revision=2L;channels=listOf(channel(1,"Austria v Spain Live",XTREAM_URL))
        assertEquals("Austria v Spain Live",matcher.match(FIXTURE,null).single().channel.name)
    }

    @Test
    fun fallback_is_bounded_coalesced_and_negative_cached() = runTest {
        val client=clientWith(*(1..12).map { channel(it,"Sky Sports $it",XTREAM_URL) }.toTypedArray())
        val matcher=matcher(xtreamAccount() to client)
        val results=coroutineScope { listOf(async { matcher.match(FIXTURE,null) },async { matcher.match(FIXTURE,null) }).awaitAll() }
        assertTrue(results.all { it.isEmpty() })
        assertTrue(matcher.match(FIXTURE,null).isEmpty())
        coVerify(exactly=6) { client.shortEpg(any(),any(),any()) }
    }

    /**
     * Onn (4-core, 2 GB), three playlists of 15k/26k/15k channels and ONE hidden channel: the game screen's lookup
     * spent 2.9-4.3 s PER PLAYLIST hashing every channel's identity to find the hidden one, so it never reached the
     * name pass inside its 6 s deadline and ended "Couldn't check your channels" on every open. A hidden channel is
     * decided only for the channels that matched; the lineup is never hashed.
     */
    @Test
    fun `one hidden channel does not make the lookup hash every channel of the lineup`() = runTest {
        val account = xtreamAccount()
        val hidden = channel(1, "Spain vs Austria", XTREAM_URL)
        val lineup = listOf(hidden, channel(2, "Spain vs Austria HD", XTREAM_URL)) +
            (3..5_000).map { channel(it, "US: Channel $it", XTREAM_URL) }
        val overlay = OverlaySnapshot(channels = mapOf(
            IptvIdentity.entityId(account.id, hidden.name, hidden.epgChannelId) to ChannelOverlay(hidden = true),
        ))
        val matcher = matcher(account to clientWith(*lineup.toTypedArray()), overlay = { overlay })
        var hashed = 0
        io.mockk.mockkObject(IptvIdentity)
        every { IptvIdentity.entityId(any(), any(), any()) } answers { hashed++; callOriginal() }
        try {
            val partials = mutableListOf<RadarChannelMatcher.ChannelMatch>()
            val result = matcher.match(FIXTURE, null) { partials += it }
            assertEquals("the hidden channel stays hidden", listOf("Spain vs Austria HD"), result.map { it.channel.name })
            assertTrue("hidden channel never reaches a partial", partials.none { it.channel.streamId == 1 })
            // Matched channels plus the few generic probe candidates looked at; never the 5,000-row lineup.
            assertTrue("hashed $hashed channel identities", hashed <= 20)
        } finally {
            io.mockk.unmockkObject(IptvIdentity)
        }
    }

    /**
     * Onn: once the hash was gone, the broadcaster-listing tier normalised every channel name (coreNorm, ~130 us each)
     * on every open — 8.5 s for 55k channels and ONE listed station — and the scoring pass fully evaluated every
     * name (6.9 s). Both now pay only for the channels that could match.
     */
    @Test
    fun `listings and name scoring do not normalise every channel of the lineup`() = runTest {
        val lineup = listOf(channel(1, "US: ESPN 2", XTREAM_URL), channel(2, "Spain vs Austria", XTREAM_URL)) +
            (3..5_000).map { channel(it, "US: Channel $it", XTREAM_URL) }
        val matcher = matcher(xtreamAccount() to clientWith(*lineup.toTypedArray()))
        var cores = 0
        var scored = 0
        io.mockk.mockkObject(com.nuvio.tv.core.epg.EpgNorm, SportsEventMatchEngine)
        every { com.nuvio.tv.core.epg.EpgNorm.coreNorm(any()) } answers { cores++; callOriginal() }
        every { SportsEventMatchEngine.name(any(), any()) } answers { scored++; callOriginal() }
        try {
            val result = matcher.match(FIXTURE, null, stations = listOf(RadarTvStation(country = "United States", channel = "ESPN 2")))
            assertEquals(setOf("US: ESPN 2", "Spain vs Austria"), result.map { it.channel.name }.toSet())
            assertTrue("normalised $cores channel names for one listed station", cores <= 20)
            assertTrue("fully scored $scored channel names", scored <= 20)
        } finally {
            io.mockk.unmockkObject(com.nuvio.tv.core.epg.EpgNorm, SportsEventMatchEngine)
        }
    }

    /**
     * Cold start: each playlist's download began only after the previous playlist's wait ended, so three panels
     * waited up to 3 x [RadarChannelMatcher.accountWaitMs] in a row (12 s on the Onn) inside a 6 s lookup.
     * All downloads start together and share one wait.
     */
    @Test
    fun `playlist downloads start together and share one wait`() = runTest {
        val accounts = (1..3).map { xtreamAccount().copy(id = "p$it", name = "Panel $it", baseUrl = "http://p$it.example") }
        val clients = accounts.mapIndexed { i, _ ->
            clientWith().also { client ->
                coEvery { client.liveChannels(any(), any()) } coAnswers {
                    kotlinx.coroutines.delay(400) // real time, in the matcher's own IO scope
                    Result.success(listOf(channel(i + 1, "Spain vs Austria ${i + 1}", XTREAM_URL)))
                }
            }
        }
        val matcher = matcher(*accounts.zip(clients).toTypedArray()).also { it.accountWaitMs = 1_000 }
        val started = System.nanoTime()
        val result = withContext(kotlinx.coroutines.Dispatchers.Default) { matcher.match(FIXTURE, null) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("every playlist answered within the shared wait", 3, result.size)
        assertTrue("downloads ran one after another: $elapsedMs ms", elapsedMs < 1_000)
    }

    // --- helpers ---------------------------------------------------------------

    private fun matcher(
        vararg playlists: Pair<XtreamAccount, IptvClient>,
        contentDb: IptvContentDb = mockk(relaxed = true),
        catalogRevision: () -> Long? = { null },
        overlay: () -> OverlaySnapshot = { OverlaySnapshot.EMPTY },
    ): RadarChannelMatcher {
        val overlayRepository = mockk<IptvOverlayRepository>()
        coEvery { overlayRepository.freshSnapshot() } coAnswers { overlay() }
        coEvery { contentDb.builtAt(any()) } coAnswers { catalogRevision() }
        val store = mockk<XtreamAccountStore>()
        every { store.accounts } returns flowOf(playlists.map { it.first })
        val factory = mockk<IptvClientFactory>()
        playlists.forEach { (account, client) -> every { factory.clientFor(account) } returns client }
        return RadarChannelMatcher(
            xtreamClient = mockk(relaxed = true),
            accountStore = store,
            registry = XtreamItemRegistry(),
            matchIndex = mockk(relaxed = true),
            resolver = mockk(relaxed = true),
            epgMirror = mockk(relaxed = true),
            // Defaults to relaxed, i.e. epgSearch finds nothing: most cases here are about
            // the name/keyword tiers underneath the guide.
            contentDb = contentDb,
            clientFactory = factory,
            catchUp = mockk(relaxed = true),
            refreshStore = mockk(relaxed = true),
            overlayRepository = overlayRepository,
        )
    }

    private fun clientWith(vararg channels: XtreamChannel): IptvClient = mockk<IptvClient>().apply {
        coEvery { liveChannels(any(), any()) } returns Result.success(channels.toList())
        coEvery { shortEpg(any(), any(), any()) } returns Result.success(emptyList())
    }

    private fun channel(streamId: Int, name: String, url: String) = XtreamChannel(
        streamId = streamId,
        name = name,
        logo = null,
        epgChannelId = null,
        categoryId = null,
        hasArchive = false,
        streamUrl = url,
    )

    private fun xtreamAccount() = XtreamAccount(
        id = XTREAM_ID, name = "Panel", baseUrl = "http://panel.example:8080",
        username = "user", password = "pass",
    )

    private fun m3uAccount() = XtreamAccount(
        id = M3U_ID, name = "Playlist", baseUrl = "", username = "", password = "",
        sourceType = XtreamAccount.SOURCE_URL,
    )

    private fun stalkerAccount() = XtreamAccount(
        id = STALKER_ID, name = "Portal", baseUrl = "", username = "", password = "",
        sourceType = XtreamAccount.SOURCE_STALKER,
        portalUrl = "http://portal.example", macAddress = "00:1A:79:00:00:01",
    )

    private companion object {
        const val XTREAM_ID = "http://panel.example:8080|user"
        const val M3U_ID = "m3u|playlist"
        const val STALKER_ID = "stalker|portal"
        const val XTREAM_URL = "http://panel.example:8080/live/user/pass/1.ts"
        const val M3U_URL = "http://cdn.example/playlist/2.m3u8"
        /** Kickoff in the past, so the EPG stage runs like it would for a real opened fixture. */
        val FIXTURE = RadarFixture(
            id = "1",
            home = "Spain",
            away = "Austria",
            ts = "2020-05-01T12:00:00",
        )
    }
}
