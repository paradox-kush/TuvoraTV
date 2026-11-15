package com.nuvio.tv.ui.screens.multiview

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.multiview.CenterPressTracker
import com.nuvio.tv.core.multiview.MultiviewBackAction
import com.nuvio.tv.core.multiview.MultiviewBackPolicy
import com.nuvio.tv.core.multiview.MultiviewChromePolicy
import com.nuvio.tv.core.multiview.MultiviewDirection
import com.nuvio.tv.core.multiview.MultiviewHintPolicy
import com.nuvio.tv.core.multiview.MultiviewLayoutPolicy
import com.nuvio.tv.core.multiview.MultiviewTileMenuPolicy
import com.nuvio.tv.core.multiview.TileAction
import com.nuvio.tv.core.multiview.TileMenuItem
import com.nuvio.tv.core.multiview.TileStatus
import com.nuvio.tv.ui.screens.iptv.GuideFocusRequestLoop
import com.nuvio.tv.ui.screens.iptv.requestFocusOrFalse
import com.nuvio.tv.ui.util.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.key as composeKey

/**
 * Several live channels on one screen. Thin by design: the grid is drawn from the controller's state, keys and
 * focus are forwarded, and the panels (tile menu, layout panel, channel picker) are plain composables over the grid.
 * Decisions live in [MultiviewController] and the policies in `core.multiview`.
 *
 * Back, innermost first: a picker sub-list or panel closes (the layout panel returns to the tile menu), a full-screen
 * tile returns to the grid, the grid leaves Multiview (a second Back within 2.5 s when two or more channels are open).
 */
