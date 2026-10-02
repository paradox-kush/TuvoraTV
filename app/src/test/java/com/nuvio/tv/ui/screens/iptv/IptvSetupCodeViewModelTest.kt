package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.iptv.ManagedInfoRefresher
import com.nuvio.tv.core.iptv.ProviderSetupRepository
import com.nuvio.tv.core.iptv.RedeemFlowResult
import com.nuvio.tv.core.iptv.SetupCodeHolder
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
        runCurrent()
        vm.typeCode()
        vm.continueTapped()
        runCurrent()
        assertEquals(SetupPhase.NEEDS_SIGN_IN, vm.ui.value.phase)
        assertEquals("the code is kept while signing in", "ABCDEFGHJKMN", vm.ui.value.typed)

        auth.value = AuthState.FullAccount("u1", "me@example.com")
        runCurrent()

        assertEquals(SetupPhase.PREVIEW, vm.ui.value.phase)
        assertEquals("Acme TV", vm.ui.value.preview?.providerName)
        coVerify(exactly = 2) { repo.preview("ABCDEFGHJKMN") }
    }

    @Test
    fun `signing in with nothing waiting does not preview anything`() = runTest(main.dispatcher) {
        val vm = vm()
        runCurrent()
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        runCurrent()
        assertEquals(SetupPhase.ENTRY, vm.ui.value.phase)
        coVerify(exactly = 0) { repo.preview(any()) }
    }

    @Test
    fun `after a rejected code Continue stays off and focus goes to Delete until the code changes`() = runTest(main.dispatcher) {
        coEvery { repo.preview(any()) } returns SetupCodeOutcome.Unusable
        val vm = vm()
        runCurrent()
        vm.typeCode()
        assertTrue(vm.ui.value.continueEnabled)
        vm.continueTapped()
        runCurrent()
        assertEquals(SetupPhase.ENTRY, vm.ui.value.phase)
        assertFalse("OK must not resubmit the rejected code", vm.ui.value.continueEnabled)
        assertEquals("focus moves to Delete once", 1, vm.ui.value.deleteFocusTick)
        vm.continueTapped()
        runCurrent()
        coVerify(exactly = 1) { repo.preview(any()) }

        vm.backspace()
        vm.type('N')
        assertTrue("an edited code can be submitted again", vm.ui.value.continueEnabled)
    }

    @Test
    fun `a network failure keeps Continue available for a retry and does not move focus`() = runTest(main.dispatcher) {
        coEvery { repo.preview(any()) } returns SetupCodeOutcome.Network
        val vm = vm()
        runCurrent()
        vm.typeCode()
        vm.continueTapped()
        runCurrent()
        assertTrue(vm.ui.value.continueEnabled)
        assertEquals(0, vm.ui.value.deleteFocusTick)
    }

    @Test
    fun `the state never prints the typed code`() = runTest(main.dispatcher) {
        val vm = vm()
        runCurrent()
        vm.typeCode()
        val text = vm.ui.value.toString()
        assertFalse(text, "ABCDEFGH" in text || "ABCDEFGHJKMN" in text)
        assertTrue(text, "typedLength=12" in text)
    }

    @Test
    fun `the typed code is forgotten when the holder's 30 minutes are up`() = runTest(main.dispatcher) {
        val vm = vm()
        runCurrent()
        vm.typeCode()
        advanceTimeBy(SetupCodeHolder.TTL_MS - 1)
        assertEquals("ABCDEFGHJKMN", vm.ui.value.typed)
        advanceTimeBy(2)
        assertEquals("", vm.ui.value.typed)
        io.mockk.verify { repo.clearCode() }
        // typing again re-arms it
        vm.typeCode()
        advanceTimeBy(SetupCodeHolder.TTL_MS - 1)
        assertEquals("ABCDEFGHJKMN", vm.ui.value.typed)
    }

    @Test
    fun `a redeem-time expiry keeps the provider's contacts from the preview`() = runTest(main.dispatcher) {
        val support = ProviderSupport(telegram = "acme_tv")
        val withContacts = preview.copy(support = support)
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        coEvery { repo.preview(any()) } returns SetupCodeOutcome.Ready(withContacts)
        coEvery { repo.redeem(any(), any()) } returns RedeemFlowResult.Failed(SetupCodeOutcome.Expired(null))
        val vm = vm()
        runCurrent()
        vm.typeCode(); vm.continueTapped(); runCurrent()
        vm.confirmAdd(); runCurrent()
        assertEquals(support, vm.ui.value.expiredSupport)
        assertEquals("Acme TV", vm.ui.value.expiredProvider)
        assertEquals(com.nuvio.tv.core.iptv.SetupMessage.EXPIRED, vm.ui.value.message)
    }

    @Test
    fun `a transport failure on redeem keeps the code for a retry`() = runTest(main.dispatcher) {
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        coEvery { repo.preview(any()) } returns SetupCodeOutcome.Ready(preview)
        coEvery { repo.redeem(any(), any()) } returns RedeemFlowResult.Failed(SetupCodeOutcome.Network)
        val vm = vm()
        runCurrent()
        vm.typeCode(); vm.continueTapped(); runCurrent()
        vm.confirmAdd(); runCurrent()
        assertEquals("ABCDEFGHJKMN", vm.ui.value.typed)
        assertTrue(vm.ui.value.continueEnabled)
        assertEquals(com.nuvio.tv.core.iptv.SetupMessage.NETWORK, vm.ui.value.message)
    }

    @Test
    fun `a session the server refused on redeem asks for sign-in and keeps the code`() = runTest(main.dispatcher) {
        auth.value = AuthState.FullAccount("u1", "me@example.com")
        coEvery { repo.preview(any()) } returns SetupCodeOutcome.Ready(preview)
        coEvery { repo.redeem(any(), any()) } returns RedeemFlowResult.Failed(SetupCodeOutcome.NeedsSignIn)
        val vm = vm()
        runCurrent()
        vm.typeCode(); vm.continueTapped(); runCurrent()
        vm.confirmAdd(); runCurrent()
        assertEquals(SetupPhase.NEEDS_SIGN_IN, vm.ui.value.phase)
        assertEquals("ABCDEFGHJKMN", vm.ui.value.typed)
    }
}
