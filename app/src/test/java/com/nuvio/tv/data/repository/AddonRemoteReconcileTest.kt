package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test

/**
 * A pull must not overwrite addon edits the server never saw. Before AddonSyncMerge the reconcile
 * replaced the device's list with the server's, so addons whose push was skipped (no session) or
 * failed vanished on the next pull — and a user's whole addon set never reached their TV.
 */
class AddonRemoteReconcileTest {

    private val cinemeta = "https://v3-cinemeta.strem.io"
    private val opensubs = "https://opensubtitles-v3.strem.io"
    private val torrentio = "https://torrentio.test/config"

    private class Harness(
        val repository: AddonRepositoryImpl,
        val preferences: AddonPreferences,
        val syncService: AddonSyncService,
    )

    private fun newHarness(local: List<String>, lastSynced: List<String>): Harness {
        val preferences = mockk<AddonPreferences>(relaxed = true)
        every { preferences.installedAddonUrls } returns flowOf(local)
        every { preferences.userSetNames } returns flowOf(emptyMap())
        every { preferences.addonEnabledStates } returns flowOf(emptyMap())
        every { preferences.readsPrimaryAddons() } returns false
        coEvery { preferences.getSyncedAddonUrlsOrDefaults() } returns lastSynced
        coEvery { preferences.setAddonOrder(any()) } returns true
        val syncService = mockk<AddonSyncService>()
        coEvery { syncService.pushToRemote() } returns Result.success(Unit)
        val repository = AddonRepositoryImpl(
            api = mockk<AddonApi>(relaxed = true),
            preferences = preferences,
            addonSyncService = syncService,
            authManager = mockk<AuthManager>(relaxed = true),
            context = mockk<Context>(relaxed = true),
            dispatcher = UnconfinedTestDispatcher(),
            clock = { 0L },
        )
        return Harness(repository, preferences, syncService)
    }

    @Test
    fun anAddonTheServerNeverSawSurvivesAnEmptyServerAndIsPushed() = runBlocking {
        // Fresh TV: defaults plus one addon the user installed before its push could land.
        val harness = newHarness(local = listOf(torrentio, cinemeta), lastSynced = listOf(cinemeta, opensubs))

        harness.repository.reconcileWithRemoteAddonUrls(remoteUrls = emptyList(), removeMissingLocal = true)

        coVerify { harness.preferences.setAddonOrder(listOf(torrentio)) }
        coVerify(exactly = 1) { harness.syncService.pushToRemote() }
    }

    @Test
    fun withNoLocalEditsAnEmptyServerStillClearsTheDevice() = runBlocking {
        val harness = newHarness(local = listOf(cinemeta, torrentio), lastSynced = listOf(cinemeta, torrentio))

        harness.repository.reconcileWithRemoteAddonUrls(remoteUrls = emptyList(), removeMissingLocal = true)

        coVerify { harness.preferences.setAddonOrder(emptyList()) }
        coVerify { harness.preferences.setSyncedAddonUrls(emptyList()) }
        coVerify(exactly = 0) { harness.syncService.pushToRemote() }
    }
}
