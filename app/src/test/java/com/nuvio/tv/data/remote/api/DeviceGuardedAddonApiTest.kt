package com.nuvio.tv.data.remote.api

import com.nuvio.tv.core.sources.AddonInstallationRequest
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import retrofit2.Response
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class DeviceGuardedAddonApiTest {
    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
        var thrown: Throwable? = null
        try { block() } catch (error: Throwable) { thrown = error }
        assertTrue(thrown is T)
    }
    private val manifest = "https://addon.example/config/manifest.json?token=A"

    @Test fun installMayValidateItsManifestWhileDiscoveryStaysDisabled() = runTest {
        val delegate = mockk<AddonApi>()
        val response = mockk<Response<AddonManifestDto>>()
        coEvery { delegate.getManifest(manifest) } returns response
        val api = DeviceGuardedAddonApi(delegate, allowed = { false }, sourceEpoch = { 0L })
        assertFailsWith<CancellationException> { api.getManifest(manifest) }
        withContext(AddonInstallationRequest("https://addon.example/config?token=A")) {
            assertSame(response, api.getManifest(manifest))
            assertFailsWith<CancellationException> { api.getCatalog("https://addon.example/config/catalog/movie/top.json?token=A") }
            assertFailsWith<CancellationException> { api.getStreams("https://addon.example/config/stream/movie/id.json?token=A") }
            assertFailsWith<CancellationException> { api.getMeta("https://addon.example/config/meta/movie/id.json?token=A") }
            assertFailsWith<CancellationException> { api.getSubtitles("https://addon.example/config/subtitles/movie/id.json?token=A") }
            assertFailsWith<CancellationException> { api.getManifest(manifest.replace("token=A","token=B")) }
        }
        coVerify(exactly = 1) { delegate.getManifest(manifest) }
        coVerify(exactly = 0) { delegate.getStreams(any()) }
    }

    @Test fun installationRetainsTheLateResponseFence() = runTest {
        val delegate = mockk<AddonApi>()
        val response = mockk<Response<AddonManifestDto>>()
        var epoch = 0L
        coEvery { delegate.getManifest(manifest) } coAnswers { epoch++; response }
        val api = DeviceGuardedAddonApi(delegate, allowed = { false }, sourceEpoch = { epoch })
        withContext(AddonInstallationRequest("https://addon.example/config?token=A")) {
            assertFailsWith<CancellationException> { api.getManifest(manifest) }
        }
    }
}
