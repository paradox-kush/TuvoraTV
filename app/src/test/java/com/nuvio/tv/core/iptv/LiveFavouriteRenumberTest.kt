package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.match.IndexedItem
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.match.XtreamStreamSource
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.repository.StreamRepositoryImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * RED-FIRST (TV twin of NuvioMobile's LiveFavouriteRenumberTest; Overlay Build Spec P1 test
 * "stream_id wholesale renumber"; mechanism documented in commit 9c7248641 "panels renumber their
 * catalogs").
 *
 * A live favourite is stored as `xtream:{account}:live:{sid}`. Today
 * [StreamRepositoryImpl.refreshIptvStreamUrl] parses that FROZEN sid and hands it straight to
 * `client.resolveStreamUrl` — it never consults the catalog. When the provider renumbers the channel
 * (100 → 900, same name + tvg-id) the favourite keeps asking for sid 100: a dead stream, or a
 * different channel the panel reassigned that id to.
 *
 * The client mock echoes the sid it is asked for, so the assertions read which sid the REPOSITORY
 * chose. A real [XtreamMatchIndex] holds the current catalog underneath, so when P1 resolves the
 * favourite through its identity to the current sid this test goes green without being rewritten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveFavouriteRenumberTest {

    private val app = RuntimeEnvironment.getApplication()

    private val account = XtreamAccount(
        id = "http://panel.example.com:8080|u",
        name = "Panel",
        baseUrl = "http://panel.example.com:8080",
        username = "u",
        password = "p",
        sourceType = XtreamAccount.SOURCE_XTREAM,
    )

    private fun bbcOne(sid: Int) = IndexedItem(
        sid = sid, name = "BBC ONE HD", year = null, tmdb = null, ext = null,
        poster = null, categoryId = "uk", epgId = "BBCOne.uk", hasArchive = false,
    )

    @Test
    fun `a live favourite survives a provider stream id renumber`() = runTest {
        val index = XtreamMatchIndex(app)

        // Echoes the sid it is asked for: the URL therefore reveals the repository's choice.
        val client = mockk<IptvClient>()
        coEvery { client.resolveStreamUrl(account, "live", any(), any()) } answers {
            "${account.baseUrl}/live/u/p/${thirdArg<Int>()}.ts"
        }
        val factory = mockk<IptvClientFactory> { every { clientFor(account) } returns client }
        val store = mockk<XtreamAccountStore> { every { accounts } returns flowOf(listOf(account)) }
        val streamSource = XtreamStreamSource(
            client = mockk(relaxed = true),
            stalkerClient = mockk(relaxed = true),
            resolver = mockk(relaxed = true),
            index = index,
            tmdbService = mockk(relaxed = true),
        )
        val repo = StreamRepositoryImpl(
            context = app,
            api = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            pluginManager = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            debridStreamPresentation = mockk(relaxed = true),
            localDebridAvailabilityService = mockk(relaxed = true),
            xtreamRegistry = XtreamItemRegistry(),
            iptvClientFactory = factory,
            xtreamAccountStore = store,
            xtreamStreamSource = streamSource,
        )

        // Day 1: BBC ONE HD is stream 100 and the user favourites it. All the app keeps is this id.
        index.rebuild(account.id, MatchKind.LIVE, listOf(bbcOne(100)))
        val favouriteId = XtreamItemRegistry.liveId(account.id, 100)

        val before = repo.refreshIptvStreamUrl(favouriteId, forceFresh = false)
        assertNotNull("baseline: favourite resolves while sid 100 exists", before)
        assertTrue("baseline resolves to sid 100 but was $before", before!!.endsWith("/live/u/p/100.ts"))

        // Day 2: the panel renumbers its catalog. Same channel (name + tvg-id), new sid; 100 is gone.
        index.rebuild(account.id, MatchKind.LIVE, listOf(bbcOne(900)))

        val after = repo.refreshIptvStreamUrl(favouriteId, forceFresh = false)
        assertNotNull("favourite must still resolve after the renumber", after)
        assertTrue(
            "favourite must follow the channel to its CURRENT sid 900 (identity = name + tvg-id), " +
                "but resolved from the frozen sid: $after",
            after!!.endsWith("/live/u/p/900.ts"),
        )
        coVerify { client.resolveStreamUrl(account, "live", 900, any()) }
    }
}
