package com.nuvio.tv.ui.screens.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.IptvPanelGuard
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
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withPermit
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
    val categoryId: String? = null,
    /** The panel's channel-level `tv_archive` flag — the ⟲ beside the channel name. */
    val hasArchive: Boolean = false,
    /** `tv_archive_duration` in days; 0 = the panel did not say (see XtreamCatchUp.isWithinWindow). */
    val catchUpDays: Int = 0
)

/** Programs for a channel: now/next plus the raw list feeding the guide's timeline cells. */
data class GuideEpg(
    val now: XtreamProgram?,
    val next: XtreamProgram?,
    val programmes: List<XtreamProgram> = emptyList()
)

/** Everything the player needs for one replay — see CatchUpPlaybackCoordinator. */
data class ReplayLaunch(
    val url: String,
    val contentId: String,
    val title: String,
    val programmeStartMs: Long,
    val programmeEndMs: Long
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
    /** Container to force for [previewPlayback], set only by the container-mismatch retry (see
     *  [XtreamLiveGuideViewModel.onPreviewContainerMismatch]). Null = infer from the URL. */
    val previewMimeOverride: String? = null,
    val loadingChannels: Boolean = false,
    val error: String? = null,
    /**
     * Start of the visible two hours. Travels backward with LEFT past the window's edge; the minute
     * tick only rolls it forward while it is still anchored at live, so a viewer reading yesterday
     * is never yanked back to now.
     */
    val windowStartMs: Long = GuideTimeTravel.liveWindowStartMs(System.currentTimeMillis()),
    /**
     * Whether this playlist can build catch-up URLs at all (Xtream with credentials). False turns
     * every cell action back into plain live, so a Stalker or M3U playlist never shows a replay
     * badge it could not honour.
     */
    val catchUpSupported: Boolean = false,
    /** Set when a replay is ready to launch; the screen consumes it and navigates. */
    val replayLaunch: ReplayLaunch? = null
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
    private val contentDb: com.nuvio.tv.core.iptv.content.IptvContentDb,
    private val catchUp: com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator,
    private val matchIndex: com.nuvio.tv.core.iptv.match.XtreamMatchIndex,
    private val xmltv: com.nuvio.tv.core.iptv.epg.XmltvClient,
) : ViewModel() {

    /**
     * Historical guide for the FOCUSED channel only — `get_simple_data_table`, which is the one
     * endpoint that returns past programmes and therefore the one that makes "days back" real.
     * Deliberately not prefetched across channels (a full week per channel is how 2 MB becomes
     * 40 MB on a 192 MB heap), gated on the stored copy's age, and single-flighted so the focus
     * debounce, the timeline opening and a re-focus cannot become three requests.
     */
    private val historyFetcher = com.nuvio.tv.core.iptv.CatchUpEpgFetcher(
        fetchedAt = { playlistId, channelId -> contentDb.epgChannelFetchedAt(playlistId, channelId) },
        refill = { playlistId, channelId, catchUpDays, nowMs ->
            refillHistory(playlistId, channelId, catchUpDays, nowMs)
        },
    )

    private val _uiState = MutableStateFlow(LiveGuideUiState())
    val uiState: StateFlow<LiveGuideUiState> = _uiState.asStateFlow()

    init {
        // Warm the canonical-EPG mirror (12h TTL, no-op when fresh) — it backs the guide's
        // now/next whenever the panel's own EPG is missing.
        //
        // NOT on viewModelScope: the sync is minutes long and dies the moment the viewer leaves
        // the guide. Measured on an Onn (2026-08-18): it reached the match phase and was then
        // cancelled, twice, so the mirror downloaded nothing at all.
        epgMirror.warm()
        // If programmes land while this screen is still open, the "nothing for this channel"
        // verdicts taken before them are stale — retire them so rows can resolve.
        viewModelScope.launch {
            epgMirror.programmesCommitted.collect {
                epgAdmission.invalidate()
                epgRequested.clear()
            }
        }
    }

    /** Live channel ids currently in the platform Library (drives the ★ + add/remove). */
    val favoriteLiveIds: StateFlow<Set<String>> = libraryRepository.libraryItems
        .map { items -> items.filter { XtreamItemRegistry.isLiveContentId(it.id) }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private var account: XtreamAccount? = null
    private var channelsJob: Job? = null
    private var epgFocusJob: Job? = null
    private val epgRequested = mutableSetOf<Int>()

    /**
     * The one gate to this panel's EPG endpoint, and the memory of which channels are not worth
     * asking again yet — the TV counterparts of the mobile/desktop TileEpgQueue's two workers and
     * TileEpgAdmission's per-channel cooldown, so every platform is polite in the same way.
     */
    private val epgFetchGate = kotlinx.coroutines.sync.Semaphore(EPG_FETCH_PERMITS)
    private val epgAdmission = com.nuvio.tv.core.iptv.TileEpgAdmission()

    // Caches so revisiting an account/category is instant (no spinner flash, no re-fetch).
    private val categoriesCache = mutableMapOf<String, List<GuideCategory>>()   // accountId -> full list
    private val channelsCache = mutableMapOf<String, List<GuideChannel>>()      // "accountId|categoryId"

    /** Called by the screen when the hub's selected account changes (or its options change —
     *  category selections filter the guide's category column at display time). */
    fun setAccount(acc: XtreamAccount) {
        if (acc == account) return
        val sameAccount = acc.id == account?.id
        account = acc
        // Warm this account's OWN whole guide (xmltv.php), so the store rung has something to serve
        // and the per-channel asks stop. On the ingest's own scope — never this ViewModel's, which
        // is the mistake that cost the mirror 76 seconds of work per visit.
        xmltv.warm(acc)
        // Stalker warms its lineup + the ONE bulk get_epg_info here (Xtream/M3U guide warming is the
        // xmltv line above). Runs on the client's OWN scope, so now/next is ready as the user scrolls
        // instead of only after they settle on a channel — see StalkerClient.warm.
        clientFactory.clientFor(acc).warm(acc)
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
        // Switching to a different playlist must not leave the OLD account's channel decoding in the
        // shared preview player. Clear the preview state (the screen stops the player when
        // previewChannel goes null) and reset this account's freeze bookkeeping — without this the
        // preview keeps playing the previous playlist's channel after a switch (root-caused 2026-08-20).
        previewFreezeRecoveryAttempts = 0
        previewFreezeRecoveryChannelId = null
        previewRetuneTimestampsMs.clear()
        _uiState.update {
            it.copy(
                catchUpSupported = catchUp.supports(acc),
                windowStartMs = GuideTimeTravel.liveWindowStartMs(System.currentTimeMillis()),
                previewChannel = null,
                previewPlayback = null,
                previewMimeOverride = null,
            )
        }
        // Tune the preview to the LAST OPENED channel of this account (TiViMate-style resume).
        // Stalker's stored URL is a dead single-use create_link — skip the auto-resume for it
        // (OK on a row resolves a fresh URL); Xtream/M3U resume with their stable stored URL.
        if (acc.sourceType != XtreamAccount.SOURCE_STALKER) {
            viewModelScope.launch {
                // Skip resume only if a preview for THIS account is already up — a stale preview from
                // the previous account (now cleared above) must never block re-tuning the new one.
                if (GuidePreviewOwnership.belongsTo(_uiState.value.previewChannel?.contentId, acc.id)) return@launch
                liveStore.recents.first()
                    .firstOrNull { it.id.startsWith("${XtreamItemRegistry.PREFIX}${acc.id}:live:") }
                    ?.let { ref ->
                        if (!GuidePreviewOwnership.belongsTo(_uiState.value.previewChannel?.contentId, acc.id)) {
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
        // force=true is only ever user-driven (the error row's Retry, a category-selection edit):
        // clear the panel breaker FIRST (WP6) so the refresh is never met with a fast-fail. The
        // automatic single retryOnce below deliberately does NOT reset.
        if (force) IptvPanelGuard.resetForAccount(acc)
        channelsJob?.cancel()
        // Cache hit for a network-backed category: swap channels in directly, skipping the empty-list
        // + loadingChannels spinner flash on revisit. (FAVORITES/RECENT stay dynamic — not cached here.)
        if (category.special == null || category.special == GuideSpecial.ALL) {
            channelsCache["${acc.id}|${categoryId}"]?.let { cached ->
                _uiState.update { it.copy(selectedCategoryId = categoryId, channels = cached, loadingChannels = false, error = null, focusedChannelId = cached.firstOrNull()?.contentId) }
                primeEpgFor(cached)
                return
            }
        }
        _uiState.update { it.copy(selectedCategoryId = categoryId, channels = emptyList(), focusedChannelId = null, loadingChannels = true, error = null) }
        channelsJob = viewModelScope.launch {
            // null = the panel request FAILED (these panels throw transient 403/500s and
            // rate-limit bursts) — retry once, then surface an error instead of faking "empty".
            val channels: List<GuideChannel>? = when (category.special) {
                GuideSpecial.FAVORITES -> favoriteChannels(acc)
                // Scoped to THIS account: the store keeps one flat profile-wide list (favorites
                // and recents across every playlist), and these rails live inside a provider's
                // guide — the auto-resume above already filters the same way.
                GuideSpecial.RECENT -> liveStore.recents.first()
                    .filter { it.id.startsWith(XtreamItemRegistry.accountPrefix(acc.id)) }
                    .map { GuideChannel(it.id, it.name, it.logo, it.streamUrl, streamIdOf(it.id)) }
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
            primeEpgFor(channels)
        }
    }

    /**
     * Prime now/next for a category that has just been shown. The first channel is marked focused
     * as the list lands, so [onChannelFocused] treats the UI's own focus event for it as a no-op
     * and its window never runs — without this the whole group reads "No information" until the
     * viewer moves off row one.
     */
    private fun primeEpgFor(channels: List<GuideChannel>) {
        GuideEpgPrefetchPolicy.onChannelsLoaded(channels.size).forEach { index ->
            channels.getOrNull(index)?.let { ensureEpg(it.streamId) }
        }
    }

    /** One quiet retry for flaky IPTV panels; second failure bubbles up as null. */
    private suspend fun <T> retryOnce(block: suspend () -> T?): T? =
        block() ?: run { delay(RETRY_DELAY_MS); block() }

    /**
     * Channel got D-pad focus: drive the preview + fetch its now/next EPG. Debounced ~250ms and
     * prefetches a window around the focused channel (see [GuideEpgPrefetchPolicy]) so now/next is
     * present when focus settles, instead of one get_short_epg per composed row, which made fast
     * scrolling feel laggy.
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
            GuideEpgPrefetchPolicy.onFocusChanged(center, channels.size).forEach { index ->
                channels.getOrNull(index)?.let { ensureEpg(it.streamId) }
            }
            // History for the FOCUSED channel alone. GuideEpgPrefetchPolicy prefetches now/next
            // around it because that is one cheap call each; a week of programmes per channel is
            // not, so this one deliberately does not follow the window.
            ensureHistory(channel)
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
        // A user-initiated tune resolves fresh below, so it always deserves a new recovery shot.
        previewLinkRefreshBurntForChannelId = null
        previewContainerRetryBurntForChannelId = null
        previewFreezeRecoveryAttempts = 0
        previewFreezeRecoveryChannelId = null
        previewRetuneTimestampsMs.clear()
        viewModelScope.launch {
            // Stalker needs a fresh create_link; Xtream/M3U reuse the browse-time URL. Then tunePreview
            // applies the playlist's DNS (resolve → rewrite) before handing the URL to the player.
            val playable = resolvedStreamUrl(channel)
            if (playable == null) {
                // The source sometimes knows exactly what went wrong (a Stalker session cap is
                // fixed by closing the other device, not by retrying) — say that instead.
                val specific = account?.let { clientFactory.clientFor(it).lastResolveError }
                _uiState.update { it.copy(error = specific ?: "Couldn't open \"${channel.name}\"") }
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

    // One fresh link per user tune: a second token failure on the same channel means the
    // account/session is the problem, not the link — stop re-minting so we don't hammer the portal.
    private var previewLinkRefreshBurntForChannelId: String? = null

    /** Automatic re-tunes spent on the currently frozen channel (see [GuidePreviewFreezePolicy]). */
    private var previewFreezeRecoveryAttempts: Int = 0
    private var previewFreezeRecoveryChannelId: String? = null

    /**
     * When the last recovery re-tune was started, or 0.
     *
     * A boolean "in flight" flag was not enough: it cleared when the coroutine finished, while the
     * player's own IDLE arrived ~1.5s later and spent an attempt on nothing (Onn 4K, 2026-08-18).
     * The timestamp lets [GuidePreviewFreezePolicy.isSelfInflictedTransition] ignore the whole
     * settling window instead.
     */
    private var previewRetuneStartedAtMs: Long = 0L

    /** When the current preview tune started playing — the "it lasted N" half of the reason. */
    private var previewPlayingSinceMs: Long = 0L

    /** Recent recovery re-tunes, for the rolling-window loop guard. */
    private val previewRetuneTimestampsMs = ArrayDeque<Long>()

    /**
     * The preview reported ENDED/IDLE on a live channel — a dropped feed, not a completion.
     *
     * ExoPlayer raises no error when a provider closes the socket mid-stream, so this is the only
     * signal that the picture has died. Re-tune in place, bounded, and only say something once the
     * attempts are spent (most drops self-heal on the first re-tune).
     */
    fun onPreviewPlaybackStalled(playbackState: Int) {
        // Our own re-prepare passes through IDLE — never treat that as a fresh death.
        val sinceRetune = if (previewRetuneStartedAtMs == 0L) null
            else System.currentTimeMillis() - previewRetuneStartedAtMs
        if (GuidePreviewFreezePolicy.isSelfInflictedTransition(sinceRetune)) return
        val channel = _uiState.value.previewChannel ?: return
        // A different channel than the one we were recovering: its budget starts fresh.
        if (previewFreezeRecoveryChannelId != channel.contentId) {
            previewFreezeRecoveryChannelId = channel.contentId
            previewFreezeRecoveryAttempts = 0
        }
        val playedMs = playedMsForReason()
        // A feed that keeps dying and coming back is looping, not recovering. Stop and say so,
        // rather than reconnecting for ever against a panel that charges a handshake each time.
        if (GuidePreviewFreezePolicy.isRetuneLooping(previewRetuneTimestampsMs.toList(), System.currentTimeMillis())) {
            val reason = GuidePreviewFreezePolicy.freezeReason(
                playbackState = playbackState,
                playedMs = playedMs,
                attemptsUsed = previewRetuneTimestampsMs.size,
            )
            val tech = freezeTechnicalDetail(channel, playbackState)
            android.util.Log.w("LiveGuide", "preview looping on ${channel.name}: $reason [$tech]")
            _uiState.update {
                it.copy(error = "\"${channel.name}\" keeps dropping. $reason\n$tech")
            }
            previewRetuneTimestampsMs.clear()
            previewPlayingSinceMs = 0L
            return
        }
        // The last tune actually worked for a while, so this is a new incident, not a continuation
        // — give the channel its budget back. A tune that never really played does NOT reset,
        // otherwise a render-then-die feed would reconnect for ever.
        if (GuidePreviewFreezePolicy.isNewIncident(playedMs)) {
            previewFreezeRecoveryAttempts = GuidePreviewFreezePolicy.attemptsAfterSuccess()
        }
        // Catch-up replays are launched into PlayerScreen, never this surface (see LiveGuide's
        // onPlayCatchUp doc), so anything playing here is a live feed by construction.
        val isLiveFeed = true
        if (GuidePreviewFreezePolicy.shouldSurfaceError(
                playbackState = playbackState,
                isLiveFeed = isLiveFeed,
                attemptsUsed = previewFreezeRecoveryAttempts,
            )
        ) {
            val reason = GuidePreviewFreezePolicy.freezeReason(
                playbackState = playbackState,
                playedMs = playedMs,
                attemptsUsed = previewFreezeRecoveryAttempts,
            )
            val tech = freezeTechnicalDetail(channel, playbackState)
            android.util.Log.w("LiveGuide", "preview froze on ${channel.name}: $reason [$tech]")
            _uiState.update { it.copy(error = "\"${channel.name}\" stopped. $reason\n$tech") }
            LivePlaybackTelemetry.previewStall(playbackState, previewFreezeRecoveryAttempts, surfaced = true)
            previewPlayingSinceMs = 0L
            return
        }
        if (!GuidePreviewFreezePolicy.shouldRetune(
                playbackState = playbackState,
                isLiveFeed = isLiveFeed,
                attemptsUsed = previewFreezeRecoveryAttempts,
            )
        ) {
            return
        }
        previewFreezeRecoveryAttempts += 1
        LivePlaybackTelemetry.previewStall(playbackState, previewFreezeRecoveryAttempts, surfaced = false)
        val nowMs = System.currentTimeMillis()
        previewRetuneStartedAtMs = nowMs
        previewRetuneTimestampsMs.addLast(nowMs)
        // Keep only the window's worth, so the deque cannot grow across a long viewing session.
        while (previewRetuneTimestampsMs.isNotEmpty() &&
            nowMs - previewRetuneTimestampsMs.first() > GuidePreviewFreezePolicy.RETUNE_WINDOW_MS
        ) {
            previewRetuneTimestampsMs.removeFirst()
        }
        // Each tune measures its own playing time; the next rendered frame restamps it.
        previewPlayingSinceMs = 0L
        android.util.Log.w(
            "LiveGuide",
            "preview stalled (state=$playbackState) on ${channel.name}; re-tune ${previewFreezeRecoveryAttempts}/${GuidePreviewFreezePolicy.MAX_RECOVERY_ATTEMPTS}"
        )
        viewModelScope.launch {
            val acc = account ?: return@launch
            // forceFresh: the URL that just died may be a burnt single-use Stalker link.
            run {
                val fresh = clientFactory.clientFor(acc)
                    .resolveStreamUrl(acc, "live", channel.streamId, forceFresh = true)
                if (fresh.isNullOrBlank()) {
                    val reason = GuidePreviewFreezePolicy.freezeReason(
                        playbackState = playbackState,
                        playedMs = playedMs,
                        attemptsUsed = previewFreezeRecoveryAttempts,
                        resolveError = clientFactory.clientFor(acc).lastResolveError,
                    )
                    val tech = freezeTechnicalDetail(channel, playbackState)
                    android.util.Log.w("LiveGuide", "preview froze on ${channel.name}: $reason [$tech]")
                    _uiState.update { it.copy(error = "\"${channel.name}\" stopped. $reason\n$tech") }
                    previewPlayingSinceMs = 0L
                    return@launch
                }
                tunePreview(channel.copy(streamUrl = fresh))
            }
        }
    }

    /**
     * The credential-free technical footprint of the current preview, for a bug report.
     *
     * Built from the channel's own URL only to derive container + host — never echoed whole,
     * because Xtream paths carry the account user and password.
     */
    private fun freezeTechnicalDetail(channel: GuideChannel, playbackState: Int): String {
        val url = _uiState.value.previewPlayback?.url ?: channel.streamUrl
        return GuidePreviewFreezePolicy.technicalDetail(
            container = com.nuvio.tv.core.analytics.LivePlaybackFreezeReporter.streamContainerOf(url),
            host = GuidePreviewFreezePolicy.hostOf(url),
            playbackState = playbackState,
            attemptsUsed = previewFreezeRecoveryAttempts,
            appVersion = com.nuvio.tv.BuildConfig.VERSION_NAME,
        )
    }

    private fun playedMsForReason(): Long =
        if (previewPlayingSinceMs == 0L) 0L else System.currentTimeMillis() - previewPlayingSinceMs

    /**
     * A frame rendered: whatever we did worked, so the next failure is a new incident.
     *
     * Without this a flaky channel that recovers, plays for minutes and dies again would find its
     * budget already spent and freeze with no recovery (seen on an Onn 4K, 2026-08-18).
     */
    fun onPreviewFramePlayed() {
        if (previewPlayingSinceMs == 0L) previewPlayingSinceMs = System.currentTimeMillis()
        // Video is on screen, so the re-tune has settled — later stalls are genuine again.
        previewRetuneStartedAtMs = 0L
    }

    /**
     * The guide's preview player hit a token-shaped HTTP failure (401/403/410): the Stalker
     * create_link token expired mid-watch, a reconnect consumed a single-use link, or the portal
     * session was rotated by another device on the same MAC. Mint ONE fresh link for the tuned
     * channel and re-tune in place; a repeat failure surfaces the channel error instead.
     */
    fun onPreviewAuthError(httpStatus: Int) {
        if (!com.nuvio.tv.ui.screens.player.isIptvRefreshableHttpStatus(httpStatus)) return
        val channel = _uiState.value.previewChannel ?: return
        if (previewLinkRefreshBurntForChannelId == channel.contentId) {
            _uiState.update { it.copy(error = "Couldn't open \"${channel.name}\"") }
            return
        }
        previewLinkRefreshBurntForChannelId = channel.contentId
        viewModelScope.launch {
            val acc = account ?: return@launch
            // forceFresh: a static-cmd verdict would rebuild the very URL that just answered
            // 401/403/410 — this recovery exists to mint a genuinely new link.
            val fresh = clientFactory.clientFor(acc).resolveStreamUrl(acc, "live", channel.streamId, forceFresh = true)
            if (fresh.isNullOrBlank()) {
                _uiState.update { it.copy(error = "Couldn't open \"${channel.name}\"") }
                return@launch
            }
            tunePreview(channel.copy(streamUrl = fresh))
        }
    }

    // One container retry per tuned channel: if HLS doesn't play either then the container isn't
    // what's wrong, and re-tuning on every error would just spin.
    private var previewContainerRetryBurntForChannelId: String? = null

    /**
     * The preview failed because the bytes aren't the container the URL promised: we asked the panel
     * for `.ts` and it answered with something that isn't MPEG-TS — in practice a 302 to an HLS
     * playlist, which several panels do for every live channel. Re-tune the same channel forcing
     * HLS.
     *
     * Stalker's `create_link` tokens are single-use and the failed attempt already spent this one,
     * so that source needs a freshly minted link (no re-handshake — the portal session is reused).
     * Xtream/M3U URLs are stable and get retried as they are, which keeps the retry free of any
     * extra provider request — the connection budget on a `max_connections=1` line can't spare one.
     */
    fun onPreviewContainerMismatch() {
        val channel = _uiState.value.previewChannel ?: return
        if (previewContainerRetryBurntForChannelId == channel.contentId) return
        previewContainerRetryBurntForChannelId = channel.contentId
        viewModelScope.launch {
            val acc = account ?: return@launch
            val url = if (acc.sourceType == XtreamAccount.SOURCE_STALKER) {
                clientFactory.clientFor(acc).resolveStreamUrl(acc, "live", channel.streamId)
                    ?.takeIf { it.isNotBlank() } ?: return@launch
            } else {
                channel.streamUrl
            }
            tunePreview(
                channel.copy(streamUrl = url),
                mimeOverride = PlayerMediaSourceFactory.CONTAINER_MISMATCH_RETRY_MIME_TYPE
            )
        }
    }

    /**
     * The forced-container retry reached a frame, so the guess was right. Remember it against this
     * host so every later zap on the provider builds the right source first time — the failed
     * attempt is paid once per provider, not once per channel. Deliberately recorded on success
     * only: a guess that never played must not poison the rest of the session.
     */
    fun onPreviewContainerRetryPlayed() {
        val state = _uiState.value
        val mime = state.previewMimeOverride ?: return
        val url = state.previewPlayback?.url ?: return
        PlayerMediaSourceFactory.rememberContainerMimeType(url, mime)
    }

    /**
     * Points the preview at [channel]: sets it synchronously (clearing any stale prepared playback so
     * the screen never loads the previous channel's DoH-rewritten URL) and computes the DoH-prepared
     * URL + headers off-main. The prepare is defensive — any failure yields the plain URL.
     */
    private fun tunePreview(channel: GuideChannel, mimeOverride: String? = null) {
        _uiState.update {
            it.copy(previewChannel = channel, previewPlayback = null, previewMimeOverride = mimeOverride)
        }
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
        // A cooled-down channel is not asked again yet: the panel having no guide for it is the
        // common case (Starshare fills 6% of epg_channel_id), and without this every settle that
        // brings it back into the prefetch window spends another request that cannot succeed.
        // Release the once-only mark so the retry after the cooldown still happens.
        if (!epgAdmission.admits(streamId.toString(), System.currentTimeMillis())) {
            epgRequested.remove(streamId)
            return
        }
        viewModelScope.launch {
            // One gate to the panel's EPG endpoint, matching the mobile/desktop TileEpgQueue's two
            // workers. GuideEpgPrefetchPolicy already bounds a settle to a window of ~17 channels,
            // but it launched all of them at once: 17 concurrent requests at a host that commonly
            // sells max_connections=1 is the same shape (smaller) as the guide fan-out measured at
            // 390 concurrent on mobile.
            epgFetchGate.withPermit {
                val nowMs = System.currentTimeMillis()
            // Per-channel source ladder (replacing the old provider-first `.ifEmpty { mirror }`):
            // (future) manual mapping → the playlist's own short EPG if its rows pass the sanity
            // gate → the mirror's programme window (the timeline needs more than now/next, and the
            // windowed query is a superset of nowNext anyway) → nothing. Present-but-garbage panel
            // rows (the wa12 shape — Starshare fills 6%, and what IS filled can be skew the epoch
            // detector could not prove) no longer suppress the mirror. The answering rung is
            // remembered per (account, channel) for the session, so a mirror-fed channel doesn't
            // re-ask the panel on every guide re-entry.
            val resolution = com.nuvio.tv.core.iptv.EpgSourceLadder.resolveAndRemember(
                memory = com.nuvio.tv.core.iptv.EpgSourceLadder.sessionMemory,
                accountId = acc.id,
                streamId = streamId,
                nowMs = nowMs,
                manual = null,   // the manual-mapping seam — see [EpgSourceLadder.ManualResolver]
                // The account's own guide, ingested once into SQLite. Zero network per channel —
                // this is the rung that makes a guide fling cost nothing. An account with no stored
                // guide answers empty and the ladder falls through exactly as before.
                store = {
                    runCatching {
                        val epgId = matchIndex.liveEpgIdFor(acc.id, streamId)
                        if (epgId.isNullOrBlank()) emptyList()
                        else contentDb.epgNowNext(acc.id, epgId, nowMs).map {
                            XtreamProgram(
                                title = it.title,
                                description = it.desc.orEmpty(),
                                startMs = it.startMs,
                                endMs = it.endMs,
                                nowPlaying = nowMs in it.startMs until it.endMs,
                            )
                        }
                    }.getOrDefault(emptyList())
                },
                // null = the ask FAILED. Collapsing that into emptyList() told the ladder "this
                // panel has no EPG for this channel", which is a coverage claim a timeout cannot
                // support — see EpgSourceLadder.Source.UNAVAILABLE.
                provider = { runCatching { clientFactory.clientFor(acc).shortEpg(acc, streamId).getOrNull() }.getOrNull() },
                mirror = {
                    runCatching { epgMirror.programmesWindow(acc.id, streamId, nowMs, nowMs + GUIDE_EPG_WINDOW_MS) }
                        .getOrDefault(emptyList())
                        .map { XtreamProgram(it.title, it.desc.orEmpty(), it.startMs, it.endMs, nowPlaying = nowMs in it.startMs until it.endMs) }
                },
            )
                val programs = resolution.programmes
                // No rung answered: cool the channel down rather than re-asking on every settle.
                if (programs.isEmpty()) {
                    epgAdmission.recordEmpty(streamId.toString(), nowMs)
                    epgRequested.remove(streamId)
                    return@withPermit
                }
                epgAdmission.recordAnswered(streamId.toString())
                val nowIdx = programs.indexOfFirst { it.nowPlaying || (nowMs in it.startMs until it.endMs) }
                    .takeIf { it >= 0 } ?: 0
                val now = programs.getOrNull(nowIdx)
                val next = programs.getOrNull(nowIdx + 1)
                _uiState.update { it.copy(epg = it.epg + (streamId to GuideEpg(now, next, programs))) }
            }
        }
    }

    /** null = request failed (as opposed to a genuinely empty category). */
    private suspend fun fetchChannels(acc: XtreamAccount, categoryId: String?): List<GuideChannel>? {
        val raw = clientFactory.clientFor(acc).liveChannels(acc, categoryId).getOrNull() ?: return null
        // Map + registry.register the whole result OFF the main thread. "All channels" hands back the
        // entire live catalog (tens of thousands of rows on real panels), and running this pass on the
        // ViewModel's Main dispatcher is a measured cold-load stall + GC churn on weak TV boxes (Onn
        // 4K). registry is a ConcurrentHashMap, so register() is safe on Dispatchers.Default.
        return withContext(Dispatchers.Default) {
            raw.map { ch ->
                val id = XtreamItemRegistry.liveId(acc.id, ch.streamId)
                registry.register(
                    XtreamResolvedItem(
                        id = id, type = ContentType.TV, name = ch.name, poster = ch.logo,
                        streamUrl = ch.streamUrl, kind = XtreamKind.LIVE, accountId = acc.id, streamId = ch.streamId
                    )
                )
                GuideChannel(
                    contentId = id, name = ch.name, logo = ch.logo, streamUrl = ch.streamUrl,
                    streamId = ch.streamId, categoryId = ch.categoryId,
                    hasArchive = ch.hasArchive, catchUpDays = ch.catchUpDays
                )
            }
        }
    }

    /** Live favorites belonging to [acc] — see the RECENT rail for why this is account-scoped. */
    private suspend fun favoriteChannels(acc: XtreamAccount): List<GuideChannel> {
        val prefix = XtreamItemRegistry.accountPrefix(acc.id)
        val favIds = libraryRepository.libraryItems.first()
            .filter { XtreamItemRegistry.isLiveContentId(it.id) && it.id.startsWith(prefix) }
            .map { it.id }
        return favIds.mapNotNull { id ->
            liveStore.refFor(id)?.let { GuideChannel(it.id, it.name, it.logo, it.streamUrl, streamIdOf(it.id)) }
        }
    }

    /** Best-effort streamId from a live content id ("xtream:acc:live:<streamId>"). */
    private fun streamIdOf(contentId: String): Int = contentId.substringAfterLast(":live:").toIntOrNull() ?: 0


    // --- Catch-up: history, time travel, and launching a replay -------------------------------

    /**
     * Makes sure the focused channel's stored guide covers the catch-up window, then republishes
     * its cells from the database.
     *
     * Everything about this call is bounded on purpose: one channel, gated on the stored copy's
     * age, single-flighted, parsed straight from the socket into SQLite, and read back one visible
     * window at a time. The XMLTV out-of-memory crash is what all of that is avoiding.
     */
    private fun ensureHistory(channel: GuideChannel) {
        val acc = account ?: return
        if (!_uiState.value.catchUpSupported || channel.streamId <= 0) return
        viewModelScope.launch {
            runCatching {
                historyFetcher.ensure(
                    playlistId = acc.id,
                    channelId = epgChannelKey(channel.streamId),
                    catchUpDays = channel.catchUpDays,
                    nowMs = System.currentTimeMillis(),
                )
            }
            publishWindow(channel)
        }
    }

    /** Streams get_simple_data_table into the EPG table, atomically per channel, pruning as it goes. */
    private suspend fun refillHistory(playlistId: String, channelId: String, catchUpDays: Int, nowMs: Long) {
        val acc = account ?: return
        val streamId = channelId.removePrefix(EPG_CHANNEL_PREFIX).toIntOrNull() ?: return
        val client = clientFactory.clientFor(acc)
        if (client !is com.nuvio.tv.core.iptv.XtreamClient) return
        val rows = ArrayList<com.nuvio.tv.core.iptv.content.EpgProgramme>(PROGRAMME_CAP)
        val parsed = client.historicalEpgInto(acc, streamId, channelId, nowMs, catchUpDays) { row ->
            // A corrupt feed must not be able to materialize thousands of rows on a TV stick.
            if (rows.size < PROGRAMME_CAP) rows.add(row)
        }
        // A failed fetch stamps nothing, so the next open tries again rather than reading as
        // "this channel has no history" for the life of the install.
        if (parsed.isFailure) return
        contentDb.refillChannelEpg(playlistId, channelId, rows, nowMs)
        contentDb.pruneEpg(playlistId, com.nuvio.tv.core.iptv.CatchUpEpgWindow.pruneCutoffMs(nowMs, catchUpDays))
    }

    /**
     * Republishes one channel's cells for the CURRENT visible window from the database — a windowed
     * read with the description truncated in SQL, so a travelling guide costs one screenful of rows
     * rather than the whole archive. Keeps the panel's now/next when there is no stored history.
     */
    private suspend fun publishWindow(channel: GuideChannel) {
        val acc = account ?: return
        val windowStart = _uiState.value.windowStartMs
        val rows = runCatching {
            contentDb.epgWindow(
                playlistId = acc.id,
                channelId = epgChannelKey(channel.streamId),
                fromMs = windowStart - GuideTimeTravel.WINDOW_MS,
                toMs = windowStart + 2 * GuideTimeTravel.WINDOW_MS,
            )
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        val programmes = rows.map {
            XtreamProgram(
                title = it.title,
                description = it.desc.orEmpty(),
                startMs = it.startMs,
                endMs = it.endMs,
                nowPlaying = nowMs in it.startMs until it.endMs,
                hasArchive = it.hasArchive.takeIf { marked -> marked },
            )
        }
        _uiState.update { state ->
            val existing = state.epg[channel.streamId]
            state.copy(
                epg = state.epg + (channel.streamId to GuideEpg(
                    now = existing?.now ?: programmes.firstOrNull { it.nowPlaying },
                    next = existing?.next,
                    programmes = programmes,
                ))
            )
        }
    }

    /**
     * Moves the visible window [slots] half-hours (negative = back into the archive), clamped to the
     * provider's window, and reloads the focused channel's cells for where it landed.
     */
    fun travelWindow(slots: Int) {
        val channel = _uiState.value.focusedChannel
        val next = GuideTimeTravel.shift(
            currentStartMs = _uiState.value.windowStartMs,
            slots = slots,
            nowMs = System.currentTimeMillis(),
            catchUpDays = channel?.catchUpDays ?: 0,
        )
        if (next == _uiState.value.windowStartMs) return
        _uiState.update { it.copy(windowStartMs = next) }
        channel?.let { viewModelScope.launch { publishWindow(it) } }
    }

    /** BACK out of the timeline, or a channel change: return the guide to now. */
    fun resetWindowToLive() {
        val live = GuideTimeTravel.liveWindowStartMs(System.currentTimeMillis())
        if (live == _uiState.value.windowStartMs) return
        _uiState.update { it.copy(windowStartMs = live) }
        _uiState.value.focusedChannel?.let { viewModelScope.launch { publishWindow(it) } }
    }

    /**
     * The minute tick. Only rolls the window forward while it is still anchored at live — a viewer
     * reading yesterday's schedule must not have it pulled back to now under them.
     */
    fun onMinuteTick(nowMs: Long) {
        val current = _uiState.value.windowStartMs
        val live = GuideTimeTravel.liveWindowStartMs(nowMs)
        if (current != live && !GuideTimeTravel.isAtLiveEdge(current, nowMs - 60_000L)) return
        if (current == live) return
        _uiState.update { it.copy(windowStartMs = live) }
    }

    /**
     * Builds the replay for one programme and hands it to the screen to launch. Start-over and a
     * finished replay take the same path — the difference is only whether the programme's end is
     * still in the future, which the player uses for its clamped seek ceiling.
     */
    fun startReplay(channel: GuideChannel, programme: XtreamProgram) {
        val acc = account ?: return
        val nowMs = System.currentTimeMillis()
        val session = catchUp.begin(
            account = acc,
            channelContentId = channel.contentId,
            channelName = channel.name,
            streamId = channel.streamId,
            programme = com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator.Programme(
                title = programme.title,
                startMs = programme.startMs,
                endMs = programme.endMs,
            ),
            nowMs = nowMs,
        )
        if (session == null) {
            _uiState.update { it.copy(error = "This provider has no recording of \"${programme.title}\"") }
            return
        }
        // Publish the channel list first so BACK from the player returns to a populated guide.
        recordPlayed(channel)
        _uiState.update {
            it.copy(
                replayLaunch = ReplayLaunch(
                    url = session.url,
                    contentId = session.contentId,
                    title = "${channel.name} · ${programme.title}",
                    programmeStartMs = programme.startMs,
                    programmeEndMs = programme.endMs,
                )
            )
        }
    }

    /** The screen navigated; drop the one-shot so a recomposition cannot launch it twice. */
    fun consumeReplayLaunch() {
        if (_uiState.value.replayLaunch != null) _uiState.update { it.copy(replayLaunch = null) }
    }

    fun dismissError() {
        if (_uiState.value.error != null) _uiState.update { it.copy(error = null) }
    }

    /**
     * What one channel's programmes are stored under. Prefixed so the stream-id namespace can never
     * collide with the tvg-ids the XMLTV ingest writes into the same table.
     */
    private fun epgChannelKey(streamId: Int): String = "$EPG_CHANNEL_PREFIX$streamId"

    companion object {
        private const val ALL_ID = "__all"
        private const val ALL_CAP = 600   // ponytail: don't render 26k rows; categories are the real browse path
        private const val EPG_FOCUS_DEBOUNCE_MS = 250L   // wait for focus to settle before fetching EPG

        /** Concurrent EPG requests allowed at the panel — the same ceiling TileEpgQueue uses, and
         *  the same one iptvnator settled on for the identical reason (providers rate-limit). */
        private const val EPG_FETCH_PERMITS = 2
        private const val GUIDE_EPG_WINDOW_MS = 3 * 60 * 60 * 1000L  // mirror-fallback fetch span for the timeline
        private const val RETRY_DELAY_MS = 1000L         // pause before the single panel-flake retry
        private const val EPG_CHANNEL_PREFIX = "sid:"    // keeps stream ids out of the tvg-id namespace
        private const val PROGRAMME_CAP = 2_000          // a corrupt feed must not materialize a catalog
    }
}
