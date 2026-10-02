package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.iptv.ManagedInfoRefresher
import com.nuvio.tv.core.iptv.ProviderSetupRepository
import com.nuvio.tv.core.iptv.ProviderSupport
import com.nuvio.tv.core.iptv.SetupCodeOutcome
import com.nuvio.tv.core.iptv.SetupPreview
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The code screen's state machine: what carries on after sign-in and what is enabled after an error. */
@OptIn(ExperimentalCoroutinesApi::class)
class IptvSetupCodeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val auth = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val repo = mockk<ProviderSetupRepository>(relaxed = true)
    private val preview = SetupPreview("Acme TV", ProviderSupport(), null, emptyList(), emptyList())

    private fun vm(): IptvSetupCodeViewModel {
        val authManager = mockk<AuthManager> { every { authState } returns auth }
        val profiles = mockk<ProfileManager> {
            every { profiles } returns MutableStateFlow(listOf(UserProfile(1, "Profile 1", "#fff")))
            every { activeProfileId } returns MutableStateFlow(1)
        }
        return IptvSetupCodeViewModel(
            repo, mockk<ManagedInfoRefresher>(relaxed = true), profiles, authManager,
            mockk<XtreamAccountSyncService>(relaxed = true), PlaylistDetailsRequests(),
        )
    }

    private fun IptvSetupCodeViewModel.typeCode() = "ABCDEFGHJKMN".forEach { type(it) }

    @Test
    fun `a code typed while signed out carries on to the preview once the person signs in`() = runTest(main.dispatcher) {
        coEvery { repo.preview("ABCDEFGHJKMN") } returnsMany listOf(SetupCodeOutcome.NeedsSignIn, SetupCodeOutcome.Ready(preview))
        val vm = vm()
        advanceUntilIdle()
        vm.typeCode()
        vm.continueTapped()
        advanceUntilIdle()
        assertEquals(SetupPhase.NEEDS_SIGN_IN, vm.ui.value.phase)
        assertEquals("the code is kept while signing in", "ABCDEFGHJKMN", vm.ui.value.typed)

        auth.value = AuthState.FullAccount("u1", "me@example.com")
        advanceUntilIdle()

        assertEquals(SetupPhase.PREVIEW, vm.ui.value.phase)
        assertEquals("Acme TV", vm.ui.value.preview?.providerName)
        coVerify(exactly = 2) { repo.preview("ABCDEFGHJKMN") }
    }

    @Test
    fun `signing in with nothing waiting does not preview anything`() = runTest(main.dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        advanceUntilIdle()
        assertEquals(SetupPhase.ENTRY, vm.ui.value.phase)
        coVerify(exactly = 0) { repo.preview(any()) }
    }

    @Test
    fun `after a rejected code Continue stays off and focus goes to Delete until the code changes`() = runTest(main.dispatcher) {
        coEvery { repo.preview(any()) } returns SetupCodeOutcome.Unusable
        val vm = vm()
        advanceUntilIdle()
        vm.typeCode()
        assertTrue(vm.ui.value.continueEnabled)
        vm.continueTapped()
        advanceUntilIdle()
        assertEquals(SetupPhase.ENTRY, vm.ui.value.phase)
        assertFalse("OK must not resubmit the rejected code", vm.ui.value.continueEnabled)
        assertEquals("focus moves to Delete once", 1, vm.ui.value.deleteFocusTick)
        vm.continueTapped()
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.preview(any()) }

        vm.backspace()
        vm.type('N')
        assertTrue("an edited code can be submitted again", vm.ui.value.continueEnabled)
    }

    @Test
    fun `a network failure keeps Continue available for a retry and does not move focus`() = runTest(main.dispatcher) {
        coEvery { repo.preview(any()) } returns SetupCodeOutcome.Network
        val vm = vm()
        advanceUntilIdle()
        vm.typeCode()
        vm.continueTapped()
        advanceUntilIdle()
        assertTrue(vm.ui.value.continueEnabled)
        assertEquals(0, vm.ui.value.deleteFocusTick)
    }
}
