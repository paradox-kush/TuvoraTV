package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * UX331/UX337/UX359: the guide's "Fix guide" action. It asks IPTV settings to open the playlist's own details page
 * (where its guide settings live) once that screen is next shown; [PlaylistDetailsRequests] already carries that
 * one-shot for the setup-code flow. Kept out of the guide's view model so its constructor (and its tests) stay as they are.
 */
@HiltViewModel
class GuideFixActionViewModel @Inject constructor(
    private val requests: PlaylistDetailsRequests,
) : ViewModel() {
    fun openPlaylistSettings(playlistKey: String) = requests.open(playlistKey, addedBy = null)
}
