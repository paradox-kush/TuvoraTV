package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamMovie
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class XtreamVodUiState(
    val accountId: String = "",
    val accountName: String = "",
    val categories: List<XtreamCategory> = emptyList(),
    val moviesByCategory: Map<String, List<XtreamMovie>> = emptyMap(),
    val loadingCategories: Set<String> = emptySet(),
    val loading: Boolean = true,   // initial category-list load
    val error: String? = null
)

/**
 * Drives the Nuvio-style VOD browse screen: one horizontal row per category, each row's
 * movies loaded lazily as it scrolls into view (catalogs can be 100k+ items, so never
 * load them all). Mirrors how the home screen lazy-loads each catalog row.
 */
@HiltViewModel
class XtreamVodViewModel @Inject constructor(
    private val store: XtreamAccountStore,
    private val clientFactory: IptvClientFactory,
    private val registry: XtreamItemRegistry,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val accountId: String = savedStateHandle.get<String>("accountId").orEmpty()
    private var account: XtreamAccount? = null
    private val requested = mutableSetOf<String>()

    private val _uiState = MutableStateFlow(XtreamVodUiState())
    val uiState: StateFlow<XtreamVodUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { loadCategories() }
    }

    private suspend fun loadCategories() {
        val acc = store.accounts.first().firstOrNull { it.id == accountId }
        if (acc == null) {
            _uiState.update { it.copy(loading = false, error = "Account not found") }
            return
        }
        account = acc
        _uiState.update { it.copy(accountId = acc.id, accountName = acc.name, loading = true, error = null) }
        clientFactory.clientFor(acc).vodCategories(acc)
            .onSuccess { cats ->
                // Deselected categories stay hidden here too (mirrors XtreamHubViewModel).
                val visible = cats.filter { acc.allowsCategory(XtreamAccount.TYPE_MOVIES, it.id) }
                _uiState.update { it.copy(categories = visible, loading = false) }
            }
            .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message ?: "Failed to load") } }
    }

    /** Loads one category's movies once (idempotent — safe to call from a row's LaunchedEffect). */
    fun loadCategory(categoryId: String) {
        if (!requested.add(categoryId)) return
        val acc = account ?: return
        _uiState.update { it.copy(loadingCategories = it.loadingCategories + categoryId) }
        viewModelScope.launch {
            clientFactory.clientFor(acc).vodMovies(acc, categoryId)
                .onSuccess { movies ->
                    // Register each movie so the meta + stream short-circuits can resolve it
                    // when its native detail screen opens.
                    movies.forEach { m ->
                        registry.register(
                            XtreamResolvedItem(
                                id = XtreamItemRegistry.vodId(acc.id, m.streamId),
                                type = ContentType.MOVIE,
                                name = m.name,
                                poster = m.poster,
                                imdbRating = m.rating?.toFloatOrNull(),
                                streamUrl = m.streamUrl,
                                accountId = acc.id,
                                streamId = m.streamId
                            )
                        )
                    }
                    _uiState.update {
                        it.copy(
                            moviesByCategory = it.moviesByCategory + (categoryId to movies),
                            loadingCategories = it.loadingCategories - categoryId
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            moviesByCategory = it.moviesByCategory + (categoryId to emptyList()),
                            loadingCategories = it.loadingCategories - categoryId
                        )
                    }
                }
        }
    }

}
