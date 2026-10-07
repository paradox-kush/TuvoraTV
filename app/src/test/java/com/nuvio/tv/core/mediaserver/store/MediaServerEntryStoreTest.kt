package com.nuvio.tv.core.mediaserver.store

import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.entry
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.nuvio.tv.core.mediaserver.assertIs
import com.nuvio.tv.core.mediaserver.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerEntryStoreTest {
    @Test
    fun addPersistsRecordsTheSyncIntentAndRejectsADuplicate() {
        val rig = TestRig()
        val e = entry()
        assertEquals(AddResult.Added, rig.store.add(e))
        assertEquals(listOf(e), rig.store.current())
        assertEquals(listOf("add:1:${e.key}"), rig.sink.events)
        assertIs<AddResult.Duplicate>(rig.store.add(e.copy(name = "Other")))
        assertEquals(1, rig.store.current().size)
        assertEquals("a refused add records nothing", 1, rig.sink.events.size)
    }

    @Test
    fun theStoreSurvivesARestartAndNeverPersistsACredentialField() {
        val rig = TestRig()
        rig.store.add(entry().copy(userName = "kid", homeRows = setOf(MediaServerHomeRow.NEXT_UP)))
        val raw = rig.persistence.blobs.getValue(1)
        assertFalse(raw, raw.contains("token", ignoreCase = true) || raw.contains("password", ignoreCase = true))
        val second = MediaServerEntryStore(rig.persistence, { null }, { 1 }, { _, _ -> })
        assertEquals("kid", second.current().single().userName)
        assertEquals(setOf(MediaServerHomeRow.NEXT_UP), second.current().single().homeRows)
        assertTrue(second.canPushFullReplace())
    }

    @Test
    fun aFailedWriteReportsFailureRestoresTheListAndRecordsNothing() {
        val rig = TestRig()
        rig.persistence.failWrites = true
        assertEquals(AddResult.NotSaved, rig.store.add(entry()))
        assertTrue(rig.store.current().isEmpty())
        assertTrue(rig.sink.events.isEmpty())
        rig.persistence.failWrites = false
        rig.store.add(entry())
        rig.persistence.failWrites = true
        assertFalse(rig.store.update(entry().key) { it.copy(name = "x") })
        assertEquals("an unsaved edit does not stick", "Home", rig.store.current().single().name)
        assertFalse(rig.store.remove(entry().key, purgeSavedData = false))
        assertEquals(1, rig.store.current().size)
    }

    @Test
    fun updateKeepsTheFrozenKeyAndRecordsTheBase() {
        val rig = TestRig()
        val e = entry()
        rig.store.add(e)
        assertTrue(rig.store.update(e.key) { it.copy(name = "Renamed", key = "jellyfin|hacked|key") })
        assertEquals("Renamed", rig.store.entryByKey(e.key)?.name)
        assertEquals(e.key, rig.store.current().single().key)
        val (updated, base) = rig.sink.updated.single()
        assertEquals("Renamed", updated.name)
        assertEquals(e, base)
        assertTrue("an edit that changes nothing is a no-op success", rig.store.update(e.key) { it })
        assertEquals(1, rig.sink.updated.size)
        assertFalse(rig.store.update("jellyfin|nope|nope") { it })
    }

    @Test
    fun removePurgesSavedDataOnlyWhenAskedAndWhenNoSiblingEntryShareTheUser() {
        val rig = TestRig()
        val a = entry()
        val sibling = entry(userId = "u2") // same server, another user: its data is untouched
        rig.store.add(a); rig.store.add(sibling)
        assertTrue(rig.store.remove(a.key, purgeSavedData = true))
        assertEquals(rig.migrations, listOf<Pair<String, String?>>("ms:jellyfin:$M:$U:" to null))
        assertEquals(listOf(sibling), rig.store.current())
        assertTrue(rig.store.remove(sibling.key, purgeSavedData = false))
        assertEquals("no purge unless asked (a sync pull never purges)", 1, rig.migrations.size)
        assertEquals(listOf("delete:1:${a.key}", "delete:1:${sibling.key}"), rig.sink.events.filter { it.startsWith("delete") })
    }

    @Test
    fun reLoginAsAnotherUserMovesTheSavedDataAndKeepsTheKey() {
        val rig = TestRig()
        val a = entry().copy(userName = "kid")
        rig.store.add(a)
        val r = assertIs<ReLoginResult.Updated>(rig.store.reLogin(a.key, "u2", "dad"))
        assertEquals("key frozen at creation", a.key, r.entry.key)
        assertEquals("u2", r.entry.userId)
        assertEquals("dad", r.entry.userName)
        assertEquals("jellyfin:$M:u2", r.entry.serverKey)
        assertEquals(rig.migrations, listOf<Pair<String, String?>>("ms:jellyfin:$M:$U:" to "ms:jellyfin:$M:u2:"))
        assertEquals("the sync base is the entry before the re-login", a, rig.sink.updated.last().second)
    }

    @Test
    fun reLoginAsAUserWhoAlreadyHasAnEntryFoldsTheOldEntryIntoIt() {
        val rig = TestRig()
        val a = entry(); val b = entry(userId = "u2")
        rig.store.add(a); rig.store.add(b)
        val r = assertIs<ReLoginResult.MergedInto>(rig.store.reLogin(a.key, "u2", null))
        assertEquals(b, r.entry)
        assertEquals(listOf(b), rig.store.current())
        assertEquals(rig.migrations, listOf<Pair<String, String?>>("ms:jellyfin:$M:$U:" to "ms:jellyfin:$M:u2:"))
        assertEquals(ReLoginResult.NotFound, rig.store.reLogin("jellyfin|x|y", "u3", null))
    }

    @Test
    fun reLoginAsTheSameUserOnlyRefreshesTheName() {
        val rig = TestRig()
        rig.store.add(entry())
        val r = assertIs<ReLoginResult.Updated>(rig.store.reLogin(entry().key, U, "kid"))
        assertEquals("kid", r.entry.userName)
        assertTrue(rig.migrations.isEmpty())
    }

    @Test
    fun entriesAreScopedToTheActiveProfile() {
        val rig = TestRig()
        rig.store.add(entry())
        rig.profile = 2
        rig.store.onProfileChanged(2)
        assertTrue("no cross-profile leak", rig.store.current().isEmpty())
        assertFalse("an absent store never full-replaces the server", rig.store.canPushFullReplace())
        rig.store.add(entry(machineId = "other"))
        rig.profile = 1
        rig.store.onProfileChanged(1)
        assertEquals(listOf(entry()), rig.store.current())
        assertEquals(listOf(1), rig.store.profilesReferencing(1..6, MediaServerType.JELLYFIN, M, U))
        assertEquals(listOf(2), rig.store.profilesReferencing(1..6, MediaServerType.JELLYFIN, "other", U))
    }

    @Test
    fun aDamagedBlobIsShownButNeverOverwrittenOrPushed() {
        val rig = TestRig()
        val good = entry()
        rig.persistence.blobs[1] =
            """{"version":1,"entries":[${kotlinx.serialization.json.Json.encodeToString(MediaServerEntry.serializer(), good)},{"key":"broken"}]}"""
        assertEquals("the readable subset is shown", listOf(good), rig.store.current())
        assertFalse("a truncated list must never full-replace the server", rig.store.canPushFullReplace())
        val before = rig.persistence.blobs.getValue(1)
        assertEquals(AddResult.NotSaved, rig.store.add(entry(machineId = "m2")))
        assertEquals("bytes this build cannot read are preserved", before, rig.persistence.blobs.getValue(1))
        rig.persistence.blobs[1] = "not json at all"
        rig.store.onProfileChanged(1)
        assertTrue(rig.store.current().isEmpty())
        assertFalse(rig.store.canPushFullReplace())
    }

    @Test
    fun aRemotePullHealsADamagedStoreAndKeepsDeviceLocalFields() {
        val rig = TestRig()
        rig.persistence.blobs[1] = "garbage"
        rig.store.ensureLoaded()
        val remote = entry(name = "From server")
        rig.store.applyFromRemote(1, listOf(remote))
        assertTrue("a pull is authoritative", rig.store.canPushFullReplace())
        assertEquals("From server", rig.store.current().single().name)
        assertTrue("a pull never echoes a push back", rig.sink.events.isEmpty())
        // device-local fields survive the next pull
        rig.store.update(remote.key) { it.copy(userName = "kid", homeRows = setOf(MediaServerHomeRow.CONTINUE_WATCHING)) }
        rig.store.applyFromRemote(1, listOf(remote.copy(name = "Renamed")))
        val e = rig.store.current().single()
        assertEquals("Renamed", e.name)
        assertEquals("kid", e.userName)
        assertEquals(setOf(MediaServerHomeRow.CONTINUE_WATCHING), e.homeRows)
    }

    @Test
    fun aStorageThatIsNotReadyYetIsRetriedNotCachedAsEmpty() {
        val rig = TestRig()
        rig.store.applyFromRemote(1, listOf(entry()))
        var ready = false
        val late = object : MediaServerEntriesPersistence {
            override fun load(profileId: Int): String? = if (ready) rig.persistence.load(profileId) else error("not initialised")
            override fun save(profileId: Int, json: String) = rig.persistence.save(profileId, json)
            override fun remove(profileId: Int) = rig.persistence.remove(profileId)
        }
        val store = MediaServerEntryStore(late, { null }, { 1 }, { _, _ -> })
        assertTrue(store.current().isEmpty())
        assertFalse("an unready store never pushes", store.canPushFullReplace())
        ready = true
        assertEquals("the next call loads for real (the empty answer was not cached)", listOf(entry()), store.current())
    }

    @Test
    fun clearAllWipesEveryProfile() {
        val rig = TestRig()
        rig.store.add(entry())
        rig.profile = 2; rig.store.onProfileChanged(2); rig.store.add(entry(machineId = "o"))
        rig.store.clearAll(1..6)
        assertTrue(rig.persistence.blobs.isEmpty())
        assertTrue(rig.store.current().isEmpty())
        assertNull(rig.store.entryByServerKey("jellyfin:$M:$U"))
        assertNotNull(rig.store) // still usable
    }
}
