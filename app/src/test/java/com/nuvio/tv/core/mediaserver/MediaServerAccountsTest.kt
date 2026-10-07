package com.nuvio.tv.core.mediaserver

import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.client.AuthSession
import com.nuvio.tv.core.mediaserver.client.ServerInfo
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.nuvio.tv.core.mediaserver.assertIs
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerAccountsTest {
    private val info = ServerInfo(M, "Living Room", "12.1.0", MediaServerType.JELLYFIN)
    private fun session(user: String = U, token: String = "TOKEN-1", name: String? = "kid") = AuthSession(token, user, name, M, false)

    private fun rig() = TestRig().also { it.store.ensureLoaded() }
    private fun accounts(rig: TestRig, profiles: Iterable<Int> = 1..2) = MediaServerAccounts(rig.store, rig.services, profiles)

    @Test
    fun addServerSavesTheCredentialInTheSecureStoreAndOnlyTheEntryInTheEntryStore() {
        val rig = rig()
        val r = assertIs<SignInResult.Success>(accounts(rig).addServer(MediaServerType.JELLYFIN, "http://nas:8096", info, session(), displayName = null))
        assertEquals("the server's own name when none is given", "Living Room", r.entry.name)
        assertEquals("jellyfin|$M|$U", r.entry.key)
        assertEquals("kid", r.entry.userName)
        assertEquals("TOKEN-1", rig.credentials.token(r.entry.serverKey))
        assertTrue(rig.services.isSignedIn(r.entry))
        assertFalse("the token never touches the entry store", rig.persistence.blobs.values.any { it.contains("TOKEN-1") })
        assertTrue(rig.secure.items.values.none { it.contains("hunter2") })
        assertEquals(listOf("add:1:${r.entry.key}"), rig.sink.events)
    }

    @Test
    fun aCustomNameIsTrimmedAndTheAddressToggleCarriesThrough() {
        val rig = rig()
        val r = assertIs<SignInResult.Success>(accounts(rig).addServer(MediaServerType.EMBY, "https://emby.example.com", info, session(), "  Den  ", syncAddress = false))
        assertEquals("Den", r.entry.name)
        assertFalse(r.entry.syncAddress)
        assertEquals(MediaServerType.EMBY, r.entry.type)
        assertEquals("emby|$M|$U", r.entry.key)
    }

    @Test
    fun addingTheSameServerAndUserAgainJustSignsTheExistingEntryIn() {
        val rig = rig()
        val a = accounts(rig)
        a.addServer(MediaServerType.JELLYFIN, "http://nas:8096", info, session(), "Home")
        val again = assertIs<SignInResult.Success>(a.addServer(MediaServerType.JELLYFIN, "http://192.168.1.5:8096", info, session(token = "TOKEN-2"), "Other"))
        assertEquals(1, rig.store.current().size)
        assertEquals("the existing entry keeps its name", "Home", again.entry.name)
        assertEquals("...and takes the address just verified", "http://192.168.1.5:8096", again.entry.address)
        assertEquals("TOKEN-2", rig.credentials.token("jellyfin:$M:$U"))
    }

    @Test
    fun aServerWhoseIdsCannotFormAKeyIsRefusedBeforeAnythingIsStored() {
        val rig = rig()
        val bad = info.copy(machineId = "has space")
        assertEquals(SignInResult.UnusableServerIds, accounts(rig).addServer(MediaServerType.JELLYFIN, "http://nas", bad, session(), null))
        assertTrue(rig.secure.items.isEmpty() && rig.store.current().isEmpty())
    }

    @Test
    fun aFailedEntryWriteLeavesNoOrphanToken() {
        val rig = rig()
        rig.persistence.failWrites = true
        assertEquals(SignInResult.NotSaved, accounts(rig).addServer(MediaServerType.JELLYFIN, "http://nas", info, session(), null))
        assertNull("no token for an entry that does not exist", rig.credentials.token("jellyfin:$M:$U"))
    }

    @Test
    fun aFailedKeychainWriteLeavesNoEntryThatLooksSignedIn() {
        val rig = rig()
        rig.secure.failWrites = true
        assertEquals(SignInResult.NotSaved, accounts(rig).addServer(MediaServerType.JELLYFIN, "http://nas", info, session(), null))
        assertTrue(rig.store.current().isEmpty())
    }

    @Test
    fun anEntryFromAnotherDeviceSignsInHereWithTheSameUser() {
        val rig = rig()
        val pulled = entry(address = null)
        rig.store.applyFromRemote(1, listOf(pulled))
        assertFalse("arrives as 'Sign in to ...'", rig.services.isSignedIn(pulled))
        val r = assertIs<SignInResult.Success>(accounts(rig).signIn(pulled, "http://192.168.1.5:8096", M, session()))
        assertEquals("http://192.168.1.5:8096", r.entry.address)
        assertTrue(rig.services.isSignedIn(r.entry))
        assertEquals(1, rig.store.current().size)
    }

    @Test
    fun signingInAsAnotherUserReKeysTheUserAndDropsTheOldToken() {
        val rig = rig()
        val pulled = entry(address = "http://nas:8096")
        rig.store.applyFromRemote(1, listOf(pulled))
        rig.credentials.save(pulled.serverKey, com.nuvio.tv.core.mediaserver.store.StoredCredential("OLD"))
        val r = assertIs<SignInResult.Success>(accounts(rig).signIn(pulled, "http://nas:8096", M, session(user = "u2", token = "NEW", name = "dad")))
        assertEquals(pulled.key, r.entry.key)
        assertEquals("u2", r.entry.userId)
        assertEquals("NEW", rig.credentials.token("jellyfin:$M:u2"))
        assertNull("the previous user's token is gone from this device", rig.credentials.token(pulled.serverKey))
        assertEquals(rig.migrations, listOf<Pair<String, String?>>("ms:jellyfin:$M:$U:" to "ms:jellyfin:$M:u2:"))
    }

    @Test
    fun aDifferentServerIdIsNeverSilentlyRePointed() {
        val rig = rig()
        val pulled = entry()
        rig.store.applyFromRemote(1, listOf(pulled))
        assertEquals(SignInResult.DifferentServer, accounts(rig).signIn(pulled, "http://elsewhere:8096", "another-server-id", session()))
        assertTrue("nothing saved for the other server", rig.secure.items.isEmpty())
    }

    @Test
    fun signOutKeepsTheEntryButDropsTheToken() {
        val rig = rig()
        val e = (accounts(rig).addServer(MediaServerType.JELLYFIN, "http://nas:8096", info, session(), null) as SignInResult.Success).entry
        accounts(rig).signOut(e)
        assertFalse(rig.services.isSignedIn(e))
        assertEquals(1, rig.store.current().size)
    }

    @Test
    fun removeKeepsTheTokenWhileAnotherProfileStillUsesTheServer() {
        val rig = rig()
        val a = accounts(rig)
        val e = (a.addServer(MediaServerType.JELLYFIN, "http://nas:8096", info, session(), null) as SignInResult.Success).entry
        rig.profile = 2; rig.store.onProfileChanged(2); rig.store.add(e) // profile 2 has the same server+user
        assertTrue(a.remove(e, purgeSavedData = false))
        assertTrue("profile 1 still has it - the token stays", rig.services.isSignedIn(e))
        rig.profile = 1; rig.store.onProfileChanged(1)
        assertTrue(a.remove(e, purgeSavedData = true))
        assertFalse("the last reference is gone - the token goes", rig.services.isSignedIn(e))
    }

    @Test
    fun editsGoThroughTheStoreSoTheyReachTheSyncIntentLog() {
        val rig = rig()
        val a = accounts(rig)
        val e = (a.addServer(MediaServerType.JELLYFIN, "http://nas:8096", info, session(), null) as SignInResult.Success).entry
        assertTrue(a.rename(e, "  Den "))
        assertFalse(a.rename(e, "   "))
        assertTrue(a.setEnabled(e, false))
        assertTrue(a.setSyncAddress(e, false))
        assertTrue(a.setAddress(e, "http://10.0.0.2:8096"))
        assertTrue(a.setHomeRows(e, setOf(MediaServerHomeRow.RECENTLY_ADDED)))
        val now = rig.store.entryByKey(e.key)!!
        assertEquals("Den", now.name); assertFalse(now.enabled); assertFalse(now.syncAddress)
        assertEquals("http://10.0.0.2:8096", now.address)
        assertEquals(setOf(MediaServerHomeRow.RECENTLY_ADDED), now.homeRows)
        assertEquals(5, rig.sink.updated.size)
    }
}
