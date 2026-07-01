package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.data.local.XtreamLiveStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Resolves a live-channel content id back to a playable (name, url) so the platform
 *  Library/Search can replay a channel on click, and resolves up/down zap neighbours for the
 *  fullscreen player. Session-browsed channels come from the in-memory registry; favorited
 *  ones survive restarts via the persisted live store. */
@HiltViewModel
class XtreamLiveResolverViewModel @Inject constructor(
    private val registry: XtreamItemRegistry,
    private val liveStore: XtreamLiveStore,
    private val livePlaylist: XtreamLivePlaylist
) : ViewModel() {

    data class ResolvedLive(val id: String, val name: String, val url: String)

    fun resolve(id: String): ResolvedLive? {
        registry.get(id)?.let { if (it.streamUrl.isNotBlank()) return ResolvedLive(id, it.name, it.streamUrl) }
        liveStore.refFor(id)?.let { return ResolvedLive(id, it.name, it.streamUrl) }
        return null
    }

    /** The channel [delta] steps from [currentId] in the active live playlist (for up/down zap). */
    fun zap(currentId: String, delta: Int): ResolvedLive? {
        val ref = livePlaylist.relativeTo(currentId, delta) ?: return null
        viewModelScope.launch { liveStore.recordPlayed(ref) }
        return ResolvedLive(ref.id, ref.name, ref.streamUrl)
    }

    /** Record a channel as just-watched so it lands in Recent Channels, no matter where it played from. */
    fun recordPlayed(id: String) {
        val ref = liveStore.refFor(id)
            ?: registry.get(id)?.takeIf { it.streamUrl.isNotBlank() }
                ?.let { LiveChannelRef(it.id, it.name, it.poster, it.streamUrl) }
            ?: return
        viewModelScope.launch { liveStore.recordPlayed(ref) }
    }
}
