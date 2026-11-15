package com.nuvio.tv.ui.screens.multiview

import com.nuvio.tv.core.multiview.AudioMode
import com.nuvio.tv.core.multiview.Capacity
import com.nuvio.tv.core.multiview.CeilingLedgerPolicy
import com.nuvio.tv.core.multiview.DecoderCeilingStore
import com.nuvio.tv.core.multiview.DecoderFacts
import com.nuvio.tv.core.multiview.DecoderFactsPort
import com.nuvio.tv.core.multiview.MemoryClass
import com.nuvio.tv.core.multiview.MultiviewCapacityPolicy
import com.nuvio.tv.core.multiview.TileCodec
import com.nuvio.tv.core.multiview.TileLoad
import com.nuvio.tv.core.multiview.MultiviewAudioFocus
import com.nuvio.tv.core.multiview.MultiviewBufferBudget
import com.nuvio.tv.core.multiview.MultiviewDirection
import com.nuvio.tv.core.multiview.MultiviewFocusPolicy
import com.nuvio.tv.core.multiview.MultiviewFullscreenPolicy
import com.nuvio.tv.core.multiview.MultiviewGridStore
import com.nuvio.tv.core.multiview.MultiviewLayout
import com.nuvio.tv.core.multiview.MultiviewLayoutChoices
import com.nuvio.tv.core.multiview.MultiviewLayoutPolicy
import com.nuvio.tv.core.multiview.MultiviewLeaveGuard
import com.nuvio.tv.core.multiview.MultiviewNotice
import com.nuvio.tv.core.multiview.MultiviewTileMenuPolicy
import com.nuvio.tv.core.multiview.TileFailurePolicy
import com.nuvio.tv.core.multiview.TileNowPlayingPolicy
import com.nuvio.tv.core.multiview.TileProgramme
import com.nuvio.tv.core.multiview.MultiviewPanePlayer
import com.nuvio.tv.core.multiview.MultiviewQueue
import com.nuvio.tv.core.multiview.MultiviewSessionCore
import com.nuvio.tv.core.multiview.SavedGrid
import com.nuvio.tv.core.multiview.TileAction
import com.nuvio.tv.core.multiview.TileBackupPolicy
import com.nuvio.tv.core.multiview.TileFailure
import com.nuvio.tv.core.multiview.TileRect
import com.nuvio.tv.core.multiview.TileStatus
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.multiview.AudibleTileSource
import com.nuvio.tv.playback.multiview.TileChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A channel chosen in the picker. [key] is the stable channel id; [name] is its display name from the guide data. */
internal data class ChannelPick(val key: String, val name: String)

internal data class MultiviewTileUi(
    val slot: Int,
    /** Identifies this tile's video surface. It follows the tile when a removal shifts the slots down. */
    val tileId: Int,
    val rect: TileRect,
    val channelKey: String?,
    val name: String?,
    val nowPlaying: String?,
    val status: TileStatus,
    val failure: TileFailure?,
    val reconnecting: Boolean,
    /** Back from full screen, this tile was closed and is opening again: it says "Rejoining…", not "Tuning in…". */
    val rejoining: Boolean,
    val focused: Boolean,
    val audible: Boolean,
    val pinned: Boolean,
    /** Focused, but silent because the sound is kept on another tile: the muted badge says why. */
    val mutedByPin: Boolean = false,
) {
    /** The failed tile's actions in menu order, first = what OK does; empty while the tile is not failed. */
    val failureActions: List<TileAction> get() = if (status == TileStatus.FAILED) failure?.actions.orEmpty() else emptyList()
}

internal data class MultiviewUiState(
    val started: Boolean = false,
    val layout: MultiviewLayout = MultiviewLayout.SIDE_BY_SIDE,
    val tileCount: Int = 0,
    val tiles: List<MultiviewTileUi> = emptyList(),
    val focusedSlot: Int = 0,
    val audibleSlot: Int? = null,
    val pinnedSlot: Int? = null,
    /** The tile shown alone while the others are closed; null in the grid. */
    val fullscreenSlot: Int? = null,
    /** The one notice showing, if any. [noticeSerial] changes with every new notice so its timer restarts. */
    val notice: MultiviewNotice? = null,
    val noticeSerial: Int = 0,
    /** A slot added by "Add another channel" that no channel has been picked for yet; cancelling the picker drops it. */
    val pendingSlot: Int? = null,
) {
    val tileIds: List<Int> get() = tiles.mapNotNull { t -> t.tileId.takeIf { it >= 0 } }
}

