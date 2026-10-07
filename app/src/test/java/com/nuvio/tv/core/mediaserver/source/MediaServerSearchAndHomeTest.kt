package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.FakeClient
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.client.HomeShelves
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.item
import com.nuvio.tv.core.mediaserver.policy.HomeRefreshPolicy
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.nuvio.tv.core.mediaserver.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

private object FakeTitles : MediaServerRowTitles {
    override suspend fun home(row: MediaServerHomeRow, serverName: String) = "${row.name}@$serverName"
    override suspend fun searchMovies(serverName: String) = "Movies@$serverName"
    override suspend fun searchSeries(serverName: String) = "Series@$serverName"
}

class MediaServerSearchAndHomeTest {
    @After
    fun reset() = MediaServerItemRegistry.reset()

    private val client = FakeClient()
    private fun rig(homeRows: Set<MediaServerHomeRow> = emptySet(), signedIn: Boolean = true, enabled: Boolean = true) = TestRig(clientFactory = { client }).also { r ->
        val e = entry().copy(homeRows = homeRows, enabled = enabled)
        r.store.applyFromRemote(1, listOf(e))
        if (signedIn) r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }

    // --- search ---

    @Test
    fun searchRowsAreKeyedByTheSourceNotTheUserAndSplitMoviesFromShows() = runTest {
        val rig = rig()
        client.searchHits = listOf(item("m1", "Matrix"), item("m2", "Matrix 2"), item("s1", "Matrix Show", "Series"))
        val rows = MediaServerSearchProvider(rig.store, rig.services, FakeTitles).search("matrix")
        assertEquals(listOf("ms:jellyfin:$M:search:movies", "ms:jellyfin:$M:search:series"), rows.map { it.catalogId })
        assertFalse("a re-login must not orphan the viewer's choices", rows.any { it.catalogId.contains(U) })
        assertEquals(listOf(2, 1), rows.map { it.hits.size })
        assertEquals("Movies@Home", rows[0].name); assertEquals("movie", rows[0].rawType)
        assertNotNull("hits are registered so a tap plays", MediaServerItemRegistry.get("ms:jellyfin:$M:$U:movie:m1"))
    }

    @Test
    fun aSignedOutDisabledOrAddresslessServerIsNeverSearched() = runTest {
        for (r in listOf(rig(signedIn = false), rig(enabled = false))) {
            val p = MediaServerSearchProvider(r.store, r.services, FakeTitles)
            assertFalse(p.hasSearchableSources()); assertNull(p.sourceSignature().first())
            assertTrue(p.search("x").isEmpty())
        }
        val noAddress = TestRig(clientFactory = { client }).also { r -> val e = entry(address = null); r.store.applyFromRemote(1, listOf(e)); r.credentials.save(e.serverKey, StoredCredential("t")) }
        assertFalse(MediaServerSearchProvider(noAddress.store, noAddress.services, FakeTitles).hasSearchableSources())
    }

    @Test
    fun aBlankQueryOrAFailingServerAddsNothingAndNeverThrows() = runTest {
        val rig = rig()
        val p = MediaServerSearchProvider(rig.store, rig.services, FakeTitles)
        assertTrue(p.search("   ").isEmpty())
        client.failWith = MediaServerException.Unreachable("down")
        assertTrue(p.search("x").isEmpty())
        client.failWith = MediaServerException.Http(401)
        assertTrue(p.search("x").isEmpty())
        assertFalse("a revoked token surfaces as signed out", rig.services.isSignedIn(rig.store.current().single()))
    }

    @Test
    fun theSignatureChangesWithTheSignInStateAndNeverContainsACredential() = runTest {
        val rig = rig(signedIn = false)
        val p = MediaServerSearchProvider(rig.store, rig.services, FakeTitles)
        assertNull(p.sourceSignature().first())
        rig.credentials.save(rig.store.current().single().serverKey, StoredCredential("TOKEN-1"))
        rig.services.notifyCredentialsChanged()
        val sig = assertNotNull(p.sourceSignature().first())
        assertFalse("a digest: $sig", sig.contains("TOKEN-1") || sig.contains(M) || sig.contains(U))
    }

    // --- home ---

    private fun contributor(rig: TestRig, cw: Set<String> = emptySet()) =
        MediaServerHomeContributor(rig.store, rig.services, { rig.nowMs }, { cw }, FakeTitles)

    private val movie = item("m1", "Matrix")
    private val show = item("s1", "Severance", "Series")
    private val episode = item("e1", "Pilot", "Episode") { it.copy(seriesId = "s1", seriesName = "Severance", parentIndexNumber = 1, indexNumber = 1) }

    @Test
    fun nothingEnabledMeansNoRowsAndNoRequestEver() = runTest {
        val rig = rig(homeRows = emptySet())
        val c = contributor(rig)
        assertTrue(c.sections(false).isEmpty()); assertTrue(c.sections(true).isEmpty())
        assertEquals("D2: a server's own rows are opt-in - not even a forced refresh asks", 0, client.homeCalls)
    }

    @Test
    fun aSignedOutOrDisabledServerContributesNothing() = runTest {
        assertTrue(contributor(rig(setOf(MediaServerHomeRow.NEXT_UP), signedIn = false)).sections(false).isEmpty())
        assertTrue(contributor(rig(setOf(MediaServerHomeRow.NEXT_UP), enabled = false)).sections(false).isEmpty())
        assertEquals(0, client.homeCalls)
    }

