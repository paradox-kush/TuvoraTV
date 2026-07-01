package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A category entry in the guide's left column. [special] marks the synthetic ones. */
enum class GuideSpecial { FAVORITES, RECENT, ALL }
data class GuideCategory(val id: String, val name: String, val special: GuideSpecial? = null)

/** One channel row in the guide. */
data class GuideChannel(
    val contentId: String,
    val name: String,
    val logo: String?,
    val streamUrl: String,
    val streamId: Int
)

/** Now/next programs for a channel (from get_short_epg). */
data class GuideEpg(val now: XtreamProgram?, val next: XtreamProgram?)

data class LiveGuideUiState(
    val categories: List<GuideCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val channels: List<GuideChannel> = emptyList(),
    val epg: Map<Int, GuideEpg> = emptyMap(),
    val focusedChannelId: String? = null,
    val loadingChannels: Boolean = false,
    val error: String? = null
) {
    val focusedChannel: GuideChannel? get() = channels.firstOrNull { it.contentId == focusedChannelId }
}

/**
 * Drives the TiViMate-style Live TV guide: category column -> channel list with now/next EPG
 * -> a live preview of the focused channel. Channels register in the registry so pressing one
 * opens the existing fullscreen live player (which forces mpv for raw TS).
 *
 * ponytail: EPG is fetched per focused/visible channel via get_short_epg (1 call each, cached).
 * Bulk xmltv is the upgrade path if per-channel calls ever feel slow.
 */
