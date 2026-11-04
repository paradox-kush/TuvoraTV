package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.domain.model.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceClaimCoordinatorTest {
    private val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val authManager = mockk<AuthManager> { every { authState } returns auth }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(2) }
    private val api = mockk<ProviderSetupApi>()
    private val sync = mockk<XtreamAccountSyncService>(relaxed = true)
    private val local = mockk<DeviceSetupPlaylists>(relaxed = true)
    private val held = mutableListOf<DeviceClaim>()
    private val claims = mockk<DeviceClaimStore> {
        every { all() } answers { held.toList() }
        every { remove(any()) } answers { held.removeAll { c -> c.token == firstArg<String>() } }
    }
    private val claim = DeviceClaim("tok1", "Acme", 1L, listOf(DevicePlaylist(playlistKey = "k1"), DevicePlaylist(playlistKey = "k2")))

    private fun coordinator() = DeviceClaimCoordinator(api, claims, local, profiles, sync, authManager)

    @Test
    fun `a pending claim is claimed once the person signs in and then pulls once`() = runTest {
        held += claim
        coEvery { api.claimDeviceSetup("tok1", 2, any()) } returns ClaimOutcome.Claimed
        coordinator().start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        runCurrent()
        coVerify(exactly = 0) { api.claimDeviceSetup(any(), any(), any()) }

        auth.value = AuthState.FullAccount("u1", "me@example.com")
        runCurrent()

        coVerify(exactly = 1) { api.claimDeviceSetup("tok1", 2, !com.nuvio.tv.core.build.AppFeaturePolicy.addonsEnabled) }
        assertEquals(emptyList<DeviceClaim>(), held)
        coVerify(exactly = 1) { sync.pullAndApply() }
        coVerify(exactly = 0) { local.addMissing(any()) }
    }

    @Test
    fun `already signed in at app start claims straight away`() = runTest {
        held += claim
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        coEvery { api.claimDeviceSetup(any(), any(), any()) } returns ClaimOutcome.Claimed
        coordinator().start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        runCurrent()
        assertEquals(emptyList<DeviceClaim>(), held)
    }

    @Test
    fun `an unclaimable setup restores its playlists locally so the normal push uploads them`() = runTest {
        held += claim
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        coEvery { api.claimDeviceSetup(any(), any(), any()) } returns ClaimOutcome.Failed(ClaimError.UNAVAILABLE)
        coordinator().start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        runCurrent()
        coVerify(exactly = 1) { local.addMissing(claim.playlists) }
        assertEquals(emptyList<DeviceClaim>(), held)
        coVerify(exactly = 0) { sync.pullAndApply() }
        verify(exactly = 1) { sync.triggerRemoteSync() }
    }

    @Test
    fun `a network failure keeps the claim and does nothing else`() = runTest {
        held += claim
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        coEvery { api.claimDeviceSetup(any(), any(), any()) } returns ClaimOutcome.Failed(ClaimError.NETWORK)
        coordinator().start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        runCurrent()
        assertEquals(listOf(claim), held)
        coVerify(exactly = 0) { local.addMissing(any()) }
        coVerify(exactly = 0) { sync.pullAndApply() }
    }

    @Test
    fun `no timer loop a kept claim is not retried until the next sign-in`() = runTest {
        held += claim
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        coEvery { api.claimDeviceSetup(any(), any(), any()) } returns ClaimOutcome.Failed(ClaimError.RATE_LIMITED)
        coordinator().start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        runCurrent()
        testScheduler.advanceTimeBy(24L * 3600_000)
        runCurrent()
        coVerify(exactly = 1) { api.claimDeviceSetup(any(), any(), any()) }
    }

    @Test
    fun `signed out nothing is claimed and claims stay`() = runTest {
        held += claim
        coordinator().claimPending()
        coVerify(exactly = 0) { api.claimDeviceSetup(any(), any(), any()) }
        assertEquals(listOf(claim), held)
    }

    @Test
    fun `claims are processed one at a time with a single pull`() = runTest {
        held += claim
        held += claim.copy(token = "tok2")
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        coEvery { api.claimDeviceSetup(any(), any(), any()) } returns ClaimOutcome.Claimed
        coordinator().start(TestScope(UnconfinedTestDispatcher(testScheduler)))
        runCurrent()
        coVerify(exactly = 2) { api.claimDeviceSetup(any(), any(), any()) }
        coVerify(exactly = 1) { sync.pullAndApply() }
    }
}
