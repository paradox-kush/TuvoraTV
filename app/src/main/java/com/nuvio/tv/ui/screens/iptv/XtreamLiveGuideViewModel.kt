package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.core.iptv.dns.PlaylistLivePlayback
import com.nuvio.tv.core.iptv.dns.PreparedLiveStream
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A category entry in the guide's left column. [special] marks the synthetic ones. */
enum class GuideSpecial { FAVORITES, RECENT, ALL }
data class GuideCategory(val id: String, val name: String, val special: GuideSpecial? = null)

/** One channel row in the guide. [categoryId] is null for synthetic rows (Favorites/Recent
 *  restores), which are never category-filtered. */
data class GuideChannel(
    val contentId: String,
    val name: String,
    val logo: String?,
    val streamUrl: String,
    val streamId: Int,
    val categoryId: String? = null
)

/** Programs for a channel: now/next plus the raw list feeding the guide's timeline cells. */
data class GuideEpg(
    val now: XtreamProgram?,
    val next: XtreamProgram?,
    val programmes: List<XtreamProgram> = emptyList()
)

data class LiveGuideUiState(
    val categories: List<GuideCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val channels: List<GuideChannel> = emptyList(),
    val epg: Map<Int, GuideEpg> = emptyMap(),
    val focusedChannelId: String? = null,
    /** What the single preview player is tuned to. Changes ONLY on OK (or last-played restore),
     *  never on focus movement — focus just browses. */
    val previewChannel: GuideChannel? = null,
    /** The URL + headers to actually hand mpv for [previewChannel] — DoH-rewritten when the playlist
     *  opts into a non-system resolver, else the channel's URL with no extra headers. Recomputed per
     *  channel; null until computed (the screen waits for it before loading). */
    val previewPlayback: PreparedLiveStream? = null,
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
    private val clientFactory: IptvClientFactory,
    private val registry: XtreamItemRegistry,
    private val liveStore: XtreamLiveStore,
    private val livePlaylist: XtreamLivePlaylist,
    private val libraryRepository: LibraryRepository,
    private val livePlayback: PlaylistLivePlayback,
    private val epgMirror: com.nuvio.tv.core.epg.EpgMirrorRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveGuideUiState())
    val uiState: StateFlow<LiveGuideUiState> = _uiState.asStateFlow()

    init {
        // Warm the canonical-EPG mirror (12h TTL, no-op when fresh) — it backs the guide's
        // now/next whenever the panel's own EPG is missing.
        viewModelScope.launch { epgMirror.ensureFresh() }
    }

    /** Live channel ids currently in the platform Library (drives the ★ + add/remove). */
    val favoriteLiveIds: StateFlow<Set<String>> = libraryRepository.libraryItems
        .map { items -> items.filter { XtreamItemRegistry.isLiveContentId(it.id) }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private var account: XtreamAccount? = null
    private var channelsJob: Job? = null
    private var epgFocusJob: Job? = null
    private val epgRequested = mutableSetOf<Int>()

    // Caches so revisiting an account/category is instant (no spinner flash, no re-fetch).
    private val categoriesCache = mutableMapOf<String, List<GuideCategory>>()   // accountId -> full list
    private val channelsCache = mutableMapOf<String, List<GuideChannel>>()      // "accountId|categoryId"

    /** Called by the screen when the hub's selected account changes (or its options change —
     *  category selections filter the guide's category column at display time). */
    fun setAccount(acc: XtreamAccount) {
        if (acc == account) return
        val sameAccount = acc.id == account?.id
        account = acc
        if (sameAccount) {
            // Option-only change: re-filter the cached category column, keep everything else.
            categoriesCache[acc.id]?.let { full ->
                val visible = filteredCategories(acc, full)
                _uiState.update { it.copy(categories = visible) }
                // "All channels" was fetched+filtered under the OLD selections — rebuild it.
                channelsCache.remove("${acc.id}|$ALL_ID")
                val selectedId = _uiState.value.selectedCategoryId
                if (visible.none { it.id == selectedId }) {
                    // The selected category just got deselected: fall back to "All channels".
                    selectCategory(visible.firstOrNull { c -> c.special == GuideSpecial.ALL }?.id ?: visible.firstOrNull()?.id)
                } else if (selectedId == ALL_ID) {
                    // Still on "All channels": refresh the displayed list under the new selections.
                    selectCategory(selectedId, force = true)
                }
            }
            return
        }
        epgRequested.clear()
        // Tune the preview to the LAST OPENED channel of this account (TiViMate-style resume).
        // Stalker's stored URL is a dead single-use create_link — skip the auto-resume for it
        // (OK on a row resolves a fresh URL); Xtream/M3U resume with their stable stored URL.
        if (acc.sourceType != XtreamAccount.SOURCE_STALKER) {
            viewModelScope.launch {
                if (_uiState.value.previewChannel != null) return@launch
                liveStore.recents.first()
                    .firstOrNull { it.id.startsWith("${XtreamItemRegistry.PREFIX}${acc.id}:live:") }
                    ?.let { ref ->
                        if (_uiState.value.previewChannel == null) {
                            tunePreview(GuideChannel(ref.id, ref.name, ref.logo, ref.streamUrl, streamIdOf(ref.id)))
                        }
                    }
            }
        }
        // Cache hit: show the category column immediately without re-fetching. The cache keeps
        // the UNFILTERED list; category selections filter at display time.
        categoriesCache[acc.id]?.let { full ->
            val visible = filteredCategories(acc, full)
            _uiState.update { it.copy(categories = visible) }
            selectCategory(visible.firstOrNull { c -> c.special == GuideSpecial.ALL }?.id ?: visible.firstOrNull()?.id)
            return
        }
        viewModelScope.launch {
            val cats = clientFactory.clientFor(acc).liveCategories(acc).getOrDefault(emptyList())
            val full = buildList {
                add(GuideCategory("__fav", "Favorites", GuideSpecial.FAVORITES))
                add(GuideCategory("__recent", "Recent", GuideSpecial.RECENT))
                add(GuideCategory(ALL_ID, "All channels", GuideSpecial.ALL))
                cats.forEach { add(GuideCategory(it.id, it.name)) }
            }
            categoriesCache[acc.id] = full
            val visible = filteredCategories(acc, full)
            _uiState.update { it.copy(categories = visible) }
            // Default to "All channels" so the guide isn't empty for a fresh account.
            selectCategory(visible.firstOrNull { c -> c.special == GuideSpecial.ALL }?.id ?: visible.firstOrNull()?.id)
        }
    }

    /** Category selections hide deselected provider categories; the synthetic ones
     *  (Favorites/Recent/All channels) are always shown. */
    private fun filteredCategories(acc: XtreamAccount, full: List<GuideCategory>): List<GuideCategory> =
        full.filter { it.special != null || acc.allowsCategory(XtreamAccount.TYPE_LIVE, it.id) }

    fun selectCategory(categoryId: String?, force: Boolean = false) {
        val acc = account ?: return
        val category = _uiState.value.categories.firstOrNull { it.id == categoryId } ?: return
        if (!force && categoryId == _uiState.value.selectedCategoryId && _uiState.value.channels.isNotEmpty()) return
        channelsJob?.cancel()
        // Cache hit for a network-backed category: swap channels in directly, skipping the empty-list
        // + loadingChannels spinner flash on revisit. (FAVORITES/RECENT stay dynamic — not cached here.)
        if (category.special == null || category.special == GuideSpecial.ALL) {
            channelsCache["${acc.id}|${categoryId}"]?.let { cached ->
                _uiState.update { it.copy(selectedCategoryId = categoryId, channels = cached, loadingChannels = false, error = null, focusedChannelId = cached.firstOrNull()?.contentId) }
                cached.firstOrNull()?.let { ensureEpg(it.streamId) }
                return
            }
        }
        _uiState.update { it.copy(selectedCategoryId = categoryId, channels = emptyList(), focusedChannelId = null, loadingChannels = true, error = null) }
        channelsJob = viewModelScope.launch {
            // null = the panel request FAILED (these panels throw transient 403/500s and
            // rate-limit bursts) — retry once, then surface an error instead of faking "empty".
            val channels: List<GuideChannel>? = when (category.special) {
                GuideSpecial.FAVORITES -> favoriteChannels()
                GuideSpecial.RECENT -> liveStore.recents.first().map {
                    GuideChannel(it.id, it.name, it.logo, it.streamUrl, streamIdOf(it.id))
                }
                // "All channels" honors the category selections too (filter BEFORE the cap so
                // a selection at the catalog's tail isn't cut off). Favorites/Recent stay unfiltered.
                GuideSpecial.ALL -> retryOnce { fetchChannels(acc, null) }
                    ?.filter { acc.allowsCategory(XtreamAccount.TYPE_LIVE, it.categoryId) }
                    ?.take(ALL_CAP)
                null -> retryOnce { fetchChannels(acc, category.id) }
            }
            if (channels == null) {
                _uiState.update {
                    it.copy(loadingChannels = false, error = "Provider error loading \"${category.name}\" — re-select to retry")
                }
                return@launch
            }
            // Cache network-backed lists so revisiting the category is instant. Never cache an
            // empty list: a transient panel failure must not pin a category empty all session.
            if ((category.special == null || category.special == GuideSpecial.ALL) && channels.isNotEmpty()) {
                channelsCache["${acc.id}|${category.id}"] = channels
            }
            _uiState.update { it.copy(channels = channels, loadingChannels = false, focusedChannelId = channels.firstOrNull()?.contentId) }
            channels.firstOrNull()?.let { ensureEpg(it.streamId) }
        }
    }

    /** One quiet retry for flaky IPTV panels; second failure bubbles up as null. */
    private suspend fun <T> retryOnce(block: suspend () -> T?): T? =
        block() ?: run { delay(RETRY_DELAY_MS); block() }

    /**
     * Channel got D-pad focus: drive the preview + fetch its now/next EPG. Debounced ~250ms and
     * prefetches the focused channel plus its ±3 neighbours so now/next is present when focus settles
     * (instead of one get_short_epg per composed row, which made fast scrolling feel laggy).
     */
    fun onChannelFocused(channel: GuideChannel, index: Int = -1) {
        if (channel.contentId == _uiState.value.focusedChannelId) return
        _uiState.update { it.copy(focusedChannelId = channel.contentId) }
        epgFocusJob?.cancel()
        epgFocusJob = viewModelScope.launch {
            delay(EPG_FOCUS_DEBOUNCE_MS)
            val channels = _uiState.value.channels
            val center = if (index in channels.indices) index else channels.indexOfFirst { it.contentId == channel.contentId }
            if (center < 0) { ensureEpg(channel.streamId); return@launch }
            // Focused first, then neighbours by proximity, so the visible row resolves soonest.
            ensureEpg(channels[center].streamId)
            for (d in 1..EPG_PREFETCH_RADIUS) {
                channels.getOrNull(center - d)?.let { ensureEpg(it.streamId) }
                channels.getOrNull(center + d)?.let { ensureEpg(it.streamId) }
            }
        }
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

    /** OK on a channel row: tune the single preview player to it (and remember it as
     *  last-played). OK on the already-tuned channel is handled by the screen (fullscreen).
     *
     *  Stalker channels carry a blank browse-time URL (create_link is single-use) — resolve it
     *  FRESH here so the placeholder never reaches mpv. Xtream/M3U have a stable URL, so
     *  [resolvedStreamUrl] returns it unchanged with no extra round-trip. */
    fun playPreview(channel: GuideChannel) {
        viewModelScope.launch {
            // Stalker needs a fresh create_link; Xtream/M3U reuse the browse-time URL. Then tunePreview
            // applies the playlist's DNS (resolve → rewrite) before handing the URL to the player.
            val playable = resolvedStreamUrl(channel)
            if (playable == null) {
                _uiState.update { it.copy(error = "Couldn't open \"${channel.name}\"") }
                return@launch
            }
            val tuned = channel.copy(streamUrl = playable)
            tunePreview(tuned)
            recordPlayed(tuned)
        }
    }

    /** The stream URL to feed the player: the browse-time URL if present (Xtream/M3U), else a fresh
     *  create_link (Stalker). Null if the source can't produce one. */
    private suspend fun resolvedStreamUrl(channel: GuideChannel): String? {
        if (channel.streamUrl.isNotBlank()) return channel.streamUrl
        val acc = account ?: return null
        return clientFactory.clientFor(acc).resolveStreamUrl(acc, "live", channel.streamId)
    }

    /**
     * Points the preview at [channel]: sets it synchronously (clearing any stale prepared playback so
     * the screen never loads the previous channel's DoH-rewritten URL) and computes the DoH-prepared
     * URL + headers off-main. The prepare is defensive — any failure yields the plain URL.
     */
    private fun tunePreview(channel: GuideChannel) {
        _uiState.update { it.copy(previewChannel = channel, previewPlayback = null) }
        val provider = account?.dnsProvider
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) { livePlayback.prepare(provider, channel.streamUrl) }
            // Ignore a stale result if the user has since tuned to a different channel.
            _uiState.update {
                if (it.previewChannel?.contentId == channel.contentId) it.copy(previewPlayback = prepared) else it
            }
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
            val nowMs = System.currentTimeMillis()
            // Panel EPG first; when the panel has nothing (the common case on real panels —
            // Starshare fills 6%), fall back to the mirrored canonical EPG via the mapping.
            // Panel short EPG first; else the mirror's programme window (the timeline needs more
            // than now/next, and the windowed query is a superset of nowNext anyway).
            val programs = clientFactory.clientFor(acc).shortEpg(acc, streamId).getOrDefault(emptyList())
                .ifEmpty {
                    runCatching { epgMirror.programmesWindow(acc.id, streamId, nowMs, nowMs + GUIDE_EPG_WINDOW_MS) }
                        .getOrDefault(emptyList())
                        .map { XtreamProgram(it.title, it.desc.orEmpty(), it.startMs, it.endMs, nowPlaying = nowMs in it.startMs until it.endMs) }
                }
            val nowIdx = programs.indexOfFirst { it.nowPlaying || (nowMs in it.startMs until it.endMs) }
                .takeIf { it >= 0 } ?: 0
            val now = programs.getOrNull(nowIdx)
            val next = programs.getOrNull(nowIdx + 1)
            _uiState.update { it.copy(epg = it.epg + (streamId to GuideEpg(now, next, programs))) }
        }
    }

    /** null = request failed (as opposed to a genuinely empty category). */
    private suspend fun fetchChannels(acc: XtreamAccount, categoryId: String?): List<GuideChannel>? {
        return clientFactory.clientFor(acc).liveChannels(acc, categoryId).getOrNull()?.map { ch ->
            val id = XtreamItemRegistry.liveId(acc.id, ch.streamId)
            registry.register(
                XtreamResolvedItem(
                    id = id, type = ContentType.TV, name = ch.name, poster = ch.logo,
                    streamUrl = ch.streamUrl, kind = XtreamKind.LIVE, accountId = acc.id, streamId = ch.streamId
                )
            )
            GuideChannel(id, ch.name, ch.logo, ch.streamUrl, ch.streamId, ch.categoryId)
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
        private const val ALL_ID = "__all"
        private const val ALL_CAP = 600   // ponytail: don't render 26k rows; categories are the real browse path
        private const val EPG_FOCUS_DEBOUNCE_MS = 250L   // wait for focus to settle before fetching EPG
        private const val EPG_PREFETCH_RADIUS = 8        // prefetch ±8 neighbours: timeline rows show cells, so cover a screenful
        private const val GUIDE_EPG_WINDOW_MS = 3 * 60 * 60 * 1000L  // mirror-fallback fetch span for the timeline
        private const val RETRY_DELAY_MS = 1000L         // pause before the single panel-flake retry
    }
}
