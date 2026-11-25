package com.nuvio.tv.ui.screens.multiview

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.analytics.AppExitReporter
import com.nuvio.tv.core.telemetry.playback.PlaybackTelemetry
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.multiview.DecoderCeilingStore
import com.nuvio.tv.core.multiview.DecoderFactsPort
import com.nuvio.tv.core.multiview.MemoryClass
import com.nuvio.tv.core.memory.AppMemory
import com.nuvio.tv.core.memory.MemoryTier
import com.nuvio.tv.core.multiview.MultiviewBufferBudget
import com.nuvio.tv.core.multiview.MultiviewGridStore
import com.nuvio.tv.core.multiview.MultiviewQueue
import com.nuvio.tv.core.multiview.TileLoadBounds
import com.nuvio.tv.core.multiview.TileProgramme
import com.nuvio.tv.core.multiview.TileOpenGate
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.playback.android.AndroidAudioFocusPort
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.host.AndroidCleanLiveHostFactory
import com.nuvio.tv.playback.host.AndroidCleanLiveHostInput
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import com.nuvio.tv.playback.multiview.AndroidAudibleTileMediaSession
import com.nuvio.tv.playback.multiview.MultiviewCoordinator
import com.nuvio.tv.playback.multiview.TileHostPaneFactory
import com.nuvio.tv.playback.multiview.TileHostPanePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the multiview session for the screen's back-stack entry: the controller, the tile surfaces, the one audio-focus
 * request and the one media session. It is released when the entry goes (Back out of multiview), which releases
 * every tile player.
 */
