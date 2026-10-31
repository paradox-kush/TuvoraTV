package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.LoadSurface
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamSearchIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * F48: search inside ONE playlist from the IPTV hub's Movies and Series sections. Live keeps its own guide
 * search (F01); the main Search tab still mixes every playlist with add-ons — this is the scoped one people
 * asked for. Pure decisions only; the I/O lives in [XtreamHubSearchController]. Mirrors the KMP apps'
 * `IptvHubSearchPolicy`, except TV searches on SUBMIT (OK on Search opens the keyboard, like the guide's
 * F01 search) rather than as you type, so there is no debounce here.
 */
internal object IptvHubSearchPolicy {
    const val MIN_QUERY_LENGTH = 2

    /** The content type a section searches, or null when the section has no hub search (Live). */
    fun contentTypeFor(section: XtreamSection): String? = when (section) {
        XtreamSection.MOVIES -> XtreamAccount.TYPE_MOVIES
        XtreamSection.SERIES -> XtreamAccount.TYPE_SERIES
        XtreamSection.LIVE -> null
    }

    /** The trimmed query to send, or null when no request should be made (wrong section, too short). */
    fun request(section: XtreamSection, raw: String): String? {
        if (contentTypeFor(section) == null) return null
        return raw.trim().takeIf { it.length >= MIN_QUERY_LENGTH }
    }
}

data class HubSearchUiState(
    /** The results page is showing (any state of it). The query outlives closing so the box re-opens prefilled. */
    val open: Boolean = false,
    val query: String = "",
    /** Entered only through [BoundedLoad]: a failed search is Failed (with Retry), never "no matches". */
    val load: LoadStatus = LoadStatus.Idle,
    val items: List<XtreamHubItem> = emptyList(),
)

/**
 * State holder for the hub search (the composable only renders it). A newer submit, a retry or a close
 * cancels the one in flight, so a slow old answer can never overwrite a newer one.
 */
internal class XtreamHubSearchController(
    private val scope: CoroutineScope,
    private val search: suspend (accountId: String, type: String, query: String) -> List<XtreamHubItem>,
) {
    private val mutableState = MutableStateFlow(HubSearchUiState())
    val state: StateFlow<HubSearchUiState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var last: Triple<String, XtreamSection, String>? = null

    fun submit(accountId: String, section: XtreamSection, raw: String) {
        val query = IptvHubSearchPolicy.request(section, raw)
        job?.cancel()
        if (query == null) {
            // Too short (or Live): no request is made, and the viewer stays where they were.
            mutableState.value = mutableState.value.copy(open = false, load = LoadStatus.Idle, items = emptyList())
            return
        }
        last = Triple(accountId, section, query)
        run(accountId, section, query)
    }

    fun retry() {
        val (accountId, section, query) = last ?: return
        run(accountId, section, query)
    }

    /** Back from the results, or the viewer switched playlist/section: stop, keep the text for next time. */
    fun close() {
        job?.cancel()
        mutableState.value = HubSearchUiState(query = mutableState.value.query)
    }

    private fun run(accountId: String, section: XtreamSection, query: String) {
        job?.cancel()
        val type = IptvHubSearchPolicy.contentTypeFor(section) ?: return
        mutableState.value = HubSearchUiState(open = true, query = query, load = BoundedLoad.begin(LoadSurface.HUB_SEARCH))
        job = scope.launch {
            val outcome = BoundedLoad.run(
                LoadSurface.HUB_SEARCH,
                isEmpty = { it.isEmpty() },
                report = mapOf("section" to type),
            ) { search(accountId, type, query) }
            mutableState.value = HubSearchUiState(
                open = true, query = query, load = outcome.status, items = outcome.valueOrNull().orEmpty(),
            )
        }
    }
}

@HiltViewModel
class XtreamHubSearchViewModel @Inject constructor(
    searchIndex: XtreamSearchIndex,
) : ViewModel() {
    private val controller = XtreamHubSearchController(viewModelScope) { accountId, type, query ->
        searchIndex.searchPlaylist(accountId, type, query).map { hit ->
            XtreamHubItem(
                cardId = hit.contentId, name = hit.name, poster = hit.poster, isLive = false,
                contentId = hit.contentId, streamUrl = null, detailType = hit.detailType,
            )
        }
    }
    val state: StateFlow<HubSearchUiState> = controller.state

    fun submit(accountId: String, section: XtreamSection, query: String) = controller.submit(accountId, section, query)
    fun retry() = controller.retry()
    fun close() = controller.close()
}
