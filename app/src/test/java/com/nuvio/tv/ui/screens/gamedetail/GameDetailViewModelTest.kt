package com.nuvio.tv.ui.screens.gamedetail

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.radar.RadarChannelMatcher
import com.nuvio.tv.core.radar.RadarFixture
import com.nuvio.tv.core.radar.RadarPendingMatch
import com.nuvio.tv.core.radar.RadarRepository
import com.nuvio.tv.core.radar.RadarUiState
import com.nuvio.tv.core.sports.detail.BundledSportsMapping
import com.nuvio.tv.core.sports.detail.DetailLoad
import com.nuvio.tv.core.sports.detail.GameDetailRefreshPolicy
import com.nuvio.tv.core.sports.detail.GameDetailSource
import com.nuvio.tv.core.sports.detail.GameRef
import com.nuvio.tv.core.sports.detail.OfferResult
import com.nuvio.tv.core.sports.detail.SportsDetailService
import com.nuvio.tv.core.sports.detail.SportsMapping
import com.nuvio.tv.data.local.XtreamAccountStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeService : SportsDetailService {
        var syncs = 0
        override fun open(ref: GameRef) = object : GameDetailSource {
            override suspend fun tick(fetchEvent: Boolean, fetchSummary: Boolean): DetailLoad = DetailLoad.Failed()
        }
        override suspend fun syncMapping(): OfferResult? { syncs++; return null }
        override fun refreshConfig() = GameDetailRefreshPolicy.Config()
        override fun currentMapping(): SportsMapping = BundledSportsMapping.load()
    }

    private val fixture = RadarFixture(id = "7", leagueId = "4387", league = "NBA", sport = "Basketball", home = "Hawks", away = "Celtics", ts = "2026-11-07T00:00:00")
    private val accounts = MutableStateFlow<List<XtreamAccount>>(emptyList())
    private val service = FakeService()
    private val matcher = mockk<RadarChannelMatcher>()

    private fun vm(): GameDetailViewModel {
        val repo = mockk<RadarRepository>(relaxed = true)
        every { repo.uiState } returns MutableStateFlow(RadarUiState(fixturesByLeague = mapOf("4387" to listOf(fixture))))
        val store = mockk<XtreamAccountStore>(); every { store.accounts } returns accounts
        val profiles = mockk<ProfileManager>(); every { profiles.activeProfileId } returns MutableStateFlow(1)
        coEvery { matcher.match(any(), any(), any(), any(), any()) } returns emptyList()
        return GameDetailViewModel(SavedStateHandle(mapOf("gameId" to "7")), repo, matcher, service, mockk<XtreamLivePlaylist>(relaxed = true), profiles, store, mockk<RadarPendingMatch>(relaxed = true))
    }

    @Test fun `opening the screen refreshes the remote mapping`() = runTest(main.dispatcher) {
        vm(); advanceUntilIdle()
        assertEquals("one sync on open", 1, service.syncs)
    }

    @Test fun `a playlist added while the screen is open re-runs the channel match`() = runTest(main.dispatcher) {
        vm(); advanceUntilIdle()
        coVerify(exactly = 0) { matcher.match(any(), any(), any(), any(), any()) }
        accounts.value = listOf(mockk<XtreamAccount>(relaxed = true).also { every { it.enabled } returns true })
        advanceUntilIdle()
        coVerify(exactly = 1) { matcher.match(any(), any(), any(), any(), any()) }
    }

    @Test fun `a slow channel lookup ends as a failure with Retry after the deadline`() = runTest(main.dispatcher) {
        val v = vm()
        coEvery { matcher.match(any(), any(), any(), any(), any()) } coAnswers { kotlinx.coroutines.delay(600_000); emptyList() }
        accounts.value = listOf(mockk<XtreamAccount>(relaxed = true).also { every { it.enabled } returns true })
        advanceUntilIdle()
        assertEquals("failed after the deadline", com.nuvio.tv.core.sports.detail.screen.WatchStatus.FAILED, v.watch.value.status)
        assertEquals("with Retry", com.nuvio.tv.core.sports.detail.screen.PrimaryAction.Rematch(failed = true), v.watch.value.primary)
    }
}
