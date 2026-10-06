package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.ContributedRowDeclaration
import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.FakeClient
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.item
import com.nuvio.tv.core.mediaserver.policy.HomeRefreshPolicy
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

private object Titles : MediaServerRowTitles {
    override suspend fun home(row: MediaServerHomeRow, serverName: String) = "$row@$serverName"
    override suspend fun searchMovies(serverName: String) = "movies@$serverName"
    override suspend fun searchSeries(serverName: String) = "series@$serverName"
}

class MediaServerLibrariesPolicyTest {
    private fun view(id: String?, name: String?, type: String?) = ItemDto(id = id, name = name, collectionType = type)

    @Test
    fun onlyMovieAndShowLibrariesAreBrowsable() {
        val libs = MediaServerLibraries.browsable(
            listOf(
                view("1", "Movies", "movies"), view("2", "Shows", "tvshows"), view("3", "Music", "music"), view("4", "Mixed", null),
                view("5", "Photos", "homevideos"), view("6", "Live", "livetv"), view("7", "Playlists", "playlists"), view("8", "Collections", "boxsets"),
                view(null, "No id", "movies"), view("9", null, "movies"),
            ),
        )
        assertEquals(listOf("1" to MediaServerLibraries.Kind.MOVIES, "2" to MediaServerLibraries.Kind.SERIES, "4" to MediaServerLibraries.Kind.MIXED), libs.map { it.id to it.kind })
        assertEquals(listOf("Movies", "Shows", "Mixed"), libs.map { it.name })
    }
}

class MediaServerHomeLibrariesTest {
    private val client = FakeClient()
    private val movie = item("m1", "Matrix")

    private fun rig(homeRows: Set<MediaServerHomeRow> = emptySet(), libraries: Map<String, String> = emptyMap(), signedIn: Boolean = true) =
        TestRig(clientFactory = { client }).also { r ->
            val e = entry().copy(homeRows = homeRows, homeLibraries = libraries)
            r.store.applyFromRemote(1, listOf(e))
            r.store.update(e.key) { it.copy(homeRows = homeRows, homeLibraries = libraries) }
            if (signedIn) r.credentials.save(e.serverKey, StoredCredential("t"))
        }

    private fun contributor(rig: TestRig) = MediaServerHomeContributor(rig.store, rig.services, { rig.nowMs }, { emptySet() }, Titles)

    @Test
    fun aLibraryOnHomeIsARowOfItsOwnWithAStableKeyAndASeeAllTarget() = runTest {
        val rig = rig(libraries = mapOf("view1" to "Films"))
        client.searchHits = listOf(movie)
        val sections = contributor(rig).sections(false)
        val row = sections.single()
        assertEquals("ms:jellyfin:$M:library:view1", row.key)
        assertFalse("keys never carry a user id", row.key.contains(U))
        assertEquals("Films", row.title); assertEquals("Home", row.subtitle)
        assertEquals("jellyfin:$M", row.sourceKey); assertEquals("library:view1", row.listId); assertEquals("movie", row.rawType)
        assertEquals(listOf("ms:jellyfin:$M:$U:movie:m1"), row.items.map { it.id })
        assertEquals("libraries are their own query - the opt-in shelves are not asked for", 0, client.homeCalls)
    }

    @Test
    fun aLibraryRowIsTtlGatedAndHonoursTheBackoff() = runTest {
        val rig = rig(libraries = mapOf("view1" to "Films"))
        client.searchHits = listOf(movie)
        val c = contributor(rig)
        c.sections(false); c.sections(false)
        assertEquals("fresh: one request for two visits", 1, client.itemQueries.size)
        rig.nowMs += HomeRefreshPolicy.ROW_TTL_MS
        c.sections(false)
        assertEquals(2, client.itemQueries.size)
        c.invalidate("jellyfin:$M")
        c.sections(false)
        assertEquals("an own playback report invalidates it", 3, client.itemQueries.size)
        val q = client.itemQueries.last()
        assertEquals("view1", q.parentId); assertEquals(HomeRefreshPolicy.ROW_LIMIT, q.limit); assertFalse(q.enableTotalRecordCount)
    }

    @Test
    fun anEmptyOrOfflineLibraryRowIsHiddenAndNeverRetriedInALoop() = runTest {
        val rig = rig(libraries = mapOf("view1" to "Films"))
        client.searchHits = emptyList()
        val c = contributor(rig)
        assertTrue("an empty library shows no row", c.sections(false).isEmpty())
        client.failWith = com.nuvio.tv.core.mediaserver.client.MediaServerException.Unreachable("down")
        rig.nowMs += HomeRefreshPolicy.ROW_TTL_MS
        c.sections(false)
        rig.nowMs += 31_000
        c.sections(false)
        val calls = client.itemQueries.size
        repeat(4) { c.sections(false) }
        assertEquals("backed off after the failures", calls, client.itemQueries.size)
    }

    @Test
    fun declaredRowsListEveryConfiguredRowRegardlessOfItsItems() = runTest {
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP), libraries = mapOf("view1" to "Films"))
        val c = contributor(rig)
        assertEquals("before the localized titles are fetched: an English stand-in, never blocking resource I/O", "Next Up", c.declaredRows().first().title)
        c.prepareDeclaredRows()
        val declared = c.declaredRows()
        assertEquals(
            listOf(
                ContributedRowDeclaration("ms:jellyfin:$M:next_up", "NEXT_UP@Home", "Home"),
                ContributedRowDeclaration("ms:jellyfin:$M:library:view1", "Films", "Home"),
            ),
            declared,
        )
        assertEquals("declaring rows is network-free", 0, client.homeCalls + client.itemQueries.size)
    }

    @Test
    fun aDisabledOrUnconfiguredServerDeclaresNothing() {
        assertTrue(contributor(rig()).declaredRows().isEmpty())
        val rig = rig(homeRows = setOf(MediaServerHomeRow.NEXT_UP))
        rig.store.update(rig.store.current().single().key) { it.copy(enabled = false) }
        assertTrue(contributor(rig).declaredRows().isEmpty())
    }
}
