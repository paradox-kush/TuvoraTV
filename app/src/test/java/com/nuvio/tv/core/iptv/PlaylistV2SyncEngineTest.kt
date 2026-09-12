package com.nuvio.tv.core.iptv

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B24 (TV) — the real v2 sync engine's behavior via a fake transport + in-memory Gson-serialized
 * state. Mirrors the Mobile/Desktop PlaylistV2SyncEngineTest.
 */
class PlaylistV2SyncEngineTest {

    private val gson = Gson()
    private fun acc(id: String, name: String = "P") =
        XtreamAccount(id = id, name = name, baseUrl = "http://$id", username = "u", password = "p")

    private class FakeServer(var rows: List<XtreamAccount> = emptyList(), var revision: Long = 0, var generation: Long = 0) {
        var lastMutationId: String? = null
        var lastMutationHash: String? = null
        var dropNextResponse = false
        var deletedNoProfile = false   // deleted and NOT recreated (no profiles row) — mirrors the backend guard

        fun pull() = PlaylistPullResponse(revision, rows, generation)

        /** A profile deletion: clears rows, bumps revision AND generation, marks it deleted (no profiles row). */
        fun delete() { rows = emptyList(); revision += 1; generation += 1; deletedNoProfile = true }

        fun push(expected: Long?, expectedGen: Long?, payload: List<XtreamAccount>, deleteAll: Boolean, mutationId: String): PlaylistPushResponse {
            val hash = payload.joinToString(",") { it.id } + "|" + deleteAll
            if (mutationId == lastMutationId) {
                if (hash != lastMutationHash) return PlaylistPushResponse.Rejected("reuse")
                return PlaylistPushResponse.Ok(revision, deduped = true)
            }
            // Order mirrors the backend RPC: dedup, then stale_generation, then profile_deleted.
            if (expectedGen != null && expectedGen < generation) return PlaylistPushResponse.Rejected("stale_generation")
            if (deletedNoProfile) return PlaylistPushResponse.Rejected("profile_deleted")
            if (payload.isEmpty() && !deleteAll) return PlaylistPushResponse.Rejected("empty")
            if (expected == null) {
                if (revision != 0L || rows.isNotEmpty()) return PlaylistPushResponse.Conflict(revision, rows)
            } else if (expected != revision) return PlaylistPushResponse.Conflict(revision, rows)
            rows = payload; revision += 1; lastMutationId = mutationId; lastMutationHash = hash
            deletedNoProfile = false  // a committed write means the profile is live again
            if (dropNextResponse) { dropNextResponse = false; throw RuntimeException("lost") }
            return PlaylistPushResponse.Ok(revision)
        }
    }

    private inner class Device(val server: FakeServer, var local: List<XtreamAccount>, var authoritative: Boolean = true) {
        val stateStore = HashMap<Int, String>()
        var mutCounter = 0
        val transport = object : PlaylistSyncTransport {
            override suspend fun pull(profileId: Int) = server.pull()
            override suspend fun push(profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, deleteAll: Boolean, mutationId: String, expectedGeneration: Long?) =
                server.push(expectedRevision, expectedGeneration, accounts, deleteAll, mutationId)
        }
        fun engine() = PlaylistV2SyncEngine(
            transport = transport,
            loadState = { decodePlaylistSyncState(gson, stateStore[it]) },
            saveState = { p, s -> stateStore[p] = encodePlaylistSyncState(gson, s) },
            currentAccounts = { local },
            canPush = { authoritative },
            applyLocal = { _, a -> local = a; authoritative = true },
            stillActive = { it == 1 },
            newMutationId = { "mut-${++mutCounter}" },
        )
        fun recordAdd(a: XtreamAccount) { mutate { it.recordAdd(a) }; local = local.filterNot { x -> x.id == a.id } + a }
        private fun mutate(f: (List<PendingOpDto>) -> List<PendingOpDto>) {
            val s = decodePlaylistSyncState(gson, stateStore[1])
            stateStore[1] = encodePlaylistSyncState(gson, s.copy(pending = f(s.pending)))
        }
    }

