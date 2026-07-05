package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.core.iptv.isM3UFile
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class XtreamSection { LIVE, MOVIES, SERIES }

/** The content-type key this section maps to in [XtreamAccount.contentTypes]. */
val XtreamSection.typeKey: String
    get() = when (this) {
        XtreamSection.LIVE -> XtreamAccount.TYPE_LIVE
        XtreamSection.MOVIES -> XtreamAccount.TYPE_MOVIES
        XtreamSection.SERIES -> XtreamAccount.TYPE_SERIES
    }

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
    private val clientFactory: IptvClientFactory,
    private val registry: XtreamItemRegistry,
    private val liveStore: XtreamLiveStore,
    private val libraryRepository: LibraryRepository,
    private val fileStore: com.nuvio.tv.core.iptv.content.M3UFileStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(XtreamHubUiState())
    val uiState: StateFlow<XtreamHubUiState> = _uiState.asStateFlow()

    /**
     * Hero banner items for the Movies/Series sections: the first few items of the
     * first loaded category of the current section. Cheap to derive — reuses the
     * already-loaded category items rather than fetching anything extra.
     */
    val heroItems: StateFlow<List<XtreamHubItem>> = _uiState
        .map { state ->
            if (state.section == XtreamSection.LIVE) return@map emptyList()
            val firstLoaded = state.categories.firstNotNullOfOrNull { cat ->
                state.itemsByCategory[cat.id]?.takeIf { it.isNotEmpty() }
            } ?: emptyList()
            firstLoaded.take(10)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Recently-watched channels, newest first — the hub's "Recent Channels" row. */
    val recents: StateFlow<List<XtreamHubItem>> = liveStore.recents
        .map { refs -> refs.map { it.toHubItem() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Live channel ids currently in the platform Library (for the favorite star). */
    val favoriteLiveIds: StateFlow<Set<String>> = libraryRepository.libraryItems
        .map { items -> items.filter { XtreamItemRegistry.isLiveContentId(it.id) }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val requested = mutableSetOf<String>()   // "accountId|section|categoryId"

    // In-memory caches so switching sections/accounts and coming back is instant (no spinner, no re-fetch).
    private val categoriesCache = mutableMapOf<String, List<XtreamCategory>>()          // "accountId|section"
    private val itemsCache = mutableMapOf<String, List<XtreamHubItem>>()                // "accountId|section|categoryId"

    init {
        viewModelScope.launch {
            // Keep observing so playlist edits/enables from Settings refresh a hub VM that
            // stayed on the backstack (a one-shot first() served stale accounts until death).
            store.accounts
                .map { list -> list.filter { it.enabled } }
                .distinctUntilChanged()
                .collect { accounts ->
                    val selected = _uiState.value.selectedAccountId
                        ?.takeIf { id -> accounts.any { it.id == id } }
                        ?: accounts.firstOrNull()?.id
                    // A disabled content type hides its tab — never leave the section on one.
                    val section = coerceSection(_uiState.value.section, accounts.firstOrNull { it.id == selected })
                    _uiState.update { it.copy(accounts = accounts, selectedAccountId = selected, section = section) }
                    if (accounts.isEmpty()) {
                        _uiState.update { it.copy(loading = false) }
                    } else {
                        loadCategories()
                    }
                }
        }
    }

    fun selectAccount(accountId: String) {
        if (accountId == _uiState.value.selectedAccountId) return
        val section = coerceSection(_uiState.value.section, _uiState.value.accounts.firstOrNull { it.id == accountId })
        _uiState.update { it.copy(selectedAccountId = accountId, section = section, categories = emptyList(), itemsByCategory = emptyMap()) }
        loadCategories()
    }

    fun selectSection(section: XtreamSection) {
        if (section == _uiState.value.section) return
        _uiState.update { it.copy(section = section, categories = emptyList(), itemsByCategory = emptyMap()) }
        loadCategories()
    }

    /** Keeps the section on a content type the account has enabled (first enabled one otherwise). */
    private fun coerceSection(current: XtreamSection, acc: XtreamAccount?): XtreamSection {
        if (acc == null || acc.typeEnabled(current.typeKey)) return current
        return XtreamSection.entries.firstOrNull { acc.typeEnabled(it.typeKey) } ?: current
    }

    private fun loadCategories() {
        val acc = _uiState.value.selectedAccount ?: return
        val section = _uiState.value.section
        // A file playlist synced from another device has no local copy (file contents don't
        // sync) — say so instead of a misleading empty "Nothing here".
        if (acc.isM3UFile() && !fileStore.exists(acc.id)) {
            _uiState.update {
                it.copy(
                    categories = emptyList(), itemsByCategory = emptyMap(), loading = false,
                    error = "Playlist file not on this device — re-import it in Settings → IPTV"
                )
            }
            return
        }
        // Disabled content type: hidden section, and its data is never fetched.
        if (!acc.typeEnabled(section.typeKey)) {
            _uiState.update { it.copy(categories = emptyList(), itemsByCategory = emptyMap(), loading = false, error = null) }
            return
        }
        val catKey = "${acc.id}|$section"
        // Cache hit: restore categories + their already-loaded items instantly (no spinner, no re-fetch).
        // The cache keeps the UNFILTERED list; category selections filter at display time.
        categoriesCache[catKey]?.let { cached ->
            val visible = cached.filter { acc.allowsCategory(section.typeKey, it.id) }
            val items = visible.mapNotNull { c -> itemsCache["$catKey|${c.id}"]?.let { c.id to it } }.toMap()
            _uiState.update { it.copy(categories = visible, itemsByCategory = items, loading = false, error = null) }
            return
        }
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val client = clientFactory.clientFor(acc)
            val result = when (section) {
                XtreamSection.LIVE -> client.liveCategories(acc)
                XtreamSection.MOVIES -> client.vodCategories(acc)
                XtreamSection.SERIES -> client.seriesCategories(acc)
            }
            result
                .onSuccess { cats ->
                    categoriesCache[catKey] = cats
                    val visible = cats.filter { acc.allowsCategory(section.typeKey, it.id) }
                    _uiState.update { it.copy(categories = visible, loading = false) }
                }
                .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message ?: "Failed to load") } }
        }
    }

    fun loadCategory(categoryId: String) {
        val acc = _uiState.value.selectedAccount ?: return
        val section = _uiState.value.section
        val key = "${acc.id}|$section|$categoryId"
        // Cache hit: restore items instantly without a network round-trip.
        itemsCache[key]?.let { cached ->
            requested.add(key)
            if (categoryId !in _uiState.value.itemsByCategory) {
                _uiState.update { it.copy(itemsByCategory = it.itemsByCategory + (categoryId to cached)) }
            }
            return
        }
        if (!requested.add(key)) return
        viewModelScope.launch {
            val client = clientFactory.clientFor(acc)
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
            itemsCache[key] = items
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