    @Test
    fun enabledRowsBecomeStableKeyedSectionsAndOnlyThoseRowsAreFetched() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP, MediaServerHomeRow.RECENTLY_ADDED))
        client.shelves = HomeShelves(nextUp = listOf(episode), recentlyAdded = listOf(movie, show))
        val sections = contributor(rig).sections(false)
        assertEquals("the delta: only what the user enabled", setOf(MediaServerHomeRow.NEXT_UP, MediaServerHomeRow.RECENTLY_ADDED), client.lastHomeRows)
        assertEquals(listOf("ms:jellyfin:$M:next_up", "ms:jellyfin:$M:recently_added"), sections.map { it.key })
        assertFalse(sections.any { it.key.contains(U) })
        assertEquals(listOf("NEXT_UP@Home", "RECENTLY_ADDED@Home"), sections.map { it.title })
        assertEquals("an episode shelf item opens its series", listOf("ms:jellyfin:$M:$U:series:s1"), sections[0].items.map { it.id })
        assertEquals("jellyfin:$M", sections[0].sourceKey); assertEquals("next_up", sections[0].listId)
        assertTrue(sections[1].hasMore)
    }

    @Test
    fun theTtlGatesRefetchAndTheCachedRowsAreReturned() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.RECENTLY_ADDED))
        client.shelves = HomeShelves(recentlyAdded = listOf(movie))
        val c = contributor(rig)
        assertEquals(1, c.sections(false).size)
        rig.nowMs += 60_000
        assertEquals(1, c.sections(false).size)
        assertEquals("fresh: no request at all", 1, client.homeCalls)
        rig.nowMs += HomeRefreshPolicy.ROW_TTL_MS
        c.sections(false)
        assertEquals(2, client.homeCalls)
        c.sections(true)
        assertEquals("pull-to-refresh bypasses the ttl", 3, client.homeCalls)
        c.invalidate("jellyfin:$M")
        c.sections(false)
        assertEquals("a UserDataChanged / our own playback report", 4, client.homeCalls)
    }

    @Test
    fun aFailingServerBacksOffThenHidesItsRowsWithoutARetryLoop() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.RECENTLY_ADDED))
        client.shelves = HomeShelves(recentlyAdded = listOf(movie))
        val c = contributor(rig)
        c.sections(false)
        client.failWith = MediaServerException.Unreachable("down")
        rig.nowMs += HomeRefreshPolicy.ROW_TTL_MS
        assertEquals("first failure: the stale rows stay", 1, c.sections(false).size)
        val callsAfterFirst = client.homeCalls
        assertEquals(1, c.sections(false).size)
        assertEquals("backing off: no request", callsAfterFirst, client.homeCalls)
        rig.nowMs += 31_000
        assertTrue("second failure: the server counts as offline, rows hidden", c.sections(false).isEmpty())
        assertTrue(c.isOffline("jellyfin:$M:$U"))
        val calls = client.homeCalls
        repeat(3) { c.sections(false) }
        assertEquals("an offline server is not retried in a loop", calls, client.homeCalls)
    }

    @Test
    fun aServiceUnavailableHonoursRetryAfter() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP))
        val c = contributor(rig)
        client.failWith = MediaServerException.Http(503, retryAfterSeconds = 120)
        c.sections(false)
        val calls = client.homeCalls
        rig.nowMs += 100_000
        c.sections(false)
        assertEquals("still inside the Retry-After window", calls, client.homeCalls)
        client.failWith = null
        client.shelves = HomeShelves(nextUp = listOf(episode))
        rig.nowMs += 30_000
        assertEquals(1, c.sections(false).size)
    }

    @Test
    fun aRevokedTokenDropsTheSession() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP))
        client.failWith = MediaServerException.Http(401)
        contributor(rig).sections(false)
        assertFalse(rig.services.isSignedIn(rig.store.current().single()))
    }

    @Test
    fun itemsTuvoraContinueWatchingAlreadyShowsAreHiddenFromAServerRowButNotFromRecentlyAdded() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.CONTINUE_WATCHING, MediaServerHomeRow.RECENTLY_ADDED))
        client.shelves = HomeShelves(continueWatching = listOf(movie, show), recentlyAdded = listOf(movie))
        val sections = contributor(rig, cw = setOf("ms:jellyfin:$M:$U:movie:m1")).sections(false)
        assertEquals(listOf("ms:jellyfin:$M:$U:series:s1"), sections.single { it.listId == "continue_watching" }.items.map { it.id })
        assertEquals(1, sections.single { it.listId == "recently_added" }.items.size)
        val all = contributor(rig(setOf(MediaServerHomeRow.CONTINUE_WATCHING)), cw = setOf("ms:jellyfin:$M:$U:movie:m1", "ms:jellyfin:$M:$U:series:s1")).sections(false)
        assertTrue("an emptied row is not shown", all.isEmpty())
    }

    @Test
    fun seeAllServesPagesAndOwnsOnlyItsSources() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.RECENTLY_ADDED))
        client.searchHits = (1..100).map { item("m$it", "M$it") }
        val c = contributor(rig)
        assertTrue(c.ownsSource("jellyfin:$M")); assertFalse(c.ownsSource("emby:other"))
        val page = c.loadSourcePage("jellyfin:$M", "recently_added", skip = null)
        assertEquals(100, page.items.size)
        assertEquals("a full page means there may be more", 100, page.nextSkip)
        client.searchHits = listOf(item("m1", "M1"))
        val last = c.loadSourcePage("jellyfin:$M", "library:view1", skip = 100)
        assertNull(last.nextSkip)
        val unknown = c.loadSourcePage("emby:other", "recently_added", null)
        assertTrue(unknown.items.isEmpty())
    }
}