@Composable
internal fun MultiviewScreen(
    /** The channel the viewer was watching: it becomes tile 0. Null opens the queued channels, or the last grid. */
    startKey: String?,
    /** Leaves Multiview. Gets the channel being heard, for a grid that was opened from the player. */
    onExit: (audibleKey: String?) -> Unit,
    /** Plays a channel in the normal full-screen player (a failed tile's way out). */
    onOpenInPlayer: (String) -> Unit,
    viewModel: MultiviewViewModel = hiltViewModel(),
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Before any tile can open: the hosts need the activity and the lifecycle.
    viewModel.bind(context.findActivity(), lifecycleOwner.lifecycle)
    LaunchedEffect(startKey) { viewModel.start(startKey) }

    // Tiles run while RESUMED; ON_STOP closes every one (nothing is saved for live) and resume reopens them.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.onStop() }

    // The player owns the screen: chrome outside this composable (update banner) stands down.
    DisposableEffect(Unit) {
        com.nuvio.tv.updater.ImmersivePlaybackGate.setImmersive(true)
        onDispose { com.nuvio.tv.updater.ImmersivePlaybackGate.setImmersive(false) }
    }

    var pickerSlot by remember { mutableStateOf<Int?>(null) }
    /** The tile that had the highlight when the picker opened: where focus goes if the picker is backed out of. */
    var pickerReturnSlot by remember { mutableIntStateOf(0) }
    var menuSlot by remember { mutableStateOf<Int?>(null) }
    var menuEntryItem by remember { mutableStateOf<TileMenuItem?>(null) }
    var layoutOpen by remember { mutableStateOf(false) }
    val overlayOpen = pickerSlot != null || menuSlot != null || layoutOpen
    val scope = rememberCoroutineScope()
    val requesters = remember { HashMap<Int, FocusRequester>() }
    val press = remember { CenterPressTracker() }
    val latestState by androidx.compose.runtime.rememberUpdatedState(state)

    // Labels and the hint bar fade after 5 s without a key press and come back on any key; the ring, the sound badge and
    // a tile's own content stay. The key still does its job: nothing here swallows it.
    var wakeTick by remember { mutableIntStateOf(0) }
    var chromeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(wakeTick) {
        chromeVisible = true
        delay(MultiviewChromePolicy.HIDE_AFTER_MS)
        chromeVisible = false
    }
    val fadeMs = remember {
        val scale = runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        if (scale == 0f) 0 else MultiviewChromePolicy.FADE_MS
    }
    val chromeAlpha by animateFloatAsState(if (chromeVisible) 1f else 0f, tween(fadeMs), label = "multiviewChrome")

    // One notice at a time, top centre; each goes after its own time.
    LaunchedEffect(state.noticeSerial) {
        val shown = state.notice ?: return@LaunchedEffect
        delay(shown.durationMs)
        controller.dismissNotice(state.noticeSerial)
    }

    fun tileKey(tile: MultiviewTileUi) = if (tile.tileId >= 0) tile.tileId else -(tile.slot + 1)
    fun requesterFor(tile: MultiviewTileUi) = requesters.getOrPut(tileKey(tile)) { FocusRequester() }

    /** Puts D-pad focus back on the selected tile once a panel has closed (it may not be attached for a frame or two). */
    fun restoreFocus() {
        scope.launch {
            GuideFocusRequestLoop.requestUntilLanded {
                val s = controller.state.value
                s.tiles.firstOrNull { it.slot == s.focusedSlot }?.let { requesterFor(it).requestFocusOrFalse() } == true
            }
        }
    }

    fun openPicker(slot: Int) {
        pickerReturnSlot = latestState.focusedSlot
        menuSlot = null
        layoutOpen = false
        controller.focus(slot)
        pickerSlot = slot
    }

    fun leave() = onExit(controller.audibleChannelKey())

    // Arriving from the player with a free slot: the picker opens on it at once, so the way in is OK, OK.
    var arrivalHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.started) {
        if (!state.started) return@LaunchedEffect
        if (!arrivalHandled && startKey != null) {
            arrivalHandled = true
            val free = latestState.tiles.firstOrNull { it.status == TileStatus.EMPTY }
            if (free != null) {
                openPicker(free.slot)
                return@LaunchedEffect
            }
        }
        restoreFocus()
    }
    // Requesters of tiles that are gone are dropped with them.
    LaunchedEffect(state.tiles.map { tileKey(it) }) {
        val live = latestState.tiles.map { tileKey(it) }.toSet()
        requesters.keys.retainAll(live)
    }

    fun activate(tile: MultiviewTileUi) {
        when {
            state.fullscreenSlot != null -> menuSlot = tile.slot
            tile.status == TileStatus.EMPTY -> openPicker(tile.slot)
            // A failed tile runs its failure's main action: Retry, Replace or the full player.
            tile.status == TileStatus.FAILED -> when (tile.failure?.primary) {
                TileAction.RETRY -> { controller.retry(tile.slot); restoreFocus() }
                TileAction.REPLACE -> openPicker(tile.slot)
                TileAction.OPEN_IN_PLAYER -> tile.channelKey?.let(onOpenInPlayer)
                TileAction.REMOVE, null -> menuSlot = tile.slot
            }
            else -> controller.enterFullscreen(tile.slot)
        }
    }

    fun onTileKey(tile: MultiviewTileUi, event: KeyEvent): Boolean {
        val direction = when (event.key) {
            Key.DirectionLeft -> MultiviewDirection.LEFT
            Key.DirectionRight -> MultiviewDirection.RIGHT
            Key.DirectionUp -> MultiviewDirection.UP
            Key.DirectionDown -> MultiviewDirection.DOWN
            else -> null
        }
        if (direction != null) {
            // Never leaves the grid and never wraps: an edge is simply an edge.
            if (event.type == KeyEventType.KeyDown && controller.move(direction)) {
                val s = controller.state.value
                s.tiles.firstOrNull { it.slot == s.focusedSlot }?.let { requesterFor(it).requestFocusOrFalse() }
            }
            return true
        }
        return when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                val result = if (event.type == KeyEventType.KeyDown) {
                    press.onKeyDown(event.nativeKeyEvent.repeatCount)
                } else {
                    press.onKeyUp()
                }
                when (result) {
                    CenterPressTracker.Result.LONG_PRESS -> menuSlot = tile.slot
                    CenterPressTracker.Result.ACTIVATE -> activate(tile)
                    CenterPressTracker.Result.NONE -> Unit
                }
                true
            }
            Key.Menu, Key.Info -> {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) menuSlot = tile.slot
                true
            }
            else -> false
        }
    }

    BackHandler {
        when (
            MultiviewBackPolicy.action(
                channelPickerOpen = pickerSlot != null,
                layoutPickerOpen = layoutOpen,
                tileMenuOpen = menuSlot != null,
                fullscreen = state.fullscreenSlot != null,
            )
        ) {
            MultiviewBackAction.CLOSE_PICKER -> {
                pickerSlot = null
                // A slot added by "Add another channel" goes away again, and focus returns to the tile it was added from.
                controller.cancelPendingSlot()
                controller.focus(pickerReturnSlot.coerceAtMost(latestState.tileCount - 1).coerceAtLeast(0))
                restoreFocus()
            }
            MultiviewBackAction.LAYOUT_BACK_TO_MENU -> { menuEntryItem = TileMenuItem.CHANGE_LAYOUT; layoutOpen = false }
            MultiviewBackAction.CLOSE_TILE_MENU -> { menuSlot = null; restoreFocus() }
            MultiviewBackAction.EXIT_FULLSCREEN -> { controller.exitFullscreen(); restoreFocus() }
            MultiviewBackAction.LEAVE_GRID -> if (controller.requestLeave()) leave()
        }
    }

    val fullscreen = state.fullscreenSlot != null
    // The tile menu and the layout panel leave the grid visible: it is laid out in the area left of the panel.
    val panelOpen = !fullscreen && (menuSlot != null || layoutOpen)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) wakeTick++
                false
            },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(
                    start = if (fullscreen) 0.dp else GRID_MARGIN_X,
                    top = if (fullscreen) 0.dp else GRID_MARGIN_Y,
                    bottom = if (fullscreen) 0.dp else GRID_MARGIN_Y,
                    end = when {
                        fullscreen -> 0.dp
                        panelOpen -> MENU_PANEL_WIDTH + PANEL_GAP
                        else -> GRID_MARGIN_X
                    },
                )
                .onSizeChanged {
                    // The grid's own area only: a full-screen tile does not re-lay-out the grid it will return to.
                    if (!fullscreen) controller.setStage(it.width / density.density, it.height / density.density, it.width, it.height)
                },
        ) {
            state.tiles.forEach { tile ->
                composeKey(tileKey(tile)) {
                    MultiviewTile(
                        tile = tile,
                        surfaceOwner = if (tile.tileId >= 0) viewModel.surfaceOwner(tile.tileId) else null,
                        focusRequester = requesterFor(tile),
                        canFocus = !overlayOpen,
                        fullscreen = fullscreen,
                        small = !fullscreen && MultiviewLayoutPolicy.isSmall((maxWidth * tile.rect.width).value),
                        picking = pickerSlot == tile.slot,
                        chromeAlpha = chromeAlpha,
                        onFocused = { controller.focus(tile.slot) },
                        onKey = { event -> onTileKey(tile, event) },
                        modifier = Modifier
                            .offset(x = maxWidth * tile.rect.left, y = maxHeight * tile.rect.top)
                            .size(maxWidth * tile.rect.width, maxHeight * tile.rect.height),
                    )
                }
            }
        }

        // The hint bar: what OK, Hold OK and Back do for the focused tile. Not while a panel is open.
        val focusedTile = state.tiles.firstOrNull { it.slot == state.focusedSlot }
        if (focusedTile != null && !overlayOpen) {
            val keptOn = state.pinnedSlot?.takeIf { it != focusedTile.slot }
                ?.let { slot -> state.tiles.firstOrNull { it.slot == slot } }
            val keptName = keptOn?.let { it.name ?: stringResource(R.string.multiview_tile_unnamed, it.slot + 1) }
            GridHintBar(
                hint = MultiviewHintPolicy.forTile(focusedTile.status, focusedTile.failure, fullscreen, keptName),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = GRID_MARGIN_Y)
                    .graphicsLayer { alpha = chromeAlpha },
            )
        }

        // One non-focusable line, top centre: the D-pad keeps moving between tiles while it is up.
        state.notice?.let { notice ->
            NoticeLine(
                notice = notice,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = GRID_MARGIN_Y + 5.dp, start = 32.dp, end = 32.dp),
            )
        }

        val menuTile = menuSlot?.let { slot -> state.tiles.firstOrNull { it.slot == slot } }
        if (menuTile != null && !layoutOpen) {
            val items = MultiviewTileMenuPolicy.items(
                status = menuTile.status,
                failure = menuTile.failure,
                pinnedHere = menuTile.pinned,
                paneCount = state.tileCount,
                fullscreen = fullscreen,
            )
            MultiviewTileMenu(
                title = menuTile.name ?: stringResource(R.string.multiview_empty_tile),
                caption = menuTile.failure?.takeIf { menuTile.status == TileStatus.FAILED }?.text,
                items = items,
                entryItem = menuEntryItem,
                layoutLabel = layoutFamilyLabel(MultiviewLayoutPolicy.familyOf(state.layout)),
                tileCount = state.tileCount,
                onClose = { menuSlot = null; restoreFocus() },
                onItem = { item ->
                    val slot = menuTile.slot
                    menuEntryItem = null
                    when (item) {
                        TileMenuItem.BACK_TO_GRID -> { menuSlot = null; controller.exitFullscreen(); restoreFocus() }
                        TileMenuItem.WATCH_IN_PLAYER, TileMenuItem.OPEN_IN_PLAYER -> {
                            menuSlot = null
                            menuTile.channelKey?.let(onOpenInPlayer)
                        }
                        TileMenuItem.RETRY -> { menuSlot = null; controller.retry(slot); restoreFocus() }
                        TileMenuItem.KEEP_SOUND -> { menuSlot = null; controller.pinAudio(slot); restoreFocus() }
                        TileMenuItem.FOLLOW_FOCUS -> { menuSlot = null; controller.unpinAudio(); restoreFocus() }
                        TileMenuItem.ADD_CHANNEL, TileMenuItem.REPLACE_CHANNEL -> openPicker(slot)
                        TileMenuItem.REMOVE, TileMenuItem.CLEAR_CHANNEL, TileMenuItem.REMOVE_TILE -> {
                            menuSlot = null
                            controller.remove(slot)
                            restoreFocus()
                        }
                        TileMenuItem.ADD_ANOTHER -> controller.addSlot()?.let(::openPicker)
                        TileMenuItem.CHANGE_LAYOUT -> layoutOpen = true
                        TileMenuItem.EXIT -> { menuSlot = null; leave() }
                    }
                },
            )
        }
        if (layoutOpen) {
            MultiviewLayoutPicker(
                tileCount = state.tileCount,
                current = state.layout,
                onPick = { family ->
                    layoutOpen = false
                    menuSlot = null
                    controller.setLayout(family)
                    restoreFocus()
                },
                onBack = { menuEntryItem = TileMenuItem.CHANGE_LAYOUT; layoutOpen = false },
            )
        }
        val pickSlot = pickerSlot
        if (pickSlot != null) {
            val focusedKey = state.tiles.firstOrNull { it.slot == state.focusedSlot }?.channelKey
            MultiviewChannelPicker(
                accounts = accounts,
                onScreenKeys = state.tiles.mapNotNull { it.channelKey }.toSet(),
                preferredAccountId = accounts.firstOrNull { account ->
                    focusedKey?.startsWith(XtreamItemRegistry.accountPrefix(account.id)) == true
                }?.id,
                replacingName = state.tiles.firstOrNull { it.slot == pickSlot }?.takeIf { it.channelKey != null }?.name,
                onPick = { pick ->
                    pickerSlot = null
                    controller.assign(pickSlot, pick)
                    restoreFocus()
                },
            )
        }
    }
}

/** The 5% safe area of a 960 x 540 dp screen: no tile within 27 dp of an edge. */
private val GRID_MARGIN_X = 48.dp
private val GRID_MARGIN_Y = 27.dp

/** Space between the grid and a panel that it makes room for. */
private val PANEL_GAP = 20.dp
