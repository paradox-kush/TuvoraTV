package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class XtreamLiveUiState(
    val accountName: String = "",
    val categories: List<XtreamCategory> = emptyList(),
    val channelsByCategory: Map<String, List<XtreamChannel>> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null
)

/**
 * Live TV browse: one row per live category, channels lazy-loaded per row (same lazy pattern
 * as VOD — live catalogs can be tens of thousands of channels). Channels play their .ts stream
 * directly (no detail/meta needed for a TV channel).
 */
@HiltViewModel
class XtreamLiveViewModel @Inject constructor(
    private val store: XtreamAccountStore,
    private val clientFactory: IptvClientFactory,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val accountId: String = savedStateHandle.get<String>("accountId").orEmpty()
    private var account: XtreamAccount? = null
    private val requested = mutableSetOf<String>()

    private val _uiState = MutableStateFlow(XtreamLiveUiState())
    val uiState: StateFlow<XtreamLiveUiState> = _uiState.asStateFlow()

    init { viewModelScope.launch { loadCategories() } }

    private suspend fun loadCategories() {
        val acc = store.accounts.first().firstOrNull { it.id == accountId }
        if (acc == null) {
            _uiState.update { it.copy(loading = false, error = "Account not found") }
            return
        }
        account = acc
        _uiState.update { it.copy(accountName = acc.name, loading = true, error = null) }
        clientFactory.clientFor(acc).liveCategories(acc)
            .onSuccess { cats ->
                // Deselected categories stay hidden here too (mirrors XtreamHubViewModel).
                val visible = cats.filter { acc.allowsCategory(XtreamAccount.TYPE_LIVE, it.id) }
                _uiState.update { it.copy(categories = visible, loading = false) }
            }
            .onFailure { e -> _uiState.update { it.copy(loading = false, error = e.message ?: "Failed to load") } }
    }

    fun loadCategory(categoryId: String) {
        if (!requested.add(categoryId)) return
        val acc = account ?: return
        viewModelScope.launch {
            clientFactory.clientFor(acc).liveChannels(acc, categoryId)
                .onSuccess { channels ->
                    _uiState.update { it.copy(channelsByCategory = it.channelsByCategory + (categoryId to channels)) }
                }
                .onFailure {
                    _uiState.update { it.copy(channelsByCategory = it.channelsByCategory + (categoryId to emptyList())) }
                }
        }
    }
}
