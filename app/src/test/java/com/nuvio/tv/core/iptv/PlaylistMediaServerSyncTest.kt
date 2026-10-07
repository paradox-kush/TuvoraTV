package com.nuvio.tv.core.iptv

import com.google.gson.Gson
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOp
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.recordAdd
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.recordDelete
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.recordUpdate
import com.nuvio.tv.core.mediaserver.api.MediaServerSyncBinding
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.sync.V2_SYNCED_SOURCE_TYPES
import com.nuvio.tv.core.sync.mediaServerEntries
import com.nuvio.tv.core.sync.playlistPushParams
import com.nuvio.tv.data.remote.supabase.SupabaseIptvPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 3 (TV twin of Mobile's PlaylistMediaServerSyncTest): Jellyfin/Emby server entries ride the ONE playlist-sync
 * engine (design 5.3) - a second row mapper and a second pending log under the same revision counter, never a second
 * sync loop. The engine is driven through a fake transport + in-memory Gson-serialized state, exactly like
 * PlaylistV2SyncEngineTest.
 */
class PlaylistMediaServerSyncTest {
    private val gson = Gson()
    private fun acc(id: String) = XtreamAccount(id = id, name = "P", baseUrl = "http://$id", username = "u", password = "p")
    private fun ms(n: String, name: String = "Server $n", enabled: Boolean = true, address: String? = "http://$n:8096") = MediaServerEntry(
        key = "jellyfin|m$n|u$n", type = MediaServerType.JELLYFIN, machineId = "m$n", userId = "u$n", name = name, address = address, enabled = enabled,
    )

    private class Server(var accounts: List<XtreamAccount> = emptyList(), var media: List<MediaServerEntry> = emptyList(), var revision: Long = 0) {
        var pushes = 0
        var lastMediaPushed: List<MediaServerEntry>? = null
        var lastDeleteAll: Boolean? = null
        var beforeNextPush: (() -> Unit)? = null
    }

    private inner class Device(val server: Server, var local: List<XtreamAccount> = emptyList(), var localMedia: List<MediaServerEntry> = emptyList(), var mediaAuthoritative: Boolean = true) {
        val stateStore = HashMap<Int, String>()
        var mutations = 0
        val transport = object : PlaylistSyncTransport {
            override suspend fun pull(profileId: Int) = PlaylistPullResponse(server.revision, server.accounts, 0, emptySet(), server.media)
            override suspend fun push(profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, deleteAll: Boolean, mutationId: String, expectedGeneration: Long?): PlaylistPushResponse =
                error("a media-server-aware engine must push through pushWithMediaServers")
            override suspend fun pushWithMediaServers(
                profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, mediaServers: List<MediaServerEntry>,
                deleteAll: Boolean, mutationId: String, expectedGeneration: Long?,
            ): PlaylistPushResponse {
                server.beforeNextPush?.let { server.beforeNextPush = null; it() }
                server.pushes++
                server.lastMediaPushed = mediaServers
                server.lastDeleteAll = deleteAll
                if (accounts.isEmpty() && mediaServers.isEmpty() && !deleteAll) return PlaylistPushResponse.Rejected("empty without delete_all")
                if (expectedRevision != null && expectedRevision != server.revision) return PlaylistPushResponse.Conflict(server.revision, server.accounts, emptySet(), server.media)
                server.accounts = accounts; server.media = mediaServers; server.revision += 1
                return PlaylistPushResponse.Ok(server.revision)
            }
        }
        val binding = MediaServerSyncBinding(
            currentEntries = { localMedia },
            canPushFullReplace = { mediaAuthoritative },
            applyFromRemote = { _, remote -> localMedia = MediaServerPendingOps.applyRemote(remote, localMedia); mediaAuthoritative = true },
        )

        fun engine() = PlaylistV2SyncEngine(
            transport = transport,
            loadState = { decodePlaylistSyncState(gson, stateStore[it]) },
            saveState = { p, s -> stateStore[p] = encodePlaylistSyncState(gson, s) },
            currentAccounts = { local },
            canPush = { true },
            applyLocal = { _, accts -> local = accts },
            stillActive = { true },
            newMutationId = { "mut-${++mutations}" },
            mediaServers = binding,
        )

        fun state() = decodePlaylistSyncState(gson, stateStore[1])
        private fun mutate(f: (List<MediaServerPendingOp>) -> List<MediaServerPendingOp>) {
            stateStore[1] = encodePlaylistSyncState(gson, state().let { it.withMediaServerPending(f(it.mediaServerPending)) })
        }
        fun add(e: MediaServerEntry) { localMedia = localMedia + e; mutate { it.recordAdd(e) } }
        fun update(e: MediaServerEntry, base: MediaServerEntry) { localMedia = localMedia.map { if (it.key == e.key) e else it }; mutate { it.recordUpdate(e, base) } }
        fun delete(key: String) { localMedia = localMedia.filterNot { it.key == key }; mutate { it.recordDelete(key) } }
    }

    @Test
    fun aLocalServerEntryIsPushedAndItsPendingIntentAcknowledged() = runBlocking {
        val server = Server()
        val a = Device(server)
        a.add(ms("1"))
        assertEquals(PlaylistSyncOutcome.SYNCED, a.engine().sync(1))
        assertEquals(listOf("jellyfin|m1|u1"), server.media.map { it.key })
        assertEquals(1L, server.revision)
        assertTrue("acknowledged only after the commit", a.state().mediaServerPending.isEmpty())
        assertFalse(server.lastDeleteAll!!)
    }

    @Test
    fun aSecondDeviceAdoptsTheEntryAsSignInToWithoutPushing() = runBlocking {
        val server = Server(media = listOf(ms("1", address = null)), revision = 3)
        val b = Device(server)
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, b.engine().sync(1))
        assertEquals(listOf("jellyfin|m1|u1"), b.localMedia.map { it.key })
        assertNull("no address synced: the device types its own", b.localMedia.single().address)
        assertEquals("adopting a server's state never pushes", 0, server.pushes)
        // and the second sync is a no-op
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, b.engine().sync(1))
        assertEquals(0, server.pushes)
    }

    @Test
    fun aRemovalOnOneDevicePropagatesToTheOther() = runBlocking {
        val server = Server(media = listOf(ms("1"), ms("2")), revision = 5)
        val a = Device(server, localMedia = server.media)
        val b = Device(server, localMedia = server.media)
        a.delete("jellyfin|m1|u1")
        assertEquals(PlaylistSyncOutcome.SYNCED, a.engine().sync(1))
        assertEquals(listOf("jellyfin|m2|u2"), server.media.map { it.key })
        b.engine().sync(1)
        assertEquals("no resurrection from the stale device", listOf("jellyfin|m2|u2"), b.localMedia.map { it.key })
    }

    @Test
    fun aConflictReReconcilesTheSameIntentOntoTheNewerServerRows() = runBlocking {
        val server = Server(media = listOf(ms("1")), revision = 2)
        val a = Device(server, localMedia = listOf(ms("1")))
        a.add(ms("3"))
        // another device adds server 2 between our pull and our push
        server.beforeNextPush = { server.media = server.media + ms("2"); server.revision += 1 }
        assertEquals(PlaylistSyncOutcome.SYNCED, a.engine().sync(1))
        assertEquals("neither device's add is lost", setOf("jellyfin|m1|u1", "jellyfin|m2|u2", "jellyfin|m3|u3"), server.media.map { it.key }.toSet())
        assertEquals(setOf("jellyfin|m1|u1", "jellyfin|m2|u2", "jellyfin|m3|u3"), a.localMedia.map { it.key }.toSet())
    }

    @Test
    fun aFieldEditOnlyOverridesTheFieldsThisDeviceChanged() = runBlocking {
        val original = ms("1")
        val server = Server(media = listOf(original.copy(name = "Renamed on another device")), revision = 4)
        val a = Device(server, localMedia = listOf(original))
        a.update(original.copy(enabled = false), base = original)
        a.engine().sync(1)
        val stored = server.media.single()
        assertEquals("Renamed on another device", stored.name)
        assertFalse(stored.enabled)
    }

    @Test
    fun aDamagedLocalStoreNeverDeletesTheServersEntries() = runBlocking {
        val server = Server(accounts = listOf(acc("A")), media = listOf(ms("1"), ms("2")), revision = 7)
        val a = Device(server, local = listOf(acc("A")), localMedia = emptyList(), mediaAuthoritative = false)
        a.engine().sync(1)
        assertEquals("a local store this build could not read must never full-replace the server's rows", 2, server.media.size)
        assertEquals("the pull heals it", 2, a.localMedia.size)
        // even when the Xtream side has a pending edit and pushes
        val b = Device(server, local = listOf(acc("A")), localMedia = emptyList(), mediaAuthoritative = false)
        b.stateStore[1] = encodePlaylistSyncState(gson, PlaylistSyncState(pending = emptyList<PendingOpDto>().recordAdd(acc("B"))))
        b.local = listOf(acc("A"), acc("B"))
        b.engine().sync(1)
        assertEquals(listOf("A", "B"), server.accounts.map { it.id }.sorted())
        assertEquals("the server entries ride along unchanged", 2, server.media.size)
    }

    @Test
    fun theFirstEverV2WritePushesLocalEntriesAsAnInitialCreation() = runBlocking {
        val server = Server()
        val a = Device(server, localMedia = listOf(ms("1")))
        assertEquals(PlaylistSyncOutcome.SYNCED, a.engine().sync(1))
        assertEquals(1, server.media.size)
        val offline = Server()
        val b = Device(offline, localMedia = listOf(ms("1")), mediaAuthoritative = false)
        assertEquals("an absent store is not an authored set", PlaylistSyncOutcome.UP_TO_DATE, b.engine().sync(1))
        assertEquals(0, offline.pushes)
    }

    @Test
    fun deletingTheLastEntryOfAProfileWithNoPlaylistsStillPushesAnExplicitDeleteAll() = runBlocking {
        val server = Server(media = listOf(ms("1")), revision = 2)
        val a = Device(server, localMedia = listOf(ms("1")))
        a.delete("jellyfin|m1|u1")
        a.engine().sync(1)
        assertTrue(server.media.isEmpty())
        assertTrue("an empty payload requires the explicit intent", server.lastDeleteAll!!)
    }

    @Test
    fun anEngineWithoutTheMediaServerBindingBehavesExactlyAsBefore() = runBlocking {
        var local = listOf(acc("A"))
        val s = Server(accounts = listOf(acc("A")), media = listOf(ms("1")), revision = 3)
        var pushed = 0
        val transport = object : PlaylistSyncTransport {
            override suspend fun pull(profileId: Int) = PlaylistPullResponse(s.revision, s.accounts, 0, emptySet(), s.media)
            override suspend fun push(profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, deleteAll: Boolean, mutationId: String, expectedGeneration: Long?): PlaylistPushResponse { pushed++; return PlaylistPushResponse.Ok(4) }
        }
        val engine = PlaylistV2SyncEngine(transport, { decodePlaylistSyncState(gson, null) }, { _, _ -> }, { local }, { true }, { _, a -> local = a }, { true }, { "m" })
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, engine.sync(1))
        assertEquals(0, pushed)
    }

    @Test
    fun theMutationIdFingerprintIsUnchangedWithoutServerEntriesAndDiffersWithThem() {
        val payload = "[{\"playlist_key\":\"A\"}]"
        assertEquals(PlaylistMutationIdPolicy.fingerprint(payload, false), PlaylistMutationIdPolicy.fingerprint(payload, false, emptyList()))
        assertTrue(PlaylistMutationIdPolicy.fingerprint(payload, false) != PlaylistMutationIdPolicy.fingerprint(payload, false, listOf(ms("1"))))
        assertTrue(PlaylistMutationIdPolicy.fingerprint(payload, false, listOf(ms("1"))) != PlaylistMutationIdPolicy.fingerprint(payload, false, listOf(ms("1", name = "x"))))
    }

    // --- the row mapper and the wire ---

    private fun row(key: String?, type: String, name: String? = null, url: String? = null, username: String? = null, baseUrl: String? = null) =
        SupabaseIptvPlaylist(playlistKey = key, sourceType = type, name = name, url = url, username = username, baseUrl = baseUrl)

    @Test
    fun theTwoRowMappersPartitionThePulledTable() {
        val rows = listOf(
            SupabaseIptvPlaylist(sourceType = "xtream", baseUrl = "http://x:80", username = "u", password = "p"),
            row("jellyfin|mA|uA", "jellyfin", name = "Home", url = "jellyfin://mA", username = "uA", baseUrl = "http://nas:8096"),
            row("emby|mB|uB", "emby", url = "emby://mB", username = "uB"),
            row("plex|mC|uC", "plex", url = "plex://mC", username = "uC"),
        )
        assertEquals(listOf("jellyfin|mA|uA", "emby|mB|uB"), mediaServerEntries(rows).map { it.key })
        assertEquals("the playlist mapper drops server rows (and parked Plex)", 1, com.nuvio.tv.core.sync.pulledPlaylists(rows).size)
        assertEquals("Home", mediaServerEntries(rows).first().name)
    }

    @Test
    fun theFullReplaceScopeNamesServerTypesOnlyOnTheV2PathNeverTheLegacyOne() {
        assertTrue("jellyfin" in V2_SYNCED_SOURCE_TYPES && "emby" in V2_SYNCED_SOURCE_TYPES)
        assertTrue(
            "the legacy v1 push carries no server rows: naming them in its scope would delete them",
            com.nuvio.tv.core.sync.SYNCED_SOURCE_TYPES.none { it == "jellyfin" || it == "emby" },
        )
        val legacy = playlistPushParams(listOf(acc("A")), 1, onlyIfEmpty = false)
        assertEquals(com.nuvio.tv.core.sync.SYNCED_SOURCE_TYPES, (legacy["p_source_types"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(V2_SYNCED_SOURCE_TYPES.containsAll(com.nuvio.tv.core.sync.SYNCED_SOURCE_TYPES))
        assertFalse("Plex is parked: its rows are never touched", "plex" in V2_SYNCED_SOURCE_TYPES)
    }

    @Test
    fun theV2PushRpcIsScopedToTheSixTypesAndCarriesServerRowsWithAnExplicitKeyAndNoCredential() {
        val params = com.nuvio.tv.core.sync.playlistPushV2Params(1, 7L, listOf(acc("A")), listOf(ms("1"), ms("2", address = null)), false, "m-1", 3L, V2_SYNCED_SOURCE_TYPES)
        val scope = (params["p_source_types"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals("never a null scope: xtream, m3u_url, m3u_file, stalker, jellyfin, emby", listOf("xtream", "m3u_url", "m3u_file", "stalker", "jellyfin", "emby"), scope)
        val rows = params["p_playlists"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, rows.size)
        val server = rows[1] as kotlinx.serialization.json.JsonObject
        assertEquals("jellyfin|m1|u1", (server["playlist_key"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("jellyfin", (server["source_type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("positions continue after the playlists", "1", (server["sort_order"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("http://1:8096", (server["base_url"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse("an unsynced address is simply absent", "base_url" in (rows[2] as kotlinx.serialization.json.JsonObject).keys)
        val forbidden = setOf("password", "stalker_password", "mac_address", "device_id", "serial_number", "signature", "token", "access_token")
        rows.drop(1).forEach { r -> assertTrue("credential-free: ${(r as kotlinx.serialization.json.JsonObject).keys}", (r as kotlinx.serialization.json.JsonObject).keys.none { it in forbidden }) }
        // the playlist-only push keeps its four-type scope: it carries no server rows, so naming them would delete them
        val plain = com.nuvio.tv.core.sync.playlistPushV2Params(1, null, listOf(acc("A")), emptyList(), false, "m-2", null, com.nuvio.tv.core.sync.SYNCED_SOURCE_TYPES)
        assertEquals(listOf("xtream", "m3u_url", "m3u_file", "stalker"), (plain["p_source_types"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun syncStateWrittenByAnOlderBuildStillDecodes() {
        val old = """{"revision":4,"mutationId":"m-1","pending":[],"deleteAllIntent":false,"generation":2}"""
        val s = decodePlaylistSyncState(gson, old)
        assertEquals(4L, s.revision)
        assertTrue(s.mediaServerPending.isEmpty())
        val roundTrip = decodePlaylistSyncState(gson, encodePlaylistSyncState(gson, s.withMediaServerPending(listOf(MediaServerPendingOp("delete", "jellyfin|m|u")))))
        assertEquals(1, roundTrip.mediaServerPending.size)
        // an unreadable media log is never replayed and never crashes the engine
        assertTrue(decodePlaylistSyncState(gson, """{"revision":1,"mediaServerPendingJson":"not json"}""").mediaServerPending.isEmpty())
    }

    @Test
    fun theSharedKeyBuilderDelegatesToTheFeature() {
        assertEquals("jellyfin|abc|def", PlaylistKey.mediaServer("jellyfin", "abc", "def"))
        assertNull(PlaylistKey.mediaServer("jellyfin", "http://x", "def"))
    }
}
