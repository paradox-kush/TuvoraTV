package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.iptv.IptvPairingManager
import com.nuvio.tv.core.iptv.ManagedEditPolicy
import com.nuvio.tv.core.iptv.ManagedInfoRefresher
import com.nuvio.tv.core.iptv.ManagedPlaylistInfo
import com.nuvio.tv.core.iptv.ProviderSupport
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.XtreamAccountStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pairing the provider's own server and login again must not rewrite a managed playlist (silent detach). */
@OptIn(ExperimentalCoroutinesApi::class)
class IptvPairingViewModelManagedGuardTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val managed = XtreamAccount(
        id = "http://panel.example.com|bob", name = "Acme", baseUrl = "HTTP://Panel.Example.com:80/", username = "bob", password = "p w ",
        userAgent = "VLC", backupUrls = listOf("HTTP://B.example.com/"),
    )

    private fun vm(isManaged: Boolean, store: XtreamAccountStore): IptvPairingViewModel {
        every { store.accounts } returns MutableStateFlow(listOf(managed))
        val refresher = mockk<ManagedInfoRefresher> {
            every { infosNow(any()) } returns if (isManaged) mapOf(managed.id to ManagedPlaylistInfo(managed.id, "Acme", null, ProviderSupport())) else emptyMap()
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        // init starts a pairing session: fail it quickly so no network is attempted
        val pairing = mockk<IptvPairingManager> { coEvery { createPairing(any()) } returns Result.failure(RuntimeException("offline")) }
        return IptvPairingViewModel(pairing, store, mockk(relaxed = true), mockk(relaxed = true), refresher, profiles)
    }

    @Test
    fun `a paired playlist with a managed key keeps the provider's fields`() = runTest(main.dispatcher) {
        val store = mockk<XtreamAccountStore>(relaxed = true)
        val saved = slot<XtreamAccount>()
        coEvery { store.upsert(capture(saved)) } returns Unit
        val paired = managed.copy(baseUrl = "http://panel.example.com", password = "other", userAgent = null, backupUrls = null, name = "Phone")
        vm(isManaged = true, store).savePairedAccount(paired)
        assertEquals(ManagedEditPolicy.providerOwned(managed), ManagedEditPolicy.providerOwned(saved.captured))
    }

    @Test
    fun `an unmanaged paired playlist is stored as paired`() = runTest(main.dispatcher) {
        val store = mockk<XtreamAccountStore>(relaxed = true)
        val saved = slot<XtreamAccount>()
        coEvery { store.upsert(capture(saved)) } returns Unit
        val paired = managed.copy(password = "other")
        vm(isManaged = false, store).savePairedAccount(paired)
        assertEquals("other", saved.captured.password)
    }
}
