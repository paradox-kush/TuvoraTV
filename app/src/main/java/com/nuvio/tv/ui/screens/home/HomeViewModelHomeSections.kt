package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.home.HomeSectionOrderPolicy
import com.nuvio.tv.domain.model.HomeLayout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Mirrors the user's Home section order and visibility into the screen state. */
internal fun HomeViewModel.observeHomeSectionsPipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.homeSections
            .distinctUntilChanged()
            .collect { config -> _uiState.update { it.copy(homeSections = config) } }
    }
}

/**
 * Feeds the Recent Channels row. The work (a read of the recents and of the guide stored on the device) happens
 * only while the Modern layout has that row switched on, and only when the recents change or Home resumes: no
 * timer, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun HomeViewModel.observeLiveRecentsPipeline() {
    viewModelScope.launch {
        uiState
            .map { state ->
                state.homeLayout == HomeLayout.MODERN &&
                    HomeSectionOrderPolicy.LIVE_RECENTS in HomeSectionOrderPolicy.renderOrder(
                        state.homeSections,
                        state.heroSectionEnabled,
                        state.continueWatchingEnabled
                    )
            }
            .distinctUntilChanged()
            .flatMapLatest { wanted ->
                if (!wanted) flowOf(emptyList()) else _liveRecentsRefresh.flatMapLatest { liveRecentsSource.recents }
            }
            .distinctUntilChanged()
            .collect { recents -> _uiState.update { it.copy(liveRecents = recents) } }
    }
}

internal fun HomeViewModel.requestLiveRecentsRefresh() {
    _liveRecentsRefresh.update { it + 1 }
}