/**
 * The state holder of the multiview screen: one [MultiviewSessionCore], the tile players it creates, and the
 * decisions the screen must not make (full screen closes the other tiles and reopens them on Back, what is
 * remembered, which surface belongs to which tile). Composables read [state] and call the functions below;
 * none of this does I/O of its own, and it never polls: a tile changes state when its player says so.
 *
 * Main thread only, like the core. Lifecycle comes from the screen: [onResume] and [onStop].
 */
internal class MultiviewController(
    private val queue: MultiviewQueue,
    private val store: MultiviewGridStore,
    private val scope: CoroutineScope,
    private val audioFocus: MultiviewAudioFocus,
    /** Builds the tile player for (slot, buffer bytes, changed callback). */
    private val createPlayer: (Int, Int, () -> Unit) -> MultiviewPanePlayer<TileChannel>,
    /** Tells the one media session which tile is audible; called after every core change. */
    private val showAudible: (MultiviewPanePlayer<TileChannel>?) -> Unit,
    private val mayPlay: (TileChannel) -> Boolean,
    private val totalBufferBytes: Int,
    /** What this decoder can do, read once off the main thread. */
    private val decoderFacts: DecoderFactsPort = DecoderFactsPort { DecoderFacts() },
    private val ceilings: DecoderCeilingStore = object : DecoderCeilingStore {
        override suspend fun load() = emptyMap<TileCodec, Int>()
        override suspend fun save(codec: TileCodec, comfortable: Int) = Unit
    },
    private val memoryClass: () -> MemoryClass = { MemoryClass.HIGH },
    /**
     * The next backup for a channel that will not play: given the failed channel's key and the keys already tried, a
     * channel or null. TV has no backup source yet, so the default finds none; the chain is ready for one.
     */
    private val findBackup: (failedKey: String, tried: Set<String>) -> TileChannel? = { _, _ -> null },
    /** The channel's display name from what this device already holds, by key. Null when unknown. */
    private val nameLookup: suspend (key: String) -> String? = { null },
    /** The channel's programmes from the guide data already stored on the device, no network. */
    private val programmeLookup: suspend (key: String, fromMs: Long, toMs: Long) -> List<TileProgramme> = { _, _, _ -> emptyList() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(MultiviewUiState())
    val state: StateFlow<MultiviewUiState> = mutableState.asStateFlow()

    private var core: MultiviewSessionCore<TileChannel>? = null
    private val tileIds = IntArray(MultiviewBufferBudget.MAX_PANES) { -1 }
    private var nextTileId = 0
    /** Display names by channel key: the saved name, the picker's, and the live lookup (which wins). */
    private val names = HashMap<String, String>()
    private val programmes = HashMap<String, List<TileProgramme>>()
    private var nowRefreshJob: Job? = null
    private val leaveGuard = MultiviewLeaveGuard()
    private var notice: MultiviewNotice? = null
    private var noticeSerial = 0
    private var pendingSlot: Int? = null

    /** Tiles opened by a pick: a failure within [PICK_NOTICE_WINDOW_MS] also says so in a notice. */
    private val pickWatch = HashMap<Int, Pair<String, Long>>()

    /** Tiles that were closed by full screen and are reopening. */
    private val rejoiningTiles = HashSet<Int>()
    private var fullscreenSlot: Int? = null

    /** Channels of the closed tiles while one is full screen: reopened on Back. */
    private var hidden: Map<Int, TileChannel> = emptyMap()
    private var resumed = false
    private var released = false

    private var facts = DecoderFacts()
    private var capacityNoticeShown = false
    private val ceilingLearnedFrom = HashSet<Int>()
    private val backups = TileBackupPolicy()
    private val backupConsidered = HashSet<Int>()
    private var switchingToBackup = false

    /** Pixel size of the video area, for each tile's decode size hint. 0 = not measured yet. */
    var viewportWidthPx: Int = 0
    var viewportHeightPx: Int = 0

    private var stageWidthDp = MultiviewLayoutPolicy.SAFE_WIDTH_DP
    private var stageHeightDp = MultiviewLayoutPolicy.SAFE_HEIGHT_DP

    /**
     * The area the grid is laid out in, in dp and pixels. It shrinks while the tile menu or the layout panel is open
     * (the grid makes room, so every picture stays visible); only the geometry changes, nothing restarts. The screen
     * reports it only for the grid, never while a tile is full screen.
     */
    fun setStage(widthDp: Float, heightDp: Float, widthPx: Int, heightPx: Int) {
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        if (widthDp == stageWidthDp && heightDp == stageHeightDp) return
        stageWidthDp = widthDp
        stageHeightDp = heightDp
        if (core != null) publish()
    }

    /** The slot's current surface id, read when the slot's player is created. */
    fun tileIdAt(slot: Int): Int = tileIds.getOrElse(slot) { -1 }

    /** The tile's size in pixels in the current arrangement, or null when the video area is not measured. */
    fun tilePixelSize(slot: Int): Pair<Int, Int>? {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return null
        val rect = currentRects().firstOrNull { it.id == slot } ?: return null
        val w = (rect.width * viewportWidthPx).toInt()
        val h = (rect.height * viewportHeightPx).toInt()
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * Opens the screen's grid. [startKey] (the channel the viewer was watching) becomes tile 0; channels queued with
     * "Add to Multiview" follow; with neither, the last grid is restored (owner decision 5).
     */
    suspend fun start(startKey: String?) {
        if (core != null || released) return
        val saved = runCatching { store.load() }.getOrNull()
        saved?.let { grid -> grid.keys.forEachIndexed { i, key -> if (key != null) grid.names.getOrNull(i)?.let { names[key] = it } } }
        val queued = (listOfNotNull(startKey) + queue.take()).distinct().take(MultiviewLayoutPolicy.MAX_TILES)
        val layout: MultiviewLayout
        val keys: List<String?>
        when {
            queued.isNotEmpty() -> {
                val count = maxOf(2, queued.size)
                layout = saved?.let { MultiviewLayoutPolicy.layoutFor(MultiviewLayoutPolicy.familyOf(it.layout), count) }
                    ?: MultiviewLayoutPolicy.defaultFor(count)
                keys = queued + List(count - queued.size) { null }
            }
            saved != null -> {
                layout = saved.layout
                keys = saved.keys
            }
            else -> {
                layout = MultiviewLayout.SIDE_BY_SIDE
                keys = listOf(null, null)
            }
        }
        facts = runCatching { decoderFacts.facts() }.getOrDefault(DecoderFacts())
            .let { base -> base.copy(observedComfortable = runCatching { ceilings.load() }.getOrDefault(emptyMap())) }
        val created = MultiviewSessionCore(
            initialLayout = layout,
            initialTileCount = keys.size,
            totalBufferBytes = totalBufferBytes,
            audioFocus = audioFocus,
            createPlayer = { slot, bytes, changed -> createPlayer(slot, bytes, changed) },
            onChanged = ::onCoreChanged,
            mayPlayChannel = mayPlay,
        )
        core = created
        keys.forEachIndexed { slot, key ->
            val channel = key?.let(::channelOf)?.takeIf { mayPlay(it) } ?: return@forEachIndexed
            tileIds[slot] = nextTileId++
            created.assignChannel(slot, channel)
        }
        if (resumed) created.onForeground()
        // The viewer added these tiles: the same one-time heads-up as adding one later.
        noteIfTight(wasTight = false)
        publish()
        keys.filterNotNull().forEach(::refreshChannelFacts)
        // What opened is what "resume last grid" brings back, even if the viewer changes nothing.
        persist()
    }

    // ---- lifecycle ----

    /** The screen is RESUMED: tiles (re)open through the normal pipeline. */
    fun onResume() {
        if (released) return
        resumed = true
        val c = core ?: return
        // Every player was released at ON_STOP: a fresh surface each, so a late release cannot touch a reopened tile.
        for (slot in 0 until c.paneCount) if (c.channelAt(slot) != null) tileIds[slot] = nextTileId++
        c.onForeground()
        publish()
        for (slot in 0 until c.paneCount) c.channelAt(slot)?.key?.let(::refreshChannelFacts)
    }

    /** ON_STOP: nothing keeps loading, decoding or holding audio focus in the background. */
    fun onStop() {
        resumed = false
        nowRefreshJob?.cancel()
        nowRefreshJob = null
        core?.onBackground()
        publish()
    }

    fun release() {
        if (released) return
        released = true
        resumed = false
        nowRefreshJob?.cancel()
        core?.release()
        showAudible(null)
        publish()
    }

    // ---- focus and navigation ----

    fun focus(slot: Int) {
        val c = core ?: return
        if (fullscreenSlot != null && fullscreenSlot != slot) return
        c.focusPane(slot)
    }

    /** D-pad move to the neighbouring tile; false at an edge (never wraps, never leaves the area). */
    fun move(direction: MultiviewDirection): Boolean {
        val c = core ?: return false
        if (fullscreenSlot != null) return false
        val next = MultiviewFocusPolicy.neighbour(currentRects(), c.focusedPane, direction) ?: return false
        leaveGuard.disarm()
        if (notice == MultiviewNotice.LeaveGuard) notice = null
        c.focusPane(next)
        return true
    }

    // ---- full screen ----

    /** Shows [slot] alone. The other tiles are closed (not hidden): Back reconnects them. */
    fun enterFullscreen(slot: Int) {
        val c = core ?: return
        if (fullscreenSlot != null || c.channelAt(slot) == null) return
        c.focusPane(slot)
        fullscreenSlot = slot
        hidden = MultiviewFullscreenPolicy.hiddenSlots(c.paneCount, slot)
            .mapNotNull { id -> c.channelAt(id)?.let { id to it } }.toMap()
        for (id in hidden.keys) {
            c.clearPane(id)
            tileIds[id] = -1
        }
        publish()
    }

    fun exitFullscreen() {
        val c = core ?: return
        if (fullscreenSlot == null) return
        fullscreenSlot = null
        val reopen = hidden
        hidden = emptyMap()
        for ((slot, channel) in reopen) {
            tileIds[slot] = nextTileId++
            rejoiningTiles += tileIds[slot]
            c.assignChannel(slot, channel)
        }
        publish()
        persist()
    }

    // ---- tiles ----

    /**
     * Puts a channel on [slot]. One already on screen is not duplicated: focus goes to its tile (navigation, not a
     * limit). A pick this profile may not watch is refused before any tile exists, and says so.
     */
    fun assign(slot: Int, pick: ChannelPick) {
        val c = core ?: return
        val existing = (0 until c.paneCount).firstOrNull { c.channelAt(it)?.key == pick.key }
        if (existing != null) {
            if (existing != slot) {
                dropPendingSlot(except = existing)
                c.focusPane(existing)
                postNotice(MultiviewNotice.AlreadyOnScreen(pick.name))
            }
            return
        }
        val channel = channelOf(pick.key)
        if (!mayPlay(channel)) {
            pendingSlot = null
            postNotice(MultiviewNotice.CannotAdd(pick.name))
            return
        }
        val wasTight = assessNow() is Capacity.Tight
        names[pick.key] = pick.name
        pendingSlot = null
        tileIds[slot] = nextTileId++
        pickWatch[tileIds[slot]] = pick.name to nowMs()
        c.assignChannel(slot, channel)
        // The highlight goes to the tile that received the channel (it is loading, or already says why it failed).
        if (c.focusedPane != slot) c.focusPane(slot)
        // Adding a tile always goes ahead; at most it says the box may struggle, once.
        noteIfTight(wasTight)
        publish()
        persist()
        refreshChannelFacts(pick.key)
    }

    /**
     * "Add another channel": appends an empty slot in the same layout family and returns its index, or null at four
     * tiles. [cancelPendingSlot] takes it away again if the picker is backed out of.
     */
    fun addSlot(): Int? {
        val c = core ?: return null
        if (fullscreenSlot != null || c.paneCount >= MultiviewLayoutPolicy.MAX_TILES) return null
        val count = c.paneCount + 1
        val slot = c.paneCount
        c.setLayout(MultiviewLayoutPolicy.layoutFor(MultiviewLayoutPolicy.familyOf(c.layout), count), count)
        pendingSlot = slot
        publish()
        return slot
    }

    /** The picker was closed without a pick: a slot it was opened for by [addSlot] goes away, leaving no stray tile. */
    fun cancelPendingSlot() {
        if (dropPendingSlot(except = null)) {
            publish()
            persist()
        }
    }

    private fun dropPendingSlot(except: Int?): Boolean {
        val c = core ?: return false
        val slot = pendingSlot ?: return false
        pendingSlot = null
        if (slot == except || c.channelAt(slot) != null || slot >= c.paneCount || c.paneCount <= MultiviewTileMenuPolicy.MIN_SLOTS) return false
        c.removePane(slot)
        for (id in slot until MultiviewBufferBudget.MAX_PANES - 1) tileIds[id] = tileIds[id + 1]
        tileIds[MultiviewBufferBudget.MAX_PANES - 1] = -1
        return true
    }

    /** Leaving the grid: false when the first Back only armed the guard (the notice says so). */
    fun requestLeave(): Boolean {
        val channels = core?.let { c -> (0 until c.paneCount).count { c.channelAt(it) != null } } ?: 0
        return when (leaveGuard.onBack(nowMs(), channels)) {
            MultiviewLeaveGuard.Result.LEAVE -> true
            MultiviewLeaveGuard.Result.ARMED -> {
                postNotice(MultiviewNotice.LeaveGuard)
                false
            }
        }
    }

    /** The channel the viewer hears (or the first one on screen): where Back from a grid opened from the player returns. */
    fun audibleChannelKey(): String? {
        val c = core ?: return null
        return c.audibleNow?.let { c.channelAt(it)?.key }
            ?: (0 until c.paneCount).firstNotNullOfOrNull { c.channelAt(it)?.key }
    }

    fun dismissNotice(serial: Int) {
        if (serial != noticeSerial || notice == null) return
        notice = null
        publish()
    }

    private fun postNotice(next: MultiviewNotice) {
        notice = next
        noticeSerial++
        publish()
    }

    /** The load of every tile that has a channel. Until a tile shows its first picture it counts as 1080p50 AVC. */
    private fun currentLoads(): List<TileLoad> {
        val c = core ?: return emptyList()
        return (0 until c.paneCount).filter { c.channelAt(it) != null }.map { slot ->
            val output = (c.playerAt(slot) as? AudibleTileSource)?.snapshot?.value?.videoOutputFacts
            val height = output?.dimensions?.height
            val rate = output?.frameRate
            if (height != null && rate != null && rate > 0f) TileLoad(TileCodec.AVC, height, rate) else TileLoad.ASSUMED
        }
    }

    private fun assessNow(): Capacity = MultiviewCapacityPolicy.assess(facts, currentLoads(), memoryClass())

    private fun noteIfTight(wasTight: Boolean) {
        if (capacityNoticeShown || wasTight) return
        if (assessNow() !is Capacity.Tight) return
        val about = MultiviewCapacityPolicy.comfortableTiles(facts, memoryClass()) ?: return
        capacityNoticeShown = true
        notice = MultiviewNotice.Capacity(about)
        noticeSerial++
    }

    /** A tile that ran out of decoders while others were open teaches the ledger how many this box manages. */
    private fun learnCeiling(tiles: List<MultiviewTileUi>) {
        if (released) return
        val open = tiles.count { it.status != TileStatus.EMPTY }
        for (tile in tiles) {
            val failure = tile.failure ?: continue
            if (tile.status != TileStatus.FAILED || !ceilingLearnedFrom.add(tile.tileId)) continue
            val codec = TileCodec.AVC
            val learned = CeilingLedgerPolicy.learned(failure, open, facts.observedComfortable[codec]) ?: continue
            facts = facts.copy(observedComfortable = facts.observedComfortable + (codec to learned))
            scope.launch { runCatching { ceilings.save(codec, learned) } }
        }
    }

    fun retry(slot: Int) {
        val c = core ?: return
        if (c.channelAt(slot) == null) return
        tileIds[slot] = nextTileId++
        c.retryPane(slot)
    }

    /** Takes the tile away; the others keep playing and move down a slot. The last tile is emptied instead. */
    fun remove(slot: Int) {
        val c = core ?: return
        if (fullscreenSlot != null) return
        // A grid never has fewer than two slots: with two, "remove" empties the tile.
        if (c.paneCount <= MultiviewTileMenuPolicy.MIN_SLOTS) {
            c.clearPane(slot)
            tileIds[slot] = -1
        } else {
            c.removePane(slot)
            for (id in slot until MultiviewBufferBudget.MAX_PANES - 1) tileIds[id] = tileIds[id + 1]
            tileIds[MultiviewBufferBudget.MAX_PANES - 1] = -1
        }
        persist()
        publish()
    }

    /** Re-arranges the tiles that are there ("Same size" or "One big"); the tile count never changes here. */
    fun setLayout(family: MultiviewLayoutPolicy.Family) {
        val c = core ?: return
        if (fullscreenSlot != null) return
        val choice = MultiviewLayoutChoices.options(c.paneCount).first { MultiviewLayoutPolicy.familyOf(it.layout) == family }
        c.setLayout(choice.layout, c.paneCount)
        persist()
        publish()
    }

    fun pinAudio(slot: Int) {
        core?.pinAudio(slot)
    }

    fun unpinAudio() {
        core?.unpinAudio()
    }

    /** Resolves a channel's name and programmes from what the device holds. Both reads are local, never the network. */
    private fun refreshChannelFacts(key: String) {
        scope.launch {
            runCatching { nameLookup(key) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                if (names[key] != it) { names[key] = it; if (!released) publish() }
            }
            val now = nowMs()
            val found = runCatching { programmeLookup(key, now, now + PROGRAMME_WINDOW_MS) }.getOrDefault(emptyList())
            if (programmes[key] != found) { programmes[key] = found; if (!released) publish() }
        }
    }

    /** One wake-up at the next programme change on screen, so line 2 is never a title that has ended. No request. */
    private fun scheduleNowRefresh(c: MultiviewSessionCore<TileChannel>) {
        if (!resumed || released) return
        val now = nowMs()
        val next = (0 until c.paneCount).mapNotNull { slot ->
            c.channelAt(slot)?.key?.let { programmes[it] }?.let { TileNowPlayingPolicy.nextChangeAtMs(it, now) }
        }.minOrNull()
        nowRefreshJob?.cancel()
        nowRefreshJob = null
        if (next == null) return
        nowRefreshJob = scope.launch {
            delay((next - now).coerceAtLeast(MIN_NOW_REFRESH_MS))
            publish()
        }
    }

    // ---- internals ----

    private fun channelOf(key: String) = TileChannel(key = key, contentId = ProviderSelectionId(key))

    private fun currentRects(): List<TileRect> {
        val c = core ?: return emptyList()
        val only = fullscreenSlot
        return if (only != null) listOf(TileRect(only, 0f, 0f, 1f, 1f))
        else MultiviewLayoutPolicy.bounds(c.layout, c.paneCount, stageWidthDp, stageHeightDp)
    }

    private fun onCoreChanged() {
        publish()
    }

    private fun persist() {
        val c = core ?: return
        // The grid is remembered as it is laid out, never as the one-tile view while a tile is full screen.
        if (fullscreenSlot != null || released) return
        val keys = (0 until c.paneCount).map { c.channelAt(it)?.key }
        val grid = SavedGrid(c.layout, keys, keys.map { key -> key?.let { names[it] } })
        scope.launch { runCatching { store.save(grid) } }
    }

    private fun publish() {
        val c = core
        if (c == null) {
            mutableState.value = MultiviewUiState()
            return
        }
        showAudible(c.audibleNow?.let { c.playerAt(it) })
        val pinned = (c.audioMode as? AudioMode.Pinned)?.tileId
        val tiles = currentRects().map { rect ->
            val slot = rect.id
            val channel = c.channelAt(slot)
            val playback = c.playbackAt(slot)
            val status = statusOf(channel != null, c.playerAt(slot) != null, playback.failure, playback.isPlaying, playback.isReconnecting)
            val playerTitle = (c.playerAt(slot) as? AudibleTileSource)?.metadata?.title
                ?.takeIf { it.isNotBlank() && it != PLACEHOLDER_TITLE }
            MultiviewTileUi(
                slot = slot,
                tileId = tileIds[slot],
                rect = rect,
                channelKey = channel?.key,
                name = channel?.let { names[it.key] } ?: playerTitle,
                nowPlaying = channel?.let { programmes[it.key] }?.let { TileNowPlayingPolicy.nowTitle(it, nowMs()) },
                status = status,
                failure = playback.failure,
                reconnecting = playback.isReconnecting,
                rejoining = tileIds[slot] in rejoiningTiles && status == TileStatus.LOADING,
                focused = c.focusedPane == slot,
                audible = c.audibleNow == slot,
                pinned = pinned == slot,
                mutedByPin = pinned != null && pinned != slot && c.focusedPane == slot,
            )
        }
        mutableState.value = MultiviewUiState(
            started = true,
            layout = c.layout,
            tileCount = c.paneCount,
            tiles = tiles,
            focusedSlot = c.focusedPane,
            audibleSlot = c.audibleNow,
            pinnedSlot = pinned,
            fullscreenSlot = fullscreenSlot,
            notice = notice,
            noticeSerial = noticeSerial,
            pendingSlot = pendingSlot,
        )
        settleTileWatches(tiles)
        scheduleNowRefresh(c)
        learnCeiling(tiles)
        tryBackups(tiles)
    }

    /**
     * A tile that ended in Failed goes to the next backup channel, if there is one, before it shows its failure. No
     * failure is retried on the same link from here (the only retry is the viewer's, or the pipeline's own capped
     * reconnect that ran before the tile gave up), so a gone stream or an unplayable format moves on at once.
     */
    private fun tryBackups(tiles: List<MultiviewTileUi>) {
        val c = core ?: return
        if (released || switchingToBackup || fullscreenSlot != null) return
        for (tile in tiles) {
            val key = tile.channelKey ?: continue
            if (tile.status != TileStatus.FAILED || !backupConsidered.add(tile.tileId)) continue
            // A channel that is not in the playlist has no stream to back up: the viewer replaces or removes it.
            if (tile.failure?.let(TileFailurePolicy::backupAllowed) == false) continue
            val failed = channelOf(key)
            val pick = backups.next(failed, { tried -> findBackup(key, tried) }, mayPlay) ?: continue
            switchingToBackup = true
            try {
                names[key]?.let { names[pick.key] = it }
                tileIds[tile.slot] = nextTileId++
                c.assignChannel(tile.slot, pick)
            } finally {
                switchingToBackup = false
            }
            persist()
            publish()
            return
        }
    }

    /**
     * A tile opened by a pick that fails within [PICK_NOTICE_WINDOW_MS] says so in a notice as well as on the tile; one that
     * plays, or is no longer on screen, stops being watched.
     */
    private fun settleTileWatches(tiles: List<MultiviewTileUi>) {
        if (pickWatch.isEmpty() && rejoiningTiles.isEmpty()) return
        val live = tiles.associateBy { it.tileId }
        pickWatch.keys.toList().forEach { id ->
            val tile = live[id]
            val (name, at) = pickWatch.getValue(id)
            when {
                tile == null -> pickWatch.remove(id)
                tile.status == TileStatus.FAILED -> {
                    pickWatch.remove(id)
                    if (nowMs() - at <= PICK_NOTICE_WINDOW_MS) {
                        notice = MultiviewNotice.PickFailed(name, tile.failure?.shortTitle.orEmpty())
                        noticeSerial++
                        mutableState.value = mutableState.value.copy(notice = notice, noticeSerial = noticeSerial)
                    }
                }
                tile.status == TileStatus.PLAYING -> pickWatch.remove(id)
            }
        }
        rejoiningTiles.retainAll { id -> live[id]?.status == TileStatus.LOADING }
    }

    private companion object {
        const val PICK_NOTICE_WINDOW_MS = 2_000L
        const val PROGRAMME_WINDOW_MS = 6L * 60L * 60L * 1000L
        const val MIN_NOW_REFRESH_MS = 1_000L

        /** The host's stand-in title before the channel's own is known. */
        const val PLACEHOLDER_TITLE = "Live TV"

        fun statusOf(hasChannel: Boolean, hasPlayer: Boolean, failure: TileFailure?, playing: Boolean, reconnecting: Boolean): TileStatus = when {
            !hasChannel -> TileStatus.EMPTY
            failure != null -> TileStatus.FAILED
            hasPlayer && playing && !reconnecting -> TileStatus.PLAYING
            else -> TileStatus.LOADING
        }
    }
}
