package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.JsonParser
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B24 §1 (TV) — forward-compat preservation through the ACTUAL persist -> reload path, not just the
 * [mergeXtreamAccountsJson] helper. A newer build may have written per-row keys this build's schema
 * does not know; a known-field edit here must not strip them. Drives the real DataStore (a temp-file
 * [PreferenceDataStoreFactory], the same harness as [XtreamAccountStoreProfileTest]) and inspects the
 * raw persisted blob, because unknown keys are stripped on decode into [XtreamAccount] and would be
 * invisible to a decode-only assertion.
 */
class XtreamAccountStorePreserveTest {
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

    /** Seed the raw persisted blob for [profileId] the way a NEWER build would have written it. */
    private suspend fun seedRaw(profileId: Int, raw: String) {
        storeFor(profileId).edit { it[accountsKey] = raw }
    }

    private fun storeFor(profileId: Int): DataStore<Preferences> = synchronized(stores) {
        stores[profileId] ?: PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(temporaryFolder.root, "accounts-$profileId.preferences_pb")
        }.also { stores[profileId] = it }
    }

    /** Read the raw persisted blob back off disk. */
    private suspend fun rawOf(profileId: Int): String? =
        storeFor(profileId).data.map { it[accountsKey] }.first()

    private fun rowWithUnknowns(id: String, name: String, extra: String = ""): String =
        """{"id":"$id","name":"$name","baseUrl":"http://h:8080","username":"u","password":"p"$extra,""" +
            """"futureFlag":true,"futureNested":{"x":1,"y":[2,3]}}"""

    private fun known(id: String, name: String, userAgent: String? = null) = XtreamAccount(
        id = id, name = name, baseUrl = "http://h:8080", username = "u", password = "p", userAgent = userAgent
    )

    @Test
    fun `upsert edit of a known field preserves unknown flat and nested fields on disk`() = runTest {
        seedRaw(1, "[${rowWithUnknowns("a", "Old")}]")

        subject.upsert(known("a", "New")) // edit a KNOWN field (name)

        val raw = rawOf(1)!!
        assertTrue("unknown nested object survives the edit", raw.contains("futureNested"))
        assertTrue("its nested array survives", raw.contains("\"y\""))
        assertTrue("unknown flat field survives", raw.contains("futureFlag"))
        assertEquals("the known edit actually applied", "New", subject.accountsForProfile(1).single().name)
    }

    @Test
    fun `update transform of a known field preserves unknown fields on disk`() = runTest {
        seedRaw(1, "[${rowWithUnknowns("a", "Old")}]")

        subject.update("a") { it.copy(enabled = false) }

        val raw = rawOf(1)!!
        assertTrue("unknowns survive a field-level update", raw.contains("futureNested"))
        assertTrue("unknown flat field survives a field-level update", raw.contains("futureFlag"))
        assertFalse("the toggle applied", subject.accountsForProfile(1).single().enabled)
    }

    @Test
    fun `clearing a known optional field removes its stored value and does NOT restore it`() = runTest {
        // Seeded with userAgent set AND an unknown field.
        seedRaw(1, "[${rowWithUnknowns("a", "Old", extra = ""","userAgent":"CustomUA"""")}]")

        subject.upsert(known("a", "Old", userAgent = null)) // user cleared the UA

        val raw = rawOf(1)!!
        assertFalse("a cleared known field is not restored from the original", raw.contains("CustomUA"))
        assertNull("the reloaded account has no user-agent", subject.accountsForProfile(1).single().userAgent)
        assertTrue("but a genuine unknown field is still preserved", raw.contains("futureNested"))
    }

    @Test
    fun `a removed playlist does not reappear and its unknown fields do not resurrect`() = runTest {
        seedRaw(1, "[${rowWithUnknowns("a", "Old")}]")

        subject.remove("a")

        val raw = rawOf(1)!!
        assertEquals("the store is an explicit empty array", "[]", raw)
        assertFalse("the removed row's unknown fields are gone", raw.contains("futureNested"))
        assertTrue("nothing remains", subject.accountsForProfile(1).isEmpty())
    }

    @Test
    fun `unknown fields are matched to the original row by id not by position`() = runTest {
        seedRaw(
            1,
            "[${rowWithUnknowns("a", "A")},${rowWithUnknowns("b", "B")}]"
        )

        // Edit only b; a's and b's unknowns must each stay on their own row.
        subject.upsert(known("b", "B2"))

        val arr = JsonParser.parseString(rawOf(1)!!).asJsonArray
        val byId = arr.associate { it.asJsonObject.get("id").asString to it.asJsonObject }
        assertTrue("row a keeps its unknown", byId["a"]!!.has("futureNested"))
        assertTrue("row b keeps its unknown", byId["b"]!!.has("futureNested"))
        assertEquals("only b's known field changed", "B2", byId["b"]!!.get("name").asString)
        assertEquals("a's known field is untouched", "A", byId["a"]!!.get("name").asString)
    }

    @Test
    fun `preservation is profile-scoped — editing one profile leaves another profile's blob intact`() = runTest {
        seedRaw(1, "[${rowWithUnknowns("a", "P1")}]")
        seedRaw(2, "[${rowWithUnknowns("a", "P2")}]")

        activeProfileId.value = 1
        subject.upsert(known("a", "P1-edited"))

        val rawTwo = rawOf(2)!!
        assertTrue("profile 2's unknown is untouched", rawTwo.contains("futureNested"))
        assertEquals("profile 2's known field is untouched", "P2", subject.accountsForProfile(2).single().name)
        assertEquals("profile 1's edit applied", "P1-edited", subject.accountsForProfile(1).single().name)
    }

    @Test
    fun `a brand-new playlist with no original is encoded fresh without crashing`() = runTest {
        seedRaw(1, "[${rowWithUnknowns("a", "A")}]")

        subject.upsert(known("b", "B")) // a NEW id, no original row to merge

        val accounts = subject.accountsForProfile(1)
        assertEquals("both rows persist", 2, accounts.size)
        assertTrue("the new row is present", accounts.any { it.id == "b" })
        assertTrue("the pre-existing row still carries its unknown", rawOf(1)!!.contains("futureNested"))
    }
}
