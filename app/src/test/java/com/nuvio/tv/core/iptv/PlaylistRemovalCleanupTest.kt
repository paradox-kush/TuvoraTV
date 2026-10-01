package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.PlaylistRemovalOrigin.SyncPull
import com.nuvio.tv.core.iptv.PlaylistRemovalOrigin.UserDelete
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.CatchUp
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.ContentDb
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.EpgMirror
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.HubSelection
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.LiveChannels
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.M3uFileCopy
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.MatchIndex
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.Overlay
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.RefreshStamp
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.SavedRefs
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.ServerFailover
import com.nuvio.tv.core.iptv.PlaylistRemovalTarget.SessionCaches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure "what to purge for a removed playlist" plan. Twin of NuvioMobile/NuvioDesktop's
 * commonTest PlaylistRemovalCleanupTest — JUnit here, so assertEquals(message, expected, actual).
 */
class PlaylistRemovalCleanupTest {

    private val caches = setOf(ContentDb, MatchIndex, EpgMirror, RefreshStamp, CatchUp, ServerFailover, SessionCaches, HubSelection)
    private val userData = setOf(Overlay, LiveChannels, SavedRefs, M3uFileCopy)

    @Test
    fun `a user delete clears every store keyed by the playlist`() {
        assertEquals("user delete plan", PlaylistRemovalTarget.entries.toSet(), PlaylistRemovalCleanup.plan(UserDelete))
    }

    @Test
    fun `a sync pull clears every cache but no user data`() {
        val plan = PlaylistRemovalCleanup.plan(SyncPull)
        assertEquals("sync pull plan", caches, plan)
        assertTrue("a transient pull must never drop user data", plan.none { it.userData })
    }

    @Test
    fun `the cache and user-data split is exhaustive`() {
        assertEquals("every target classified", PlaylistRemovalTarget.entries.toSet(), caches + userData)
        assertTrue("user-data targets flagged", userData.all { it.userData })
        assertTrue("cache targets not flagged", caches.none { it.userData })
    }

    @Test
    fun `the content db is purged whatever the source type`() {
        // Xtream playlists fill the per-playlist EPG tables too (xmltv store lane + catch-up refills).
        assertTrue("delete purges content db", ContentDb in PlaylistRemovalCleanup.plan(UserDelete))
        assertTrue("pull purges content db", ContentDb in PlaylistRemovalCleanup.plan(SyncPull))
    }

    @Test
    fun `a sync pull keeps the saved copy of a file playlist and only a user delete removes it`() {
        // The copy holds bytes from a document the user picked — the original may be gone, so it cannot
        // rebuild. A transient pull (B24) must not destroy it; if the playlist comes back, it re-ingests.
        assertTrue("the M3U file copy is user data", M3uFileCopy.userData)
        assertFalse("a sync pull keeps the file copy", M3uFileCopy in PlaylistRemovalCleanup.plan(SyncPull))
        assertTrue("a user delete removes the file copy", M3uFileCopy in PlaylistRemovalCleanup.plan(UserDelete))
    }

    @Test
    fun `removed ids are the ones the new list no longer carries`() {
        assertEquals("dropped ids", listOf("a", "c"), PlaylistRemovalCleanup.removedIds(listOf("a", "b", "c"), listOf("b", "d")))
        assertEquals("nothing dropped", emptyList<String>(), PlaylistRemovalCleanup.removedIds(listOf("a"), listOf("a")))
        assertEquals("nothing before", emptyList<String>(), PlaylistRemovalCleanup.removedIds(emptyList(), listOf("a")))
        assertEquals("deduplicated", listOf("a"), PlaylistRemovalCleanup.removedIds(listOf("a", "a"), emptyList()))
    }

    @Test
    fun `the hub forgets a remembered selection only when it names the removed playlist`() {
        assertTrue("same id", PlaylistRemovalCleanup.dropsHubSelection("a", "a"))
        assertFalse("other id", PlaylistRemovalCleanup.dropsHubSelection("b", "a"))
        assertFalse("nothing remembered", PlaylistRemovalCleanup.dropsHubSelection(null, "a"))
    }

    @Test
    fun `a refresh stamp map loses only the removed playlist`() {
        assertEquals("entry dropped", mapOf("b" to 2L), PlaylistRemovalCleanup.withoutPlaylist(mapOf("a" to 1L, "b" to 2L), "a"))
        val untouched = mapOf("b" to 2L)
        assertSame("absent id returns the same map", untouched, PlaylistRemovalCleanup.withoutPlaylist(untouched, "a"))
    }
}
