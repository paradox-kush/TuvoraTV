package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.IptvPanelGuard
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamCategory
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.core.iptv.isM3UBacked
import com.nuvio.tv.core.iptv.isM3UFile
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.data.local.XtreamHubSelectionStore
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    /** categoryId -> more rows exist past the loaded window (item 5). */
    val hasMoreByCategory: Map<String, Boolean> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
    // The user's poster-size preference (Layout settings) — the hub's rails derive their card
    // size from the SAME source as the Modern home rows so the two surfaces always match.
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val posterLabelsEnabled: Boolean = true
) {
    val selectedAccount: XtreamAccount? get() = accounts.firstOrNull { it.id == selectedAccountId }
}

/**
 * Rows per category window (item 5). A category is loaded window-by-window — first window on
 * row-compose, the next appended as focus nears the row's end — instead of materializing a
 * 10k-item category as one List.
 */
private const val PAGE_SIZE = 400

/** How many category fetches may be in flight (fetching + parsing) at the same time. */
private const val MAX_CONCURRENT_CATEGORY_LOADS = 3

/** How many categories keep their loaded items cached — see itemsCache. */
private const val MAX_LOADED_CATEGORIES = 40

/** Past this many claimed fetches, best-effort prefetches are DROPPED rather than queued. */
private const val MAX_OUTSTANDING_CATEGORY_LOADS = 6

/**
 * Drives the top-level IPTV hub: pick an account (dropdown), pick a section (Live/Movies/Series),
 * browse category rows. Channels play directly; movies/series open the native detail (which the
 * meta + stream short-circuits handle). Per-category lazy loading (catalogs can be 100k+ items)
 * with a small bounded lookahead ([prefetchCategory]) so the next rows arrive filled in.
 */
