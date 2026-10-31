package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.iptv.refresh.IptvManualRefresher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Gives the playlist details page the injected manual refresher; each page builds its own per-playlist controller from it. */
@HiltViewModel
class PlaylistRefreshHostViewModel @Inject constructor(
    val refresher: IptvManualRefresher,
) : ViewModel()
