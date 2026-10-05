package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.iptv.ManagedEditPolicy
import com.nuvio.tv.core.iptv.ManagedInfoRefresher
import com.nuvio.tv.core.iptv.ManagedPlaylistInfo
import com.nuvio.tv.core.iptv.ProviderSupport
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.m3uAccountFromUrl
import com.nuvio.tv.core.iptv.xtreamAccountFromFields
import com.nuvio.tv.core.iptv.PlaylistKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/**
 * The silent-detach guard END TO END through the real ViewModel: every path that rebuilds an account from the form
 * (rename/edit of Xtream, M3U and Stalker, and ADD of a key that is already managed) must store the provider-owned
 * fields byte-identical. Deleting `editedAccount` / `accountToStore` from the ViewModel turns these red.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XtreamSettingsViewModelManagedWiringTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val xtream = XtreamAccount(
        id = PlaylistKey.xtream("http://panel.example.com", "Bob")!!, name = "Acme",
        baseUrl = "HTTP://Panel.Example.com:80/", username = "Bob", password = "Pa55 word ",
        sourceType = XtreamAccount.SOURCE_XTREAM, userAgent = "VLC/3.0.20", epgUrl = "http://EPG.example.com/x.xml/",
        backupUrls = listOf("HTTP://Backup.Example.com:80/"),
    )

    private val store = mockk<com.nuvio.tv.data.local.XtreamAccountStore>(relaxed = true)
    private val client = mockk<com.nuvio.tv.core.iptv.XtreamClient>(relaxed = true)

    private fun vm(existing: List<XtreamAccount>, managedKeys: Set<String>): XtreamSettingsViewModel {
        every { store.accounts } returns MutableStateFlow(existing)
        val refresher = mockk<ManagedInfoRefresher> {
            val map = managedKeys.associateWith { ManagedPlaylistInfo(it, "Acme TV", null, ProviderSupport()) }
            every { infosNow(any()) } returns map
            every { infosFlow(any()) } returns flowOf(map)
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val auth = mockk<com.nuvio.tv.core.auth.AuthManager> {
            every { authState } returns MutableStateFlow<AuthState>(AuthState.SignedOut)
            every { isAuthenticated } returns false
        }
        val failover = mockk<com.nuvio.tv.core.iptv.PlaylistServerFailover>(relaxed = true) { every { version } returns MutableStateFlow(0L) }
        val resolver = mockk<com.nuvio.tv.core.iptv.match.XtreamTmdbResolver>(relaxed = true) { every { indexing } returns MutableStateFlow(emptySet()) }
        val matchIndex = mockk<com.nuvio.tv.core.iptv.match.XtreamMatchIndex>(relaxed = true) { every { buildProgress } returns MutableStateFlow(emptyMap()) }
        return XtreamSettingsViewModel(
            store, mockk(relaxed = true), client, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), resolver, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), matchIndex,
            mockk(relaxed = true), mockk(relaxed = true), auth, failover, refresher, profiles, mockk(relaxed = true),
            com.nuvio.tv.ui.screens.iptv.PlaylistDetailsRequests(),
            mockk(relaxed = true),
        )
    }

    private fun options() = XtreamSettingsViewModel.PlaylistOptions()

    @Test
    fun `renaming a managed Xtream playlist stores the provider fields untouched`() = runTest(main.dispatcher) {
        val vm = vm(listOf(xtream), setOf(xtream.id))
        val saved = slot<XtreamAccount>()
        coEvery { store.replace(any(), capture(saved)) } returns Unit
        vm.editManual(xtream, "HTTP://Panel.Example.com:80/", "Bob", "Pa55 word ", "Renamed", options()) {}
        assertEquals("Renamed", saved.captured.name)
        assertEquals(ManagedEditPolicy.providerOwned(xtream), ManagedEditPolicy.providerOwned(saved.captured))
    }

    @Test
    fun `the same rename of an unmanaged playlist is rebuilt by the form as before`() = runTest(main.dispatcher) {
        val vm = vm(listOf(xtream), emptySet())
        val saved = slot<XtreamAccount>()
        coEvery { store.replace(any(), capture(saved)) } returns Unit
        coEvery { client.verify(any()) } returns Result.success(Unit)
        vm.editManual(xtream, "HTTP://Panel.Example.com:80/", "Bob", "Pa55 word ", "Renamed", options()) {}
        assertNotEquals("the guard is off for an unmanaged playlist", ManagedEditPolicy.providerOwned(xtream), ManagedEditPolicy.providerOwned(saved.captured))
        assertEquals("http://panel.example.com", saved.captured.baseUrl)
    }

    @Test
    fun `renaming a managed M3U link keeps the link and user agent`() = runTest(main.dispatcher) {
        val m3u = m3uAccountFromUrl("HTTP://Host.Example.com:80/list.m3u?x=1", "My-Player/1.0", "Acme M3U")!!.copy(backupUrls = listOf("HTTP://B.example.com:80/l.m3u/"))
        val vm = vm(listOf(m3u), setOf(m3u.id))
        val saved = slot<XtreamAccount>()
        coEvery { store.replace(any(), capture(saved)) } returns Unit
        vm.editM3UUrl(m3u, "http://host.example.com/list.m3u?x=1", "my-player/1.0", options()) {}
        assertEquals(ManagedEditPolicy.providerOwned(m3u), ManagedEditPolicy.providerOwned(saved.captured))
    }

    @Test
    fun `adding a server and login that is already a managed playlist does not rewrite it`() = runTest(main.dispatcher) {
        val vm = vm(listOf(xtream), setOf(xtream.id))
        val upserted = slot<XtreamAccount>()
        coEvery { store.upsert(capture(upserted)) } returns Unit
        coEvery { client.verify(any()) } returns Result.success(Unit)
        // the form rebuilds host/port, drops the backups and the user agent
        assertEquals(xtream.id, xtreamAccountFromFields("http://panel.example.com", "Bob", "typed", null)!!.id)
        vm.addManual("http://panel.example.com", "Bob", "typed", "Mine", options()) {}
        coVerify { store.upsert(any()) }
        assertEquals(ManagedEditPolicy.providerOwned(xtream), ManagedEditPolicy.providerOwned(upserted.captured))
    }

    @Test
    fun `adding an unmanaged duplicate behaves as before`() = runTest(main.dispatcher) {
        val vm = vm(listOf(xtream), emptySet())
        val upserted = slot<XtreamAccount>()
        coEvery { store.upsert(capture(upserted)) } returns Unit
        coEvery { client.verify(any()) } returns Result.success(Unit)
        vm.addManual("http://panel.example.com", "Bob", "typed", "Mine", options()) {}
        assertEquals("typed", upserted.captured.password)
    }
}
