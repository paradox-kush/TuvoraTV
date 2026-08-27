package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XtreamAccountStoreProfileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val activeProfileId = MutableStateFlow(1)
    private val dataStoreJob: Job = SupervisorJob()
    private val dataStoreScope = CoroutineScope(dataStoreJob + Dispatchers.IO)
    private lateinit var subject: XtreamAccountStore

    @Before
    fun setUp() {
        val stores = mutableMapOf<Int, DataStore<Preferences>>()
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

    @Test
    fun `explicit profile lookup never follows a later active profile switch`() = runTest {
        val profileTwoAccount = XtreamAccount(
            id = "profile-two-account",
            name = "Profile Two",
            baseUrl = "https://provider.invalid",
            username = "user",
            password = "password",
        )
        activeProfileId.value = 2
        subject.replaceAll(listOf(profileTwoAccount))

        activeProfileId.value = 1

        assertEquals(profileTwoAccount, subject.findForProfile(2, profileTwoAccount.id))
        assertEquals(listOf(profileTwoAccount), subject.accountsForProfile(2))
        assertNull(subject.findForProfile(1, profileTwoAccount.id))
    }
}
