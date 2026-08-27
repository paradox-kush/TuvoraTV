package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XtreamLiveStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val activeProfileId = MutableStateFlow(1)
    private val dataStoreJob: Job = SupervisorJob()
    private val dataStoreScope = CoroutineScope(dataStoreJob + Dispatchers.IO)
    private lateinit var subject: XtreamLiveStore

    @Before
    fun setUp() {
        val stores = mutableMapOf<Int, DataStore<Preferences>>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            val profileId = firstArg<Int>()
            synchronized(stores) {
                stores[profileId] ?: PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                    File(temporaryFolder.root, "live-$profileId.preferences_pb")
                }.also { store ->
                    stores[profileId] = store
                }
            }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        subject = XtreamLiveStore(factory, profileManager)
    }

    @After
    fun tearDown() {
        runBlocking { dataStoreJob.cancelAndJoin() }
    }

    @Test
    fun `identity history is profile scoped`() = runTest {
        subject.recordPlayedIdentity("profile-one", "One", null)
        assertEquals("profile-one", subject.recents.first { it.isNotEmpty() }.single().id)

        activeProfileId.value = 2
        assertEquals(emptyList<LiveChannelRef>(), subject.recents.first())
        subject.recordPlayedIdentity("profile-two", "Two", null)
        assertEquals("profile-two", subject.recents.first { it.isNotEmpty() }.single().id)

        activeProfileId.value = 1
        assertEquals("profile-one", subject.recents.first { it.isNotEmpty() }.single().id)
    }

    @Test
    fun `identity upsert preserves only an existing legacy URL and blank is never replayable`() = runTest {
        subject.remember(
            LiveChannelRef(
                id = "legacy",
                name = "Old",
                logo = null,
                streamUrl = "https://legacy.invalid/live",
            ),
        )
        subject.recordPlayedIdentity("legacy", "Updated", "logo.png")
        subject.recordPlayedIdentity("clean", "Clean", null)

        val rows = subject.recents.first { it.size == 2 }.associateBy(LiveChannelRef::id)
        assertEquals("https://legacy.invalid/live", rows.getValue("legacy").streamUrl)
        assertEquals("Updated", rows.getValue("legacy").name)
        assertEquals("logo.png", rows.getValue("legacy").logo)
        assertEquals("", rows.getValue("clean").streamUrl)
        awaitMirror("legacy", "clean")
        assertEquals("https://legacy.invalid/live", subject.urlFor("legacy"))
        assertNull(subject.urlFor("clean"))
    }

    @Test
    fun `explicit profile identity write never follows active profile`() = runTest {
        activeProfileId.value = 1
        subject.recordPlayedIdentityForProfile(2, "profile-two", "Two", null)

        assertEquals(emptyList<LiveChannelRef>(), subject.recents.first())
        activeProfileId.value = 2
        assertEquals("profile-two", subject.recents.first { it.isNotEmpty() }.single().id)
    }

    @Test
    fun `explicit profile identity read never follows active profile or exposes transport`() = runTest {
        activeProfileId.value = 1
        subject.recordPlayedIdentityForProfile(2, "profile-two", "Two", "logo.png")

        assertNull(subject.identityForProfile(1, "profile-two"))
        val identity = subject.identityForProfile(2, "profile-two")
        assertEquals("profile-two", identity?.contentId)
        assertEquals("Two", identity?.title)
        assertEquals("logo.png", identity?.logo)
        assertFalse(
            StoredLiveChannelIdentity::class.java.declaredFields.any {
                it.name.contains("url", ignoreCase = true) ||
                    it.name.contains("stream", ignoreCase = true)
            },
        )
        assertFalse(identity.toString().contains("profile-two"))
        assertFalse(identity.toString().contains("Two"))
    }

    private suspend fun awaitMirror(vararg ids: String) {
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (ids.any { subject.refFor(it) == null }) delay(10)
            }
        }
    }
}
