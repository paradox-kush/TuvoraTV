package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.AddonSyncService
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression: on a first launch that hit a bad moment (cold network, a slow add-on timing out) an
 * add-on whose manifest failed stayed a catalog-less placeholder until something else happened to
 * recompute the installed list - in practice until the app was restarted - so Home showed only
 * Continue Watching. Now the failed manifest is fetched again on its own, on the short bounded
 * ladder in [com.nuvio.tv.core.addons.AddonLoadRetryPolicy], and then only when Home is visited.
 *
 * The dispatcher is a test dispatcher, so the 2 s / 8 s ladder runs on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonManifestRetryTest {

    private val addonUrl = "https://addon.example"

    @Test
    fun `a manifest that failed on launch is fetched again on its own`() = runTest {
        val harness = newRepository(failFirst = 1)

        val resolved = withTimeout(60_000) {
            harness.repository.getInstalledAddons().first { list -> list.singleOrNull()?.version == "1.0.0" }
        }.single()

        assertEquals("Test Addon", resolved.name)
        assertEquals("one failure, one automatic retry, nothing more", 2, harness.manifestCalls.get())
    }

    @Test
    fun `a dead manifest is retried on the short ladder and then left alone`() = runTest {
        val harness = newRepository(failFirst = Int.MAX_VALUE)
        harness.repository.getInstalledAddons().first { it.isNotEmpty() }

        advanceTimeBy(10 * 60_000L)
        advanceUntilIdle()

        assertEquals("the launch fetch plus two ladder retries, never a poll", 3, harness.manifestCalls.get())
    }

    @Test
    fun `a Home visit retries a manifest the ladder gave up on`() = runTest {
        val harness = newRepository(failFirst = 3)
        harness.repository.getInstalledAddons().first { it.isNotEmpty() }
        advanceTimeBy(60_000L)
        advanceUntilIdle()
        assertEquals("launch fetch plus the two-step ladder", 3, harness.manifestCalls.get())
        assertEquals(
            "once the ladder gives up, Home is told",
            setOf(addonUrl),
            harness.repository.unresolvedManifestFailures().first()
        )

        harness.repository.retryFailedManifests()
        advanceUntilIdle()

        assertEquals("one visit, one fetch", 4, harness.manifestCalls.get())
        assertEquals(
            "the recovered manifest replaces the placeholder",
            "1.0.0",
            harness.repository.getInstalledAddons().first().single().version
        )
        assertEquals(
            "nothing left to report",
            emptySet<String>(),
            harness.repository.unresolvedManifestFailures().first()
        )
    }

    @Test
    fun `a Home visit makes no request when no manifest failed`() = runTest {
        val harness = newRepository(failFirst = 0)
        harness.repository.getInstalledAddons().first { list -> list.singleOrNull()?.version == "1.0.0" }
        val before = harness.manifestCalls.get()

        harness.repository.retryFailedManifests()
        advanceTimeBy(60_000L)
        advanceUntilIdle()

        assertEquals("an idle Home visit must not touch the network", before, harness.manifestCalls.get())
        assertEquals("no failure to report", emptySet<String>(), harness.repository.unresolvedManifestFailures().first())
    }

    @Test
    fun `failures are not reported while the automatic ladder still owns them`() = runTest {
        val harness = newRepository(failFirst = Int.MAX_VALUE)
        harness.repository.getInstalledAddons().first { it.isNotEmpty() }

        assertEquals(
            "the ladder is still retrying, so no card yet",
            emptySet<String>(),
            harness.repository.unresolvedManifestFailures().first()
        )
    }

    private class Harness(val repository: AddonRepositoryImpl, val manifestCalls: AtomicInteger)

    private fun TestScope.newRepository(failFirst: Int): Harness {
        val manifestCalls = AtomicInteger()
        val api = mockk<AddonApi>()
        coEvery { api.getManifest(any()) } coAnswers {
            if (manifestCalls.incrementAndGet() <= failFirst) throw IOException("timeout")
            Response.success(AddonManifestDto(id = "test-addon", name = "Test Addon", version = "1.0.0"))
        }
        val preferences = mockk<AddonPreferences>()
        every { preferences.installedAddonUrls } returns flowOf(listOf(addonUrl))
        every { preferences.userSetNames } returns flowOf(emptyMap())
        every { preferences.addonEnabledStates } returns flowOf(emptyMap())

        return Harness(
            repository = AddonRepositoryImpl(
                api = api,
                preferences = preferences,
                addonSyncService = mockk<AddonSyncService>(relaxed = true),
                authManager = mockk<AuthManager>(relaxed = true),
                context = mockk<Context>(relaxed = true),
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                clock = { 0L },
            ),
            manifestCalls = manifestCalls,
        )
    }
}
