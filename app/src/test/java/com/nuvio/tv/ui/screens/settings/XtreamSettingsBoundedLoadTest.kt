package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.IptvClient
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.overlay.ChannelOverlay
import com.nuvio.tv.core.iptv.overlay.OverlaySnapshot
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AuthState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression (IPTV settings, BoundedLoad SETTINGS surface): "Hidden channels & groups" read a playlist that
 * could not be loaded as `getOrDefault(emptyList())` and told the viewer "Nothing is hidden" — hiding the very
 * rows they came to unhide — and the Content & Categories checklist said "Loading categories…" forever when
 * the provider never answered. Both now end, and a failure is never empty.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XtreamSettingsBoundedLoadTest {
    @get:Rule val main = MainDispatcherRule()

    private val account = XtreamAccount(
        id = "http://settings.invalid|u", name = "Acme", baseUrl = "http://settings.invalid", username = "u", password = "p",
    )
    private val source = mockk<IptvClient>(relaxed = true)
    private val realSink = BoundedLoad.sink

    @Before
    fun setUp() {
        BoundedLoad.sink = { _, _ -> }
    }

    @After
    fun tearDown() {
        BoundedLoad.sink = realSink
    }

    private fun vm(): XtreamSettingsViewModel {
        val store = mockk<com.nuvio.tv.data.local.XtreamAccountStore>(relaxed = true) { every { accounts } returns MutableStateFlow(listOf(account)) }
        val factory = mockk<IptvClientFactory>(relaxed = true) { every { clientFor(any()) } returns source }
        val overlay = mockk<com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository>(relaxed = true) {
            coEvery { freshSnapshot() } returns OverlaySnapshot(channels = mapOf("e1" to ChannelOverlay(hidden = true)))
        }
        val refresher = mockk<com.nuvio.tv.core.iptv.ManagedInfoRefresher>(relaxed = true) {
            every { infosNow(any()) } returns emptyMap()
            every { infosFlow(any()) } returns flowOf(emptyMap())
        }
        val profiles = mockk<ProfileManager>(relaxed = true) { every { activeProfileId } returns MutableStateFlow(1) }
        val auth = mockk<com.nuvio.tv.core.auth.AuthManager>(relaxed = true) {
            every { authState } returns MutableStateFlow<AuthState>(AuthState.SignedOut)
            every { isAuthenticated } returns false
        }
        val failover = mockk<com.nuvio.tv.core.iptv.PlaylistServerFailover>(relaxed = true) { every { version } returns MutableStateFlow(0L) }
        val resolver = mockk<com.nuvio.tv.core.iptv.match.XtreamTmdbResolver>(relaxed = true) { every { indexing } returns MutableStateFlow(emptySet()) }
        val matchIndex = mockk<com.nuvio.tv.core.iptv.match.XtreamMatchIndex>(relaxed = true) { every { buildProgress } returns MutableStateFlow(emptyMap()) }
        return XtreamSettingsViewModel(
            store, mockk(relaxed = true), mockk(relaxed = true), factory, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), resolver, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), matchIndex,
            mockk(relaxed = true), overlay, auth, failover, refresher, profiles, mockk(relaxed = true),
            com.nuvio.tv.ui.screens.iptv.PlaylistDetailsRequests(),
            mockk(relaxed = true),
        )
    }

    @Test
    fun `a playlist that cannot be read is not reported as nothing hidden`() = runTest(main.dispatcher) {
        coEvery { source.liveChannels(any(), any()) } returns Result.failure(IOException("HTTP 503"))
        val vm = vm()
        runCurrent()

        vm.loadHiddenItems(account)
        runCurrent()

        assertEquals("the list ended Failed (with Retry)", LoadStatus.Failed(timedOut = false), vm.uiState.value.hiddenItemsLoad)
        assertNull("no list at all — never an empty 'Nothing is hidden' list", vm.uiState.value.hiddenItems)
    }

    @Test
    fun `a provider that never answers the content categories ends at the settings deadline`() = runTest(main.dispatcher) {
        coEvery { source.liveCategories(any()) } coAnswers { awaitCancellation() }
        coEvery { source.vodCategories(any()) } returns Result.success(emptyList())
        coEvery { source.seriesCategories(any()) } returns Result.success(emptyList())
        val vm = vm()
        runCurrent()

        vm.loadCategoryLists(account)
        runCurrent()
        val key = "${account.id}|${XtreamAccount.TYPE_LIVE}"
        assertTrue("the checklist starts loading", vm.uiState.value.categoryListLoads[key] is LoadStatus.Loading)

        advanceTimeBy(25_001)

        assertEquals("\"Loading categories…\" ended", LoadStatus.Failed(timedOut = true), vm.uiState.value.categoryListLoads[key])
        assertEquals("an empty answer is Empty, not Failed", LoadStatus.Empty, vm.uiState.value.categoryListLoads["${account.id}|${XtreamAccount.TYPE_MOVIES}"])
    }
}