@HiltViewModel
class XtreamLiveGuideViewModel @Inject constructor(
    private val client: XtreamClient,
    private val registry: XtreamItemRegistry,
    private val liveStore: XtreamLiveStore,
    private val livePlaylist: XtreamLivePlaylist,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveGuideUiState())
    val uiState: StateFlow<LiveGuideUiState> = _uiState.asStateFlow()

    /** Live channel ids currently in the platform Library (drives the ★ + add/remove). */
    val favoriteLiveIds: StateFlow<Set<String>> = libraryRepository.libraryItems
        .map { items -> items.filter { XtreamItemRegistry.isLiveContentId(it.id) }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private var account: XtreamAccount? = null
    private var channelsJob: Job? = null
    private val epgRequested = mutableSetOf<Int>()

    /** Called by the screen when the hub's selected account changes. */
    fun setAccount(acc: XtreamAccount) {
        if (acc.id == account?.id) return
        account = acc
        epgRequested.clear()
        viewModelScope.launch {
            val cats = client.liveCategories(acc).getOrDefault(emptyList())
            val full = buildList {
                add(GuideCategory("__fav", "Favorites", GuideSpecial.FAVORITES))
                add(GuideCategory("__recent", "Recent", GuideSpecial.RECENT))
                add(GuideCategory("__all", "All channels", GuideSpecial.ALL))
                cats.forEach { add(GuideCategory(it.id, it.name)) }
            }
            _uiState.update { it.copy(categories = full) }
            // Default to "All channels" so the guide isn't empty for a fresh account.
            selectCategory(full.firstOrNull { c -> c.special == GuideSpecial.ALL }?.id ?: full.firstOrNull()?.id)
        }
    }

    fun selectCategory(categoryId: String?) {
        val acc = account ?: return
        val category = _uiState.value.categories.firstOrNull { it.id == categoryId } ?: return
        if (categoryId == _uiState.value.selectedCategoryId && _uiState.value.channels.isNotEmpty()) return
        channelsJob?.cancel()
        _uiState.update { it.copy(selectedCategoryId = categoryId, channels = emptyList(), focusedChannelId = null, loadingChannels = true, error = null) }
        channelsJob = viewModelScope.launch {
            val channels: List<GuideChannel> = when (category.special) {
                GuideSpecial.FAVORITES -> favoriteChannels()
                GuideSpecial.RECENT -> liveStore.recents.first().map {
                    GuideChannel(it.id, it.name, it.logo, it.streamUrl, streamIdOf(it.id))
                }
                GuideSpecial.ALL -> fetchChannels(acc, null).take(ALL_CAP)
                null -> fetchChannels(acc, category.id)
            }
            _uiState.update { it.copy(channels = channels, loadingChannels = false, focusedChannelId = channels.firstOrNull()?.contentId) }
            channels.firstOrNull()?.let { ensureEpg(it.streamId) }
        }
    }

    /** Channel got D-pad focus: drive the preview + fetch its now/next EPG. */
    fun onChannelFocused(channel: GuideChannel) {
        if (channel.contentId == _uiState.value.focusedChannelId) return
        _uiState.update { it.copy(focusedChannelId = channel.contentId) }
        ensureEpg(channel.streamId)
    }

    /** Add/remove a channel from the platform Library (same store as movies). */
    fun toggleFavorite(channel: GuideChannel) {
        val adding = channel.contentId !in favoriteLiveIds.value
        viewModelScope.launch {
            libraryRepository.toggleDefault(
                LibraryEntryInput(
                    itemId = channel.contentId,
                    itemType = "tv",
                    title = channel.name,
                    poster = channel.logo,
                    posterShape = PosterShape.LANDSCAPE,
                    logo = channel.logo
                )
            )
            if (adding) liveStore.remember(LiveChannelRef(channel.contentId, channel.name, channel.logo, channel.streamUrl))
        }
    }

    /** Record a channel as just-watched + publish the current list so the fullscreen player
     *  can zap up/down through these channels. Called right before going fullscreen. */
    fun recordPlayed(channel: GuideChannel) {
        livePlaylist.set(
            _uiState.value.channels.map { LiveChannelRef(it.contentId, it.name, it.logo, it.streamUrl) }
        )
        viewModelScope.launch {
            liveStore.recordPlayed(LiveChannelRef(channel.contentId, channel.name, channel.logo, channel.streamUrl))
        }
    }

    fun ensureEpg(streamId: Int) {
        val acc = account ?: return
        if (streamId <= 0 || !epgRequested.add(streamId)) return
        viewModelScope.launch {
            val programs = client.shortEpg(acc, streamId).getOrDefault(emptyList())
            val nowMs = System.currentTimeMillis()
            val nowIdx = programs.indexOfFirst { it.nowPlaying || (nowMs in it.startMs until it.endMs) }
                .takeIf { it >= 0 } ?: 0
            val now = programs.getOrNull(nowIdx)
            val next = programs.getOrNull(nowIdx + 1)
            _uiState.update { it.copy(epg = it.epg + (streamId to GuideEpg(now, next))) }
        }
    }

    private suspend fun fetchChannels(acc: XtreamAccount, categoryId: String?): List<GuideChannel> {
        return client.liveChannels(acc, categoryId).getOrDefault(emptyList()).map { ch ->
            val id = XtreamItemRegistry.liveId(acc.id, ch.streamId)
            registry.register(
                XtreamResolvedItem(
                    id = id, type = ContentType.TV, name = ch.name, poster = ch.logo,
                    streamUrl = ch.streamUrl, kind = XtreamKind.LIVE, accountId = acc.id, streamId = ch.streamId
                )
            )
            GuideChannel(id, ch.name, ch.logo, ch.streamUrl, ch.streamId)
        }
    }

    private suspend fun favoriteChannels(): List<GuideChannel> {
        val favIds = libraryRepository.libraryItems.first()
            .filter { XtreamItemRegistry.isLiveContentId(it.id) }
            .map { it.id }
        return favIds.mapNotNull { id ->
            liveStore.refFor(id)?.let { GuideChannel(it.id, it.name, it.logo, it.streamUrl, streamIdOf(it.id)) }
        }
    }

    /** Best-effort streamId from a live content id ("xtream:acc:live:<streamId>"). */
    private fun streamIdOf(contentId: String): Int = contentId.substringAfterLast(":live:").toIntOrNull() ?: 0

    companion object {
        private const val ALL_CAP = 600   // ponytail: don't render 26k rows; categories are the real browse path
    }
}
