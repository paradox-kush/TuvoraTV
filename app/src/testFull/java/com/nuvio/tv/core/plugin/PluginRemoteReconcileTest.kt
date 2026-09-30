package com.nuvio.tv.core.plugin

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.PluginSyncService
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.domain.model.PluginRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A pull must not overwrite plugin repo edits the server never saw (B79, parity with addons). Before
 * AddonSyncMerge the reconcile deleted every local repo missing from the server, so a repo added
 * while its push couldn't land (signed out, a token gap, offline) vanished on the next pull.
 */
class PluginRemoteReconcileTest {

    private val repoA = PluginRepository(id = "a", name = "A", url = "https://plugins.test/a/manifest.json")

    private class Harness(
        val manager: PluginManager,
        val repos: MutableStateFlow<List<PluginRepository>>,
        val dataStore: PluginDataStore,
        val syncService: PluginSyncService,
    )

    private fun newHarness(local: List<PluginRepository>, lastSynced: List<String>?): Harness {
        val repos = MutableStateFlow(local)
        val dataStore = mockk<PluginDataStore>(relaxed = true)
        every { dataStore.repositories } returns repos
        every { dataStore.scrapers } returns flowOf(emptyList())
        every { dataStore.readsPrimaryPlugins() } returns false
        coEvery { dataStore.getSyncedRepositoryUrls() } returns lastSynced
        coEvery { dataStore.removeRepository(any()) } answers {
            val id = firstArg<String>()
            repos.value = repos.value.filterNot { it.id == id }
        }
        coEvery { dataStore.saveRepositories(any()) } answers { repos.value = firstArg() }
        val syncService = mockk<PluginSyncService>()
        coEvery { syncService.pushToRemote() } returns Result.success(Unit)
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns true
        val manager = PluginManager(
            dataStore = dataStore,
            runtime = mockk(relaxed = true),
            pluginSyncService = syncService,
            authManager = authManager,
            externalRepoParser = mockk(relaxed = true),
            externalExtensionLoader = mockk(relaxed = true),
            externalExtensionRunner = mockk(relaxed = true),
        )
        return Harness(manager, repos, dataStore, syncService)
    }

    /** Mirrors the callers: reconcile under isSyncingFromRemote, then flush the deferred push. */
    private suspend fun Harness.pull(remoteUrls: List<String>) {
        manager.isSyncingFromRemote = true
        try {
            manager.reconcileWithRemoteRepoUrls(remoteUrls = remoteUrls, removeMissingLocal = true)
        } finally {
            manager.isSyncingFromRemote = false
            manager.flushPendingSync()
        }
    }

    @Test
    fun aRepoTheServerNeverSawSurvivesAnEmptyServerAndIsPushed() = runBlocking {
        val harness = newHarness(local = listOf(repoA), lastSynced = null)

        harness.pull(remoteUrls = emptyList())

        assertEquals("the unsynced repo is kept", listOf(repoA), harness.repos.value)
        coVerify(exactly = 0) { harness.dataStore.removeRepository(any()) }
        coVerify(timeout = 3_000, exactly = 1) { harness.syncService.pushToRemote() }
    }

    @Test
    fun withNoLocalEditsAnEmptyServerStillClearsTheDevice() = runBlocking {
        val harness = newHarness(local = listOf(repoA), lastSynced = listOf(repoA.url))

        harness.pull(remoteUrls = emptyList())

        assertEquals("the server's delete-all sticks", emptyList<PluginRepository>(), harness.repos.value)
        coVerify { harness.dataStore.setSyncedRepositoryUrls(emptyList()) }
    }
}