@HiltViewModel
class XtreamHubViewModel @Inject constructor(
    private val store: XtreamAccountStore,
    private val clientFactory: IptvClientFactory,
    private val registry: XtreamItemRegistry,
    private val liveStore: XtreamLiveStore,
    private val selectionStore: XtreamHubSelectionStore,
    private val libraryRepository: LibraryRepository,
    private val fileStore: com.nuvio.tv.core.iptv.content.M3UFileStore,
    private val contentDb: com.nuvio.tv.core.iptv.content.IptvContentDb,
    private val matchIndex: com.nuvio.tv.core.iptv.match.XtreamMatchIndex,
    private val posterEnricher: com.nuvio.tv.core.iptv.match.PosterEnricher,
    layoutPreferenceDataStore: LayoutPreferenceDataStore,
    @ApplicationContext private val context: Context
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

    /**
     * Categories with a fetch claimed (queued or running), keyed "accountId|section|categoryId" —
     * the single-flight guard. Released in a `finally`, so a cancelled/failed fetch can be retried
     * (the old never-released "requested" set stranded a row as empty forever). Only ever touched
     * from the main dispatcher (composition + viewModelScope), so it needs no lock.
     */
    private val inFlightCategories = mutableSetOf<String>()

    // In-memory caches so switching sections/accounts and coming back is instant (no spinner, no re-fetch).
    private val categoriesCache = mutableMapOf<String, List<XtreamCategory>>()          // "accountId|section"

    /**
     * Loaded category items, LRU-bounded. This map used to only grow for the ViewModel's whole
     * life — every category ever browsed, across sections and accounts, stayed retained (and
     * every item in it is retained a second time by XtreamItemRegistry). On 2 GB sticks that
     * unbounded growth is heap the player and Coil don't get. Past the cap the
     * least-recently-loaded category drops and re-fetches if scrolled back to — a cheap
     * category call. (research/iptv-catalog-loading.md)
     */
    private val itemsCache = object : LinkedHashMap<String, List<XtreamHubItem>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<XtreamHubItem>>): Boolean =
            size > MAX_LOADED_CATEGORIES
    }

    // A single category can be tens of MB of JSON on a real panel, so the row lookahead must never
    // turn into a fan-out: at most MAX_CONCURRENT_CATEGORY_LOADS responses are ever being fetched
    // and parsed at once, and best-effort prefetches are dropped as soon as
    // MAX_OUTSTANDING_CATEGORY_LOADS fetches are already claimed (which also makes prefetch back
    // off by itself while the user flings and the visible rows are hogging the pipe).
    private val categoryLoadGate = Semaphore(MAX_CONCURRENT_CATEGORY_LOADS)

    init {
        // Track the poster-size preference so the hub's rails resize live with Layout settings,
        // exactly like the home rows do.
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp,
                layoutPreferenceDataStore.posterLabelsEnabled
            ) { widthDp, heightDp, cornerRadiusDp, labelsEnabled ->
                CardPrefs(widthDp, heightDp, cornerRadiusDp, labelsEnabled)
            }
                .distinctUntilChanged()
                .collect { prefs ->
                    _uiState.update {
                        it.copy(
                            posterCardWidthDp = prefs.widthDp,
                            posterCardHeightDp = prefs.heightDp,
                            posterCardCornerRadiusDp = prefs.cornerRadiusDp,
                            posterLabelsEnabled = prefs.labelsEnabled
                        )
                    }
                }
        }
        viewModelScope.launch {
            // Lazily enriched posters (PosterEnricher) patch loaded rows in place — the DB row
            // is already updated, this just repaints cards that are on screen right now.
            posterEnricher.updates.collect { u -> applyPosterUpdate(u) }
        }
        viewModelScope.launch {
            // Fix 1 (sticky provider): what the last visit left on screen, read BEFORE the first
            // accounts emission so a fresh entry restores it instead of resetting to the first
            // account. The section seeds once; after that the in-memory state is the truth.
            val remembered = selectionStore.read()
            var restoreSection = true
            // Keep observing so playlist edits/enables from Settings refresh a hub VM that
            // stayed on the backstack (a one-shot first() served stale accounts until death).
            store.accounts
                .map { list -> list.filter { it.enabled } }
                .distinctUntilChanged()
                .collect { accounts ->
                    val selected = resolveStickyAccount(_uiState.value.selectedAccountId, remembered.accountId, accounts)
                    val wanted = if (restoreSection) {
                        restoreSection = false
                        resolveStickySection(remembered.section, _uiState.value.section)
                    } else {
                        _uiState.value.section
                    }
                    // A disabled content type hides its tab — never leave the section on one.
                    val section = coerceSection(wanted, accounts.firstOrNull { it.id == selected })
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
        rememberSelection()
        loadCategories()
    }

    fun selectSection(section: XtreamSection) {
        if (section == _uiState.value.section) return
        _uiState.update { it.copy(section = section, categories = emptyList(), itemsByCategory = emptyMap()) }
        rememberSelection()
        loadCategories()
    }

    /** Fix 1: persist the on-screen provider + tab (per profile) so the next entry restores them. */
    private fun rememberSelection() {
        val st = _uiState.value
        viewModelScope.launch { selectionStore.save(st.selectedAccountId, st.section.name) }
    }

    /** Keeps the section on a content type the account has enabled (first enabled one otherwise). */
    private fun coerceSection(current: XtreamSection, acc: XtreamAccount?): XtreamSection {
        if (acc == null || acc.typeEnabled(current.typeKey)) return current
        return XtreamSection.entries.firstOrNull { acc.typeEnabled(it.typeKey) } ?: current
    }

    /** Retry after a failed category-list load (the ErrorState's Retry button). */
    fun retry() {
        // User-driven retry: clear the panel breaker FIRST (WP6) so it can never fast-fail the
        // very attempt the user just asked for.
        _uiState.value.selectedAccount?.let { IptvPanelGuard.resetForAccount(it) }
        _uiState.update { it.copy(error = null) }
        loadCategories()
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
                    error = context.getString(R.string.iptv_hub_error_file_missing)
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
            // Xtream reads its section rows from the local catalog once it's built (P7, item 4) —
            // no per-session category fetch. Index absent (first run): fall through to the live
            // API below (the warm-up is building it for next time).
            if (acc.isXtream()) {
                val stored = runCatching {
                    matchIndex.categoriesFor(acc.id, section.matchKind).map { (id, name) -> XtreamCategory(id, name) }
                }.getOrDefault(emptyList())
                if (stored.isNotEmpty()) {
                    categoriesCache[catKey] = stored
                    val visible = stored.filter { acc.allowsCategory(section.typeKey, it.id) }
                    _uiState.update { it.copy(categories = visible, loading = false) }
                    return@launch
                }
            }
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
                .onFailure { e ->
                    _uiState.update {
                        it.copy(loading = false, error = e.message ?: context.getString(R.string.iptv_hub_error_load_failed))
                    }
                }
        }
    }

    /** Fetch one category's items — called when its row composes. Never dropped. */
    fun loadCategory(categoryId: String) {
        requestCategory(categoryId, prefetch = false)
    }

    /**
     * Warm a category the user hasn't scrolled to yet, so its row lands with real posters and names
     * instead of shimmer. Best-effort by design: dropped whenever enough fetches are already
     * outstanding, so flinging through hundreds of categories can never queue unbounded work.
     */
    fun prefetchCategory(categoryId: String) {
        requestCategory(categoryId, prefetch = true)
    }

    private fun requestCategory(categoryId: String, prefetch: Boolean) {
        val acc = _uiState.value.selectedAccount ?: return
        val section = _uiState.value.section
        val key = "${acc.id}|$section|$categoryId"
        // Cache hit: restore items instantly without a network round-trip.
        itemsCache[key]?.let { cached ->
            if (categoryId !in _uiState.value.itemsByCategory) {
                _uiState.update { it.copy(itemsByCategory = it.itemsByCategory + (categoryId to cached)) }
            }
            return
        }
        // Claim the fetch: a visible row always gets one, a prefetch only while the pipe has room.
        if (key in inFlightCategories) return
        if (prefetch && inFlightCategories.size >= MAX_OUTSTANDING_CATEGORY_LOADS) return
        inFlightCategories.add(key)
        viewModelScope.launch {
            try {
                categoryLoadGate.withPermit { fetchCategoryItems(acc, section, categoryId, key, prefetch) }
            } finally {
                inFlightCategories.remove(key)
            }
        }
    }

    private val XtreamSection.matchKind: com.nuvio.tv.core.iptv.match.MatchKind
        get() = when (this) {
            XtreamSection.LIVE -> com.nuvio.tv.core.iptv.match.MatchKind.LIVE
            XtreamSection.MOVIES -> com.nuvio.tv.core.iptv.match.MatchKind.MOVIE
            XtreamSection.SERIES -> com.nuvio.tv.core.iptv.match.MatchKind.SERIES
        }

    /**
     * Appends the next window to an already-loaded category (item 5) — called by the row when
     * focus nears its end. Single-flight per key, best-effort.
     */
    fun loadMoreCategory(categoryId: String) {
        val acc = _uiState.value.selectedAccount ?: return
        val section = _uiState.value.section
        if (_uiState.value.hasMoreByCategory[categoryId] != true) return
        val key = "${acc.id}|$section|$categoryId"
        if (!inFlightCategories.add(key)) return
        viewModelScope.launch {
            try {
                val existing = itemsCache[key].orEmpty()
                val offset = existing.size
                val (more, hasMore) = fetchWindow(acc, section, categoryId, offset)
                // Dedup + no-progress guard. The row triggers loadMore as focus nears its end, so a
                // window that returns rows ALREADY loaded (a stale index, or a tied ORDER BY
                // overlapping its pages) would append dupes, grow the row, re-trigger, and spin
                // forever — the "category rotating on a loop" bug. Only NEW ids extend the row, and
                // a window that adds nothing new ends paging.
                val seen = existing.mapTo(HashSet(existing.size)) { it.cardId }
                val fresh = more.filter { it.cardId !in seen }
                val merged = existing + fresh
                itemsCache[key] = merged
                val stillMore = hasMore && fresh.isNotEmpty()
                if (_uiState.value.selectedAccountId == acc.id && _uiState.value.section == section) {
                    _uiState.update {
                        it.copy(
                            itemsByCategory = it.itemsByCategory + (categoryId to merged),
                            hasMoreByCategory = it.hasMoreByCategory + (categoryId to stillMore),
                        )
                    }
                }
            } finally {
                inFlightCategories.remove(key)
            }
        }
    }

    /**
     * One window of a category's items, per source (items 4+5):
     *  - Xtream w/ built catalog: local index window; stream URLs rebuilt from creds.
     *  - Xtream first-run (no index yet): the old full network fetch, once, kept to one window.
     *  - M3U + the Stalker live lineup: paged reads over IptvContentDb.
     *  - Stalker VOD/series: the portal's bounded page (70) — the protocol's own window.
     */
    private suspend fun fetchWindow(
        acc: XtreamAccount,
        section: XtreamSection,
        categoryId: String,
        offset: Int,
        prefetch: Boolean = false,
    ): Pair<List<XtreamHubItem>, Boolean> {
        if (acc.isXtream() && matchIndex.builtAt(acc.id, section.matchKind) != null) {
            val client = clientFactory.clientFor(acc) as com.nuvio.tv.core.iptv.XtreamClient
            val rows = matchIndex.itemsFor(acc.id, section.matchKind, categoryId, offset, PAGE_SIZE + 1)
            val page = rows.take(PAGE_SIZE)
            // Panels that ship no icons in the bulk list (or rows indexed before artwork was
            // known) get filled lazily: ask get_vod_info per null row, in list order, while the
            // user is on this window. Results land in the DB + patch in via posterUpdates.
            page.filter { it.poster == null }.map { it.sid }
                .takeIf { it.isNotEmpty() }
                ?.let { posterEnricher.enqueue(acc, section.matchKind, it, prioritize = !prefetch) }
            val items = page.map { r ->
                when (section) {
                    XtreamSection.LIVE -> {
                        val id = XtreamItemRegistry.liveId(acc.id, r.sid)
                        val url = client.buildStreamUrl(acc, "live", r.sid)
                        registry.register(
                            XtreamResolvedItem(
                                id = id, type = ContentType.TV, name = r.name, poster = r.poster,
                                streamUrl = url, kind = com.nuvio.tv.core.iptv.XtreamKind.LIVE,
                                accountId = acc.id, streamId = r.sid
                            )
                        )
                        XtreamHubItem(id, r.name, r.poster, isLive = true, contentId = id, streamUrl = url)
                    }
                    XtreamSection.MOVIES -> {
                        val id = XtreamItemRegistry.vodId(acc.id, r.sid)
                        registry.register(
                            XtreamResolvedItem(
                                id = id, type = ContentType.MOVIE, name = r.name, poster = r.poster,
                                streamUrl = client.buildStreamUrl(acc, "movie", r.sid, r.ext ?: "mp4"),
                                accountId = acc.id, streamId = r.sid
                            )
                        )
                        XtreamHubItem(id, r.name, r.poster, isLive = false, contentId = id, streamUrl = null)
                    }
                    XtreamSection.SERIES -> {
                        val id = XtreamItemRegistry.seriesId(acc.id, r.sid)
                        registry.register(
                            XtreamResolvedItem(
                                id = id, type = ContentType.SERIES, name = r.name, poster = r.poster,
                                streamUrl = "", kind = com.nuvio.tv.core.iptv.XtreamKind.SERIES,
                                accountId = acc.id, streamId = r.sid
                            )
                        )
                        XtreamHubItem(id, r.name, r.poster, isLive = false, contentId = id, streamUrl = null, detailType = "series")
                    }
                }
            }
            return items to (rows.size > PAGE_SIZE)
        }
        if (acc.sourceType == XtreamAccount.SOURCE_STALKER && section == XtreamSection.LIVE) {
            val rows = clientFactory.stalker().liveChannelsPage(acc, categoryId, offset, PAGE_SIZE + 1)
            val page = rows.take(PAGE_SIZE)
            val items = page.map { ch ->
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
            return items to (rows.size > PAGE_SIZE)
        }
        if (acc.isM3UBacked()) {
            return when (section) {
                XtreamSection.LIVE -> contentDb.pageChannels(acc.id, categoryId, offset, PAGE_SIZE + 1).let { rows ->
                    val page = rows.take(PAGE_SIZE)
                    page.map { r ->
                        val id = XtreamItemRegistry.liveId(acc.id, r.sid)
                        registry.register(
                            XtreamResolvedItem(
                                id = id, type = ContentType.TV, name = r.name, poster = r.logo,
                                streamUrl = r.url, kind = com.nuvio.tv.core.iptv.XtreamKind.LIVE,
                                accountId = acc.id, streamId = r.sid
                            )
                        )
                        XtreamHubItem(id, r.name, r.logo, isLive = true, contentId = id, streamUrl = r.url)
                    } to (rows.size > PAGE_SIZE)
                }
                XtreamSection.MOVIES -> contentDb.pageVod(acc.id, categoryId, offset, PAGE_SIZE + 1).let { rows ->
                    val page = rows.take(PAGE_SIZE)
                    page.map { r ->
                        val id = XtreamItemRegistry.vodId(acc.id, r.sid)
                        registry.register(
                            XtreamResolvedItem(
                                id = id, type = ContentType.MOVIE, name = r.name, poster = r.logo,
                                streamUrl = r.url, accountId = acc.id, streamId = r.sid
                            )
                        )
                        XtreamHubItem(id, r.name, r.logo, isLive = false, contentId = id, streamUrl = null)
                    } to (rows.size > PAGE_SIZE)
                }
                XtreamSection.SERIES -> contentDb.pageSeries(acc.id, categoryId, offset, PAGE_SIZE + 1).let { rows ->
                    val page = rows.take(PAGE_SIZE)
                    page.map { r ->
                        val id = XtreamItemRegistry.seriesId(acc.id, r.sid)
                        registry.register(
                            XtreamResolvedItem(
                                id = id, type = ContentType.SERIES, name = r.name, poster = r.logo,
                                streamUrl = "", kind = com.nuvio.tv.core.iptv.XtreamKind.SERIES,
                                accountId = acc.id, streamId = r.sid
                            )
                        )
                        XtreamHubItem(id, r.name, r.logo, isLive = false, contentId = id, streamUrl = null, detailType = "series")
                    } to (rows.size > PAGE_SIZE)
                }
            }
        }
        // Fallbacks (Xtream before its index exists; Stalker VOD/series): the old full fetch,
        // once — bounded to one window for Xtream, see fetchCategoryItemsLegacy.
        if (offset > 0) return emptyList<XtreamHubItem>() to false
        return fetchCategoryItemsLegacy(acc, section, categoryId) to false
    }

    /**
     * Repaints one lazily-enriched poster on every loaded copy of its row. The index row is
     * already written; this touches the in-memory copies: registry (detail/play path), the
     * LRU cache (rows on the backstack), and the visible state.
     */
    private fun applyPosterUpdate(u: com.nuvio.tv.core.iptv.match.PosterEnricher.PosterUpdate) {
        val section = when (u.kind) {
            com.nuvio.tv.core.iptv.match.MatchKind.MOVIE -> XtreamSection.MOVIES
            com.nuvio.tv.core.iptv.match.MatchKind.SERIES -> XtreamSection.SERIES
            com.nuvio.tv.core.iptv.match.MatchKind.LIVE -> return
        }
        val cardId = when (section) {
            XtreamSection.MOVIES -> XtreamItemRegistry.vodId(u.accountId, u.sid)
            else -> XtreamItemRegistry.seriesId(u.accountId, u.sid)
        }
        registry.get(cardId)?.let { registry.register(it.copy(poster = u.poster)) }
        // entry.setValue only — a get() on this access-ordered LRU inside iteration would
        // structurally reorder it mid-loop.
        val prefix = "${u.accountId}|$section|"
        for (entry in itemsCache.entries) {
            if (!entry.key.startsWith(prefix)) continue
            val list = entry.value
            if (list.none { it.cardId == cardId && it.poster == null }) continue
            entry.setValue(list.map { if (it.cardId == cardId) it.copy(poster = u.poster) else it })
        }
        val st = _uiState.value
        if (st.selectedAccountId == u.accountId && st.section == section &&
            st.itemsByCategory.values.any { l -> l.any { it.cardId == cardId && it.poster == null } }
        ) {
            _uiState.update { s ->
                s.copy(itemsByCategory = s.itemsByCategory.mapValues { (_, list) ->
                    if (list.none { it.cardId == cardId }) list
                    else list.map { if (it.cardId == cardId) it.copy(poster = u.poster) else it }
                })
            }
        }
    }

    private suspend fun fetchCategoryItems(
        acc: XtreamAccount,
        section: XtreamSection,
        categoryId: String,
        key: String,
        prefetch: Boolean = false,
    ) {
        val (items, hasMore) = fetchWindow(acc, section, categoryId, offset = 0, prefetch = prefetch)
        itemsCache[key] = items
        // Publish only while this account/section is still on screen: a prefetch that lands after
        // a switch would otherwise inject its items under a category id the new section may reuse.
        // Nothing is lost — the cache above serves them the moment the user comes back.
        if (_uiState.value.selectedAccountId == acc.id && _uiState.value.section == section) {
            _uiState.update {
                it.copy(
                    itemsByCategory = it.itemsByCategory + (categoryId to items),
                    hasMoreByCategory = it.hasMoreByCategory + (categoryId to hasMore),
                )
            }
        }
    }

    private suspend fun fetchCategoryItemsLegacy(
        acc: XtreamAccount,
        section: XtreamSection,
        categoryId: String,
    ): List<XtreamHubItem> {
        // Xtream lands here only while its index is still building: keep just the first window —
        // registering an entire 10k-item category (rows + registry + UI state) was a first-launch
        // heap spike, and the index takes over with real paging once categories reload. Stalker
        // VOD/series have no index to heal from, so they stay uncapped.
        fun <T> List<T>.bounded(): List<T> = if (acc.isXtream()) take(PAGE_SIZE) else this
        val client = clientFactory.clientFor(acc)
        val items: List<XtreamHubItem> = when (section) {
            XtreamSection.LIVE -> client.liveChannels(acc, categoryId).getOrDefault(emptyList()).bounded().map { ch ->
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
            XtreamSection.MOVIES -> client.vodMovies(acc, categoryId).getOrDefault(emptyList()).bounded().map { m ->
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
            XtreamSection.SERIES -> client.series(acc, categoryId).getOrDefault(emptyList()).bounded().map { s ->
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
        return items
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

    /** Bundle for the single combined layout-preference collector. */
    private data class CardPrefs(
        val widthDp: Int,
        val heightDp: Int,
        val cornerRadiusDp: Int,
        val labelsEnabled: Boolean
    )
}
