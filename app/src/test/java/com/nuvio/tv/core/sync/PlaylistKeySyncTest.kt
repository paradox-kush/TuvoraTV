package com.nuvio.tv.core.sync

import com.nuvio.tv.core.iptv.PlaylistKeyAdoption
import com.nuvio.tv.core.iptv.PlaylistPullResponse
import com.nuvio.tv.core.iptv.PlaylistPushResponse
import com.nuvio.tv.core.iptv.PlaylistSyncOutcome
import com.nuvio.tv.core.iptv.PlaylistSyncState
import com.nuvio.tv.core.iptv.PlaylistSyncTransport
import com.nuvio.tv.core.iptv.PlaylistV2SyncEngine
import com.nuvio.tv.core.iptv.PulledPlaylist
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.asEditOf
import com.nuvio.tv.core.iptv.identity.IptvIdentity
import com.nuvio.tv.core.iptv.pairingPayloadToXtreamAccount
import com.nuvio.tv.core.iptv.xtreamAccountFromFields
import com.nuvio.tv.data.remote.supabase.SupabaseIptvPlaylist
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 0 (TV) — the frozen playlist id on the wire, through an edit, through pairing and a pull. */
class PlaylistKeySyncTest {

    @Test
    fun `an address edit keeps the playlist id and with it the hidden channel's overlay key`() {
        // THE regression: a provider moving domains re-keyed the playlist, orphaning hidden channels
        // (overlay keys hash the playlist id), favourites and progress.
        val old = XtreamAccount(
            id = "http://old.example:8080|u", name = "P", baseUrl = "http://old.example:8080", username = "u", password = "p",
            backupUrls = listOf("http://backup.example"),
        )
        val candidate = xtreamAccountFromFields("https://new.example", "u2", "p2", "P")!!
        val saved = candidate.asEditOf(old)
        assertEquals("the id is frozen across a server + username edit", old.id, saved.id)
        assertEquals("https://new.example", saved.baseUrl)
        assertEquals("u2", saved.username)
        assertEquals("the edit form does not touch the backup list", listOf("http://backup.example"), saved.backupUrls)
        assertEquals(
            "the hidden channel's overlay key is unchanged",
            IptvIdentity.entityId(old.id, "BBC One HD", "bbc.uk"),
            IptvIdentity.entityId(saved.id, "BBC One HD", "bbc.uk"),
        )
        assertEquals("and the server is told the same id", JsonPrimitive(old.id), playlistPushJson(saved, 0)["playlist_key"])
    }

    @Test
    fun `every push row carries playlist_key and round-trips backup_urls`() {
        val acc = XtreamAccount(id = "K", name = "P", baseUrl = "http://h", username = "u", password = "p", backupUrls = listOf("http://b1"))
        val row = playlistPushJson(acc, 0)
        assertEquals(JsonPrimitive("K"), row["playlist_key"])
        assertEquals(JsonArray(listOf(JsonPrimitive("http://b1"))), row["backup_urls"])
        assertEquals("no backups -> []", JsonArray(emptyList()), playlistPushJson(acc.copy(backupUrls = null), 0)["backup_urls"])
    }

    @Test
    fun `a pull uses playlist_key and falls back to TV's derivation without it`() {
        val keyed = SupabaseIptvPlaylist(
            playlistKey = "m3u|http://h/l.m3u?x=1", backupUrls = listOf("http://b"),
            sourceType = "m3u_url", url = "http://h/l.m3u?x=1",
        )
        val k = pulledPlaylists(listOf(keyed)).single()
        assertEquals("m3u|http://h/l.m3u?x=1", k.account.id)
        assertTrue(k.serverKeyed)
        assertEquals(listOf("http://b"), k.account.backupUrls)
        val legacy = pulledPlaylists(listOf(keyed.copy(playlistKey = null, backupUrls = null))).single()
        assertEquals("pre-Step-0 TV derivation (query stripped)", "m3u:http://h/l.m3u", legacy.account.id)
        assertFalse(legacy.serverKeyed)
    }

    @Test
    fun `pairing builds ids with the shared builder or takes the form's key`() {
        val built = pairingPayloadToXtreamAccount(buildJsonObject {
            put("source_type", "xtream"); put("base_url", "HTTP://Panel.Example.com:80/"); put("username", "u"); put("password", "p")
        })!!
        assertEquals("http://panel.example.com|u", built.id)
        val keyed = pairingPayloadToXtreamAccount(buildJsonObject {
            put("source_type", "xtream"); put("base_url", "http://panel.example.com"); put("username", "u"); put("password", "p")
            put("playlist_key", "http://panel.example.com|u")
        })!!
        assertEquals("http://panel.example.com|u", keyed.id)
    }

    @Test
    fun `a pull with a mismatched key re-keys once and the next pull is a no-op`() = runBlocking {
        val local = XtreamAccount(id = "m3u:http://h/a.m3u", name = "M", baseUrl = "http://h/a.m3u", username = "", password = "", sourceType = XtreamAccount.SOURCE_URL)
        val serverRow = local.copy(id = "m3u|http://h/a.m3u")
        var device = listOf(local)
        val rekeys = mutableListOf<PlaylistKeyAdoption.Rekey>()
        var state = PlaylistSyncState()
        val engine = PlaylistV2SyncEngine(
            transport = object : PlaylistSyncTransport {
                override suspend fun pull(profileId: Int) = PlaylistPullResponse(3, listOf(serverRow), 0, keyedIds = setOf(serverRow.id))
                override suspend fun push(
                    profileId: Int, expectedRevision: Long?, accounts: List<XtreamAccount>, deleteAll: Boolean,
                    mutationId: String, expectedGeneration: Long?,
                ): PlaylistPushResponse = error("an adopted, otherwise identical set must not push")
            },
            loadState = { state },
            saveState = { _, s -> state = s },
            currentAccounts = { device },
            canPush = { true },
            applyLocal = { _, accounts -> device = accounts },
            stillActive = { true },
            newMutationId = { "m" },
            syncedKey = { playlistPushJson(it, sortOrder = 0) },
            adoptKeys = { _, pulled, keyed ->
                val r = PlaylistKeyAdoption.resolve(pulled.map { PulledPlaylist(it, it.id in keyed) }, device)
                rekeys += r.rekeys
                device = device.map { a -> r.rekeys.firstOrNull { it.oldId == a.id }?.let { a.copy(id = it.newId) } ?: a }
                r
            },
        )
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, engine.sync(1))
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("m3u:http://h/a.m3u", "m3u|http://h/a.m3u")), rekeys)
        assertEquals(PlaylistSyncOutcome.UP_TO_DATE, engine.sync(1))
        assertEquals("the second pull re-keys nothing", 1, rekeys.size)
        assertEquals(listOf("m3u|http://h/a.m3u"), device.map { it.id })
    }
}