@HiltViewModel
internal class MultiviewViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val profileManager: ProfileManager,
    hostFactory: AndroidCleanLiveHostFactory,
    channelSelection: LiveChannelSelectionPort,
    queue: MultiviewQueue,
    gridStore: MultiviewGridStore,
    tileBounds: TileLoadBounds,
    decoderFacts: DecoderFactsPort,
    decoderCeilings: DecoderCeilingStore,
    private val accountStore: XtreamAccountStore,
    private val registry: XtreamItemRegistry,
    private val displayLookup: com.nuvio.tv.core.iptv.IptvItemDisplayLookup,
    private val contentDb: com.nuvio.tv.core.iptv.content.IptvContentDb,
    private val xmltv: com.nuvio.tv.core.iptv.epg.XmltvClient,
    private val playingChannels: com.nuvio.tv.core.radar.alerts.SportsAlertPlayingChannels,
) : ViewModel() {
    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val surfaces = TileSurfaceRegistry(appContext)
    private val profile = PlaybackProfileId(profileManager.activeProfileId.value.toString())

    @Volatile
    private var allowedPrefixes: List<String> = emptyList()
    private var started = false

    private val coordinator = MultiviewCoordinator(
        scope = ownerScope,
        focus = AndroidAudioFocusPort(appContext),
        mediaSession = AndroidAudibleTileMediaSession(appContext, Looper.getMainLooper(), ownerScope),
    )

    private lateinit var controllerRef: MultiviewController

    private val paneFactory = TileHostPaneFactory(
        scope = ownerScope,
        profileId = profile,
        hostFactory = hostFactory,
        channelSelection = channelSelection,
        bounds = tileBounds,
        gate = TileOpenGate(nowMs = { System.currentTimeMillis() }),
        tileCount = { controllerRef.state.value.tileCount },
        nowMs = { System.currentTimeMillis() },
        hostInput = { slot, tile ->
            AndroidCleanLiveHostInput(
                context = appContext,
                preferenceProfileId = profile,
                parentScope = ownerScope,
                activity = checkNotNull(surfaces.activity) { "multiview screen is not bound to an activity" },
                lifecycle = checkNotNull(surfaces.lifecycle) { "multiview screen is not bound to a lifecycle" },
                surfaceOwner = surfaces.owner(controllerRef.tileIdAt(slot)),
                previewViewport = controllerRef.tilePixelSize(slot)?.let { (w, h) -> VideoDimensions(w, h) },
                tile = tile,
            )
        },
    )

    val controller: MultiviewController = MultiviewController(
        queue = queue,
        store = gridStore,
        scope = ownerScope,
        audioFocus = coordinator,
        createPlayer = { slot, bytes, changed -> paneFactory.create(slot, bytes, changed) },
        showAudible = { player -> coordinator.showAudible(player as? TileHostPanePlayer) },
        mayPlay = { channel -> allowedPrefixes.any { channel.key.startsWith(it) } },
        totalBufferBytes = MultiviewBufferBudget.totalBytes(Runtime.getRuntime().maxMemory() / (1024L * 1024L)),
        decoderFacts = decoderFacts,
        ceilings = decoderCeilings,
        memoryClass = {
            when (AppMemory.effectiveTier()) {
                MemoryTier.LOW -> MemoryClass.LOW
                MemoryTier.MID -> MemoryClass.MID
                MemoryTier.HIGH -> MemoryClass.HIGH
            }
        },
        nameLookup = ::channelName,
        programmeLookup = ::channelProgrammes,
    ).also { controllerRef = it }

    /** The channel's name from the registry the guide fills, then the catalogs this device holds. No network. */
    private suspend fun channelName(key: String): String? {
        registry.get(key)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        val parsed = XtreamItemRegistry.parseId(key)?.takeIf { it.kind == "live" } ?: return null
        val streamId = parsed.streamId.toIntOrNull() ?: return null
        return displayLookup.display(parsed.accountId, com.nuvio.tv.core.iptv.XtreamKind.LIVE, streamId)?.name
    }

    /** The channel's stored programmes, read the way the guide reads them (stored table, XMLTV match, Stalker bulk). */
    private suspend fun channelProgrammes(key: String, fromMs: Long, toMs: Long): List<TileProgramme> {
        val parsed = XtreamItemRegistry.parseId(key)?.takeIf { it.kind == "live" } ?: return emptyList()
        val streamId = parsed.streamId.toIntOrNull() ?: return emptyList()
        val account = accountStore.accounts.first().firstOrNull { it.id == parsed.accountId } ?: return emptyList()
        val rows = contentDb.epgWindow(account.id, "sid:$streamId", fromMs, toMs)
            .ifEmpty { xmltv.storedWindow(account, streamId, fromMs, toMs) }
            .ifEmpty {
                if (account.sourceType != XtreamAccount.SOURCE_STALKER) emptyList()
                else contentDb.epgWindow(account.id, streamId.toString(), fromMs, toMs)
            }
        return rows.map { TileProgramme(it.title, it.startMs, it.endMs) }
    }

    /** The profile's playlists, for the channel picker. */
    val accounts: StateFlow<List<XtreamAccount>> = accountStore.accounts
        .stateIn(ownerScope, SharingStarted.Eagerly, emptyList())

    /** Every tile's channel is "playing" for sports alerts; this screen clears only what it published. */
    private var publishedChannels: Set<String> = emptySet()

    private var persistedTiles = 0

    init {
        ownerScope.launch {
            controller.state.collect { state ->
                surfaces.retain(state.tileIds.toSet())
                publishPlayingChannels(state.tiles.mapNotNull { it.channelKey }.toSet())
                observeForTelemetry(state)
            }
        }
    }

    /**
     * Playback telemetry: tiles open / playing / newly failed feed the session tracker (counts only, one
     * `multiview_session` event at close), and the open tile count is kept for the next launch's `app_exit`
     * (`prev_tiles`) whenever it changes. No I/O beyond one SharedPreferences write per change.
     */
    private fun observeForTelemetry(state: MultiviewUiState) {
        if (!started || !state.started) return
        PlaybackTelemetry.multiviewTiles(
            tileCount = state.tileCount,
            playing = state.tiles.count { it.status == com.nuvio.tv.core.multiview.TileStatus.PLAYING },
            failedTileIds = state.tiles
                .filter { it.status == com.nuvio.tv.core.multiview.TileStatus.FAILED }
                .map { it.tileId }
                .toSet(),
        )
        if (state.tileCount != persistedTiles) {
            persistedTiles = state.tileCount
            AppExitReporter.recordMultiviewTiles(appContext, state.tileCount)
        }
    }

    private fun publishPlayingChannels(now: Set<String>) {
        (publishedChannels - now).forEach(playingChannels::clear)
        (now - publishedChannels).forEach(playingChannels::set)
        publishedChannels = now
    }

    /** The screen's host, before any tile opens. */
    fun bind(activity: Activity?, lifecycle: Lifecycle) {
        surfaces.activity = activity
        surfaces.lifecycle = lifecycle
    }

    fun surfaceOwner(tileId: Int) = surfaces.owner(tileId)

    /** Opens the grid once per entry; later calls (recomposition, a returning screen) do nothing. */
    fun start(startKey: String?) {
        if (started) return
        started = true
        PlaybackTelemetry.multiviewOpened()
        ownerScope.launch {
            // The playlists decide which channels this profile may watch: they must be known before tiles restore.
            allowedPrefixes = accountStore.accounts.first().map { XtreamItemRegistry.accountPrefix(it.id) }
            controller.start(startKey)
            accountStore.accounts.collect { list -> allowedPrefixes = list.map { XtreamItemRegistry.accountPrefix(it.id) } }
        }
    }

    override fun onCleared() {
        // Before the controller releases the tiles: the last observed state is what the session ended with.
        PlaybackTelemetry.multiviewClosed()
        AppExitReporter.recordMultiviewTiles(appContext, 0)
        publishPlayingChannels(emptySet())
        controller.release()
        coordinator.release()
        ownerScope.cancel()
    }
}
