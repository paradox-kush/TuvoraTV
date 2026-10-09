package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Whether the active profile has any IPTV playlist — empty-state copy must not ask an owner to add one. */
@HiltViewModel
internal class IptvPresenceViewModel @Inject constructor(
    accountStore: XtreamAccountStore,
) : ViewModel() {
    val hasAnyPlaylist: StateFlow<Boolean> = accountStore.accounts
        .map { it.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

@Composable
internal fun hasAnyIptvPlaylist(viewModel: IptvPresenceViewModel = hiltViewModel()): Boolean {
    val hasAny by viewModel.hasAnyPlaylist.collectAsStateWithLifecycle()
    return hasAny
}
