package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B24 §2 — the outgoing push must be built from ONE atomic read of the TARGET profile's blob, so a
 * profile switch cannot redirect the payload and a concurrent edit cannot desync the authority
 * decision from the accounts. Drives the real DataStore ([XtreamAccountStore.pushSnapshot]).
 */
class XtreamAccountPushSnapshotTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val activeProfileId = MutableStateFlow(1)
    private val dataStoreJob: Job = SupervisorJob()
    private val dataStoreScope = CoroutineScope(dataStoreJob + Dispatchers.IO)
    private val accountsKey = stringPreferencesKey("xtream_accounts")
    private val stores = mutableMapOf<Int, DataStore<Preferences>>()
    private lateinit var subject: XtreamAccountStore

    @Before
    fun setUp() {
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            val profileId = firstArg<Int>()
            synchronized(stores) {
                stores[profileId] ?: PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                    File(temporaryFolder.root, "accounts-$profileId.preferences_pb")
                }.also { store -> stores[profileId] = store }
            }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        subject = XtreamAccountStore(factory, profileManager)
    }

    @After
    fun tearDown() {
        runBlocking { dataStoreJob.cancelAndJoin() }
    }

    private fun storeFor(profileId: Int): DataStore<Preferences> = synchronized(stores) {
        stores[profileId] ?: PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(temporaryFolder.root, "accounts-$profileId.preferences_pb")
        }.also { stores[profileId] = it }
    }

    private suspend fun seedRaw(profileId: Int, raw: String) {
        storeFor(profileId).edit { it[accountsKey] = raw }
    }

    private fun row(id: String) =
        """{"id":"$id","name":"P","baseUrl":"http://h:8080","username":"u","password":"p"}"""

    @Test
    fun `the snapshot reads authority and accounts from the SAME profile blob`() = runTest {
        seedRaw(1, "[${row("a")},${row("b")}]")
        val snap = subject.pushSnapshot(1)
        assertTrue("a clean array is authoritative", snap.canFullReplace)
        assertEquals("payload matches the blob", listOf("a", "b"), snap.accounts.map { it.id })
        assertEquals("targets the requested profile", 1, snap.profileId)
    }

    @Test
    fun `the snapshot targets the requested profile — not the active one — so a switch cannot redirect it`() = runTest {
        seedRaw(1, "[${row("a")}]")     // profile 1 has one playlist
        seedRaw(2, "[${row("x")},${row("y")}]") // profile 2 has two

        // A push was queued for profile 1; the user then switched the active profile to 2.
        activeProfileId.value = 2
        val snap = subject.pushSnapshot(1)

        assertEquals("payload is profile 1's, not the now-active profile 2's", listOf("a"), snap.accounts.map { it.id })
        assertEquals(1, snap.profileId)
    }

    @Test
    fun `a captured snapshot is immutable — a later edit cannot change a push already in flight`() = runTest {
        seedRaw(1, "[${row("a")},${row("b")}]")
        val snap = subject.pushSnapshot(1)

        // Simulate an edit landing during the (would-be) network call.
        subject.remove("a")

        assertEquals("the captured payload is unchanged by the concurrent edit", listOf("a", "b"), snap.accounts.map { it.id })
        // and a fresh snapshot would of course see the new state
        assertEquals(listOf("b"), subject.pushSnapshot(1).accounts.map { it.id })
    }

    @Test
    fun `an absent profile blob yields a non-authoritative snapshot (withheld)`() = runTest {
        // profile 1 never written (fresh install / corruption-reset writes an EMPTY store, not [])
        val snap = subject.pushSnapshot(1)
        assertFalse("absent must not full-replace", snap.canFullReplace)
        assertTrue("no accounts to send", snap.accounts.isEmpty())
    }

    @Test
    fun `a corrupt profile blob yields a non-authoritative snapshot (withheld)`() = runTest {
        seedRaw(1, "not a json array")
        val snap = subject.pushSnapshot(1)
        assertFalse("corrupt must not full-replace", snap.canFullReplace)
    }

    @Test
    fun `an explicit empty array is authoritative — a deliberate delete-all still pushes`() = runTest {
        seedRaw(1, "[]")
        val snap = subject.pushSnapshot(1)
        assertTrue("[] is a deliberate delete-all", snap.canFullReplace)
        assertTrue(snap.accounts.isEmpty())
    }

    @Test
    fun `a partially recovered blob is not authoritative and still surfaces the good rows`() = runTest {
        seedRaw(1, "[${row("a")},42]") // one un-decodable element
        val snap = subject.pushSnapshot(1)
        assertFalse("a recovered subset must not overwrite the server", snap.canFullReplace)
        assertEquals("the good row is still decoded", listOf("a"), snap.accounts.map { it.id })
    }

    /** The account returned in a snapshot must be usable as-is for the push payload, with the
     *  Unsafe-decode defaults re-applied (a row missing the optional fields must not come back with
     *  JVM zero-values). */
    @Test
    fun `snapshot accounts decode with defaults applied`() = runTest {
        seedRaw(1, "[${row("a")}]") // omits the optional fields
        val acc: XtreamAccount = subject.pushSnapshot(1).accounts.single()
        assertEquals("sourceType default re-applied", "xtream", acc.sourceType)
        assertTrue("sendDeviceId default re-applied (not the Unsafe false)", acc.sendDeviceId)
        assertEquals("autoRefreshHours default re-applied (not the Unsafe 0)", 24, acc.autoRefreshHours)
    }
}
