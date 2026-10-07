package com.nuvio.tv.core.mediaserver.flow

import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.FakeClient
import com.nuvio.tv.core.mediaserver.MediaServerAccounts
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.client.HealthStatus
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerStatusPolicyTest {
    private val e = entry()

    @Test
    fun theBadgeFollowsTheTokenThenTheLastHealthCheck() {
        assertEquals(ServerStatus.SIGNED_IN, MediaServerStatusPolicy.of(e, signedIn = true, expired = false, health = null))
        assertEquals(ServerStatus.SIGNED_IN, MediaServerStatusPolicy.of(e, true, false, HealthStatus.ONLINE))
        assertEquals(ServerStatus.OFFLINE, MediaServerStatusPolicy.of(e, true, false, HealthStatus.OFFLINE))
        assertEquals("a 401 recorded by any call", ServerStatus.SIGN_IN_AGAIN, MediaServerStatusPolicy.of(e, false, true, null))
        assertEquals(ServerStatus.SIGN_IN_AGAIN, MediaServerStatusPolicy.of(e, true, false, HealthStatus.AUTH_ERROR))
        assertEquals("an entry synced from another device", ServerStatus.NEEDS_SIGN_IN, MediaServerStatusPolicy.of(e, false, false, null))
        assertEquals("a token but no address on this device", ServerStatus.NEEDS_SIGN_IN, MediaServerStatusPolicy.of(entry(address = null), true, false, null))
        assertEquals("off wins", ServerStatus.DISABLED, MediaServerStatusPolicy.of(e.copy(enabled = false), true, true, HealthStatus.OFFLINE))
    }

    @Test
    fun onlyASignedInEnabledEntryWithAnAddressIsEverChecked() {
        assertTrue(MediaServerStatusPolicy.canCheckHealth(e, signedIn = true))
        assertFalse(MediaServerStatusPolicy.canCheckHealth(e, signedIn = false))
        assertFalse(MediaServerStatusPolicy.canCheckHealth(e.copy(enabled = false), true))
        assertFalse(MediaServerStatusPolicy.canCheckHealth(entry(address = null), true))
    }
}

class MediaServerManagementTest {
    private val fake = FakeClient()
    private fun rig() = TestRig(clientFactory = { fake }).also { it.store.ensureLoaded() }

    private fun signedIn(rig: TestRig, onChanged: MutableList<String> = mutableListOf()): Pair<com.nuvio.tv.core.mediaserver.api.MediaServerEntry, MediaServerManagement> {
        val e = entry()
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
        return e to MediaServerManagement(MediaServerAccounts(rig.store, rig.services, 1..2), rig.services, { onChanged += it.key })
    }

    @Test
    fun removingAServerLogsTheTokenOutOnTheServerFirstThenForgetsEverythingLocally() = runTest {
        val rig = rig()
        val changed = mutableListOf<String>()
        val (e, management) = signedIn(rig, changed)
        assertTrue(management.remove(e, purgeSavedData = false))
        assertEquals("POST /Sessions/Logout was sent while the token still existed", 1, fake.loggedOut)
        assertNull(rig.credentials.token(e.serverKey))
        assertTrue(rig.store.current().isEmpty())
        assertEquals(listOf(e.key), changed)
    }

    @Test
    fun anUnreachableServerNeverBlocksTheUsersOwnRemoval() = runTest {
        val rig = rig()
        val (e, management) = signedIn(rig)
        fake.failWith = MediaServerException.Unreachable("down")
        assertTrue("best effort: the removal still completes", management.remove(e, purgeSavedData = true))
        assertTrue(rig.store.current().isEmpty())
        assertNull(rig.credentials.token(e.serverKey))
    }

    @Test
    fun signingOutKeepsTheEntryAsSignInAndEndsTheServerSession() = runTest {
        val rig = rig()
        val (e, management) = signedIn(rig)
        management.signOut(e)
        assertEquals(1, fake.loggedOut)
        assertFalse(rig.services.isSignedIn(e))
        assertEquals("the entry stays and keeps syncing", 1, rig.store.current().size)
    }

    @Test
    fun homeRowsAreSwitchedOnePerRowAndTellTheHomeSurfaces() = runTest {
        val rig = rig()
        val changed = mutableListOf<String>()
        val (e, management) = signedIn(rig, changed)
        assertTrue(management.setHomeRow(e, MediaServerHomeRow.NEXT_UP, true))
        assertTrue(management.setHomeRow(rig.store.current().single(), MediaServerHomeRow.RECENTLY_ADDED, true))
        assertEquals(setOf(MediaServerHomeRow.NEXT_UP, MediaServerHomeRow.RECENTLY_ADDED), rig.store.current().single().homeRows)
        assertTrue(management.setHomeRow(rig.store.current().single(), MediaServerHomeRow.NEXT_UP, false))
        assertEquals(setOf(MediaServerHomeRow.RECENTLY_ADDED), rig.store.current().single().homeRows)
        assertEquals(3, changed.size)
    }

    @Test
    fun theAddressSyncToggleAndRenameGoThroughTheAccountOperations() = runTest {
        val rig = rig()
        val (e, management) = signedIn(rig)
        assertTrue(management.setSyncAddress(e, false))
        assertFalse(rig.store.current().single().syncAddress)
        assertTrue(management.rename(rig.store.current().single(), "  Den  "))
        assertEquals("Den", rig.store.current().single().name)
        assertFalse("a blank name is refused", management.rename(rig.store.current().single(), "   "))
    }
}
