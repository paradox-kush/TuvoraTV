package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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

enum class XtreamSection { LIVE, MOVIES, SERIES }

/** One browsable item in the hub: a movie/series opens a detail; a channel plays directly. */
data class XtreamHubItem(
    val cardId: String,        // MetaPreview id used for card key + click matching
    val name: String,
    val poster: String?,
    val isLive: Boolean,
    val contentId: String?,    // movies/series -> xtream: detail id
    val streamUrl: String?,    // live -> direct play url
    val detailType: String = "movie"  // "movie" | "series"
)

data class XtreamHubUiState(
    val accounts: List<XtreamAccount> = emptyList(),
    val selectedAccountId: String? = null,
    val section: XtreamSection = XtreamSection.MOVIES,
    val categories: List<XtreamCategory> = emptyList(),
    val itemsByCategory: Map<String, List<XtreamHubItem>> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null
) {
    val selectedAccount: XtreamAccount? get() = accounts.firstOrNull { it.id == selectedAccountId }
}

/**
 * Drives the top-level IPTV hub: pick an account (dropdown), pick a section (Live/Movies/Series),
 * browse category rows. Channels play directly; movies/series open the native detail (which the
 * meta + stream short-circuits handle). Per-category lazy loading (catalogs can be 100k+ items).
 */
@HiltViewModel
class XtreamHubViewModel @Inject constructor(
    private val store: XtreamAccountStore,
    private val client: XtreamClient,
    private val registry: XtreamItemRegistry,
    private val liveStore: XtreamLiveStore,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(XtreamHubUiState())
    val uiState: StateFlow<XtreamHubUiState> = _uiState.asStateFlow()

    /** Recently-watched channels, newest first — the hub's "Recent Channels" row. */
    val recents: StateFlow<List<XtreamHubItem>> = liveStore.recents
        .map { refs -> refs.map { it.toHubItem() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Live channel ids currently in the platform Library (for the favorite star). */
    val favoriteLiveIds: StateFlow<Set<String>> = libraryRepository.libraryItems
        .map { items -> items.filter { XtreamItemRegistry.isLiveContentId(it.id) }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val requested = mutableSetOf<String>()   // "accountId|section|categoryId"

    init {
        viewModelScope.launch {
            val accounts = store.accounts.first().filter { it.enabled }
            _uiState.update { it.copy(accounts = accounts, selectedAccountId = accounts.firstOrNull()?.id) }
            if (accounts.isEmpty()) _uiState.update { it.copy(loading = false) } else loadCategories()
        }
    }

    fun selectAccount(accountId: String) {
        if (accountId == _uiState.value.selectedAccountId) return
        _uiState.update { it.copy(selectedAccountId = accountId, categories = emptyList(), itemsByCategory = emptyMap()) }
        loadCategories()
    }

    fun selectSection(section: XtreamSection) {
        if (section == _uiState.value.section) return
        _uiState.update { it.copy(section = section, categories = emptyList(), itemsByCategory = emptyMap()) }
        loadCategories()
    }

    private fun loadCategories() {
        val acc = _uiState.value.selectedAccount ?: return
        val section = _uiState.value.section
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = when (section) {
                XtreamSection.LIVE -> client.liveCategories(acc)
                XtreamSection.MOVIES -> client.vodCategories(acc)
                XtreamSection.SERIES -> client.seriesCategories(acc)
            }
            result
                .onSuccess { cats -> _uiState.update { it.copy(categories = cats, loading = false) } }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message ?: "Failed to load") } }
        }
    }

    fun loadCategory(categoryId: String) {
        val acc = _uiState.value.selectedAccount ?: return
        val section = _uiState.value.section
        val key = "${acc.id}|$section|$categoryId"
        if (!requested.add(key)) return
        viewModelScope.launch {
            val items: List<XtreamHubItem> = when (section) {
                XtreamSection.LIVE -> client.liveChannels(acc, categoryId).getOrDefault(emptyList()).map { ch ->
                    val id = XtreamItemRegistry.liveId(acc.id, ch.streamId)
                    registry.register(
                        XtreamResolvedItem(
                            id = id, type = ContentType.TV, name = ch.name, poster = ch.logo,
                            streamUrl = ch.streamUrl, kind = com.nuvio.tv.core.iptv.XtreamKind.LIVE,
                            accountId = acc.id, streamId = ch.streamId
                        )
                    )
                    XtreamHubItem(id, ch.name, ch.logo, isLive = true, contentId = id, streamUrl = ch.streamUrl)
                }
                XtreamSection.MOVIES -> client.vodMovies(acc, categoryId).getOrDefault(emptyList()).map { m ->
                    val id = XtreamItemRegistry.vodId(acc.id, m.streamId)
                    registry.register(
                        XtreamResolvedItem(
                            id = id, type = ContentType.MOVIE, name = m.name, poster = m.poster,
                            imdbRating = m.rating?.toFloatOrNull(), streamUrl = m.streamUrl,
                            accountId = acc.id, streamId = m.streamId
                        )
                    )
                    XtreamHubItem(id, m.name, m.poster, isLive = false, contentId = id, streamUrl = null)
                }
                XtreamSection.SERIES -> client.series(acc, categoryId).getOrDefault(emptyList()).map { s ->
                    val id = XtreamItemRegistry.seriesId(acc.id, s.seriesId)
                    registry.register(
                        XtreamResolvedItem(
                            id = id, type = ContentType.SERIES, name = s.name, poster = s.poster,
                            description = s.plot, imdbRating = s.rating?.toFloatOrNull(),
                            streamUrl = "", kind = com.nuvio.tv.core.iptv.XtreamKind.SERIES,
                            accountId = acc.id, streamId = s.seriesId
                        )
                    )
                    XtreamHubItem(id, s.name, s.poster, isLive = false, contentId = id, streamUrl = null, detailType = "series")
                }
            }
            _uiState.update { it.copy(itemsByCategory = it.itemsByCategory + (categoryId to items)) }
        }
    }

    /** Mark a channel as just-watched so it shows in Recent Channels and stays replayable. */
    fun recordPlayed(item: XtreamHubItem) {
        val ref = item.toLiveRef() ?: return
        viewModelScope.launch { liveStore.recordPlayed(ref) }
    }

    /** Add/remove a live channel from the platform Library (same store as movies). */
    fun toggleFavorite(item: XtreamHubItem) {
        val ref = item.toLiveRef() ?: return
        val adding = item.cardId !in favoriteLiveIds.value
        viewModelScope.launch {
            libraryRepository.toggleDefault(
                LibraryEntryInput(
                    itemId = item.cardId,
                    itemType = "tv",
                    title = item.name,
                    poster = item.poster,
                    posterShape = PosterShape.LANDSCAPE,
                    logo = item.poster
                )
            )
            // Persist the stream url so the Library click can replay it later.
            if (adding) liveStore.remember(ref)
        }
    }

    private fun XtreamHubItem.toLiveRef(): LiveChannelRef? {
        val url = streamUrl ?: return null
        return LiveChannelRef(id = cardId, name = name, logo = poster, streamUrl = url)
    }

    private fun LiveChannelRef.toHubItem(): XtreamHubItem =
        XtreamHubItem(id, name, logo, isLive = true, contentId = id, streamUrl = streamUrl)
}