    @Test
    fun `reset then add-C over server A and B reconciles to A B C`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A"), acc("B")), revision = 4)
        val dev = Device(server, local = emptyList(), authoritative = false)
        dev.recordAdd(acc("C"))
        val outcome = dev.engine().sync(1)
        assertEquals("reconciled push", PlaylistSyncOutcome.SYNCED, outcome)
        assertEquals("server holds A B C", listOf("A", "B", "C"), server.rows.map { it.id }.sorted())
        assertEquals(5L, server.revision)
    }

    @Test
    fun `commit succeeds but response lost - retry dedups`() = runBlocking {
        val server = FakeServer(rows = listOf(acc("A")), revision = 1)
        val dev = Device(server, local = listOf(acc("A")))
        dev.recordAdd(acc("B"))
        server.dropNextResponse = true
        assertEquals(PlaylistSyncOutcome.PUSH_FAILED, dev.engine().sync(1))
        assertEquals("commit happened", 2L, server.revision)
        // restart: a fresh engine over the SAME persisted state
        val dev2 = Device(server, local = dev.local, authoritative = dev.authoritative)
        dev2.stateStore.putAll(dev.stateStore)
        assertEquals(PlaylistSyncOutcome.SYNCED, dev2.engine().sync(1))
        assertEquals("no phantom revision", 2L, server.revision)
        assertTrue(server.rows.map { it.id }.containsAll(listOf("A", "B")))
    }

    @Test
    fun deletedProfilePendingIsDiscardedAndCannotPopulateRecreatedProfile() = runBlocking {
        // Client A recorded a pending add C on a live profile (anchored to generation 0), then the
        // profile was DELETED elsewhere (server empty, revision+generation bumped). A's recovery must
        // NOT replay C: on pull it sees the newer generation and discards the now-obsolete pending, so
        // nothing is pushed and the profile is not resurrected. This replaces the earlier (incorrect)
        // "retain and replay once recreated" behavior — replaying a dead profile's op onto a recreated
        // profile at the reused id is exactly the silent-population defect B24 §1 requires us to prevent.
        val server = FakeServer(rows = listOf(acc("A")), revision = 1, generation = 0)
        val dev = Device(server, local = listOf(acc("A")))
        // Sync once so state is anchored to generation 0 with A, then record a pending add C.
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, dev.engine().sync(1))
        dev.recordAdd(acc("C"))
        assertEquals("pending C recorded", listOf("C"), decodePlaylistSyncState(gson, dev.stateStore[1]).pending.map { it.id })

        // The profile is deleted (and not yet recreated).
        server.delete()

        // A's recovery: pull reports generation 1 > anchored 0 -> discard pending C; nothing to push.
        val out1 = dev.engine().sync(1)
        assertTrue("no push after a discard leaves nothing to sync", out1 == PlaylistSyncOutcome.UP_TO_DATE || out1 == PlaylistSyncOutcome.WITHHELD)
        assertTrue("server not resurrected", server.rows.isEmpty())
        val st1 = decodePlaylistSyncState(gson, dev.stateStore[1])
        assertTrue("obsolete pending discarded", st1.pending.isEmpty())
        assertEquals("generation adopted", 1L, st1.generation)

        // Restart (fresh engine over the same persisted state) + retry: still no resurrection.
        val dev2 = Device(server, local = dev.local, authoritative = dev.authoritative)
        dev2.stateStore.putAll(dev.stateStore)
        val out2 = dev2.engine().sync(1)
        assertTrue("restart re-sync stays clean", out2 == PlaylistSyncOutcome.UP_TO_DATE || out2 == PlaylistSyncOutcome.WITHHELD)
        assertTrue("server stays empty across restart", server.rows.isEmpty())

        // The profile is legitimately RECREATED at the reused id (profiles row back). A genuinely NEW
        // local op (anchored to the current generation 1) must still sync normally.
        server.deletedNoProfile = false
        dev2.recordAdd(acc("D"))
        assertEquals("legitimate new op on recreated profile commits", PlaylistSyncOutcome.SYNCED, dev2.engine().sync(1))
        assertEquals("recreated profile holds only its own new op", listOf("D"), server.rows.map { it.id })
    }

    @Test
    fun staleGenerationPushIsRejectedByServerContract() = runBlocking {
        // Defense-in-depth: even if a client did NOT discard (older build), a push anchored to a
        // superseded generation is rejected by the server contract, so it cannot populate the profile.
        val server = FakeServer(rows = emptyList(), revision = 2, generation = 1)
        assertEquals(
            PlaylistPushResponse.Rejected("stale_generation"),
            server.push(expected = 2, expectedGen = 0, payload = listOf(acc("C")), deleteAll = false, mutationId = "m1"),
        )
        assertTrue("server not populated by a stale-generation push", server.rows.isEmpty())
    }
}
