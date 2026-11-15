package com.nuvio.tv.ui.screens.multiview

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.multiview.CenterPressTracker
import com.nuvio.tv.core.multiview.MultiviewBackAction
import com.nuvio.tv.core.multiview.MultiviewBackPolicy
import com.nuvio.tv.core.multiview.MultiviewDirection
import com.nuvio.tv.core.multiview.MultiviewTileMenuPolicy
import com.nuvio.tv.core.multiview.TileMenuItem
import com.nuvio.tv.core.multiview.TileStatus
import com.nuvio.tv.ui.screens.iptv.GuideFocusRequestLoop
import com.nuvio.tv.ui.screens.iptv.requestFocusOrFalse
import com.nuvio.tv.ui.util.findActivity
import kotlinx.coroutines.launch
import androidx.compose.runtime.key as composeKey

/**
 * Several live channels on one screen. Thin by design: the grid is drawn from the controller's state, keys and
 * focus are forwarded, and the panels (tile menu, layout picker, channel picker) are plain composables over the grid.
 * Decisions live in [MultiviewController] and the policies in `core.multiview`.
 *
 * Back, innermost first: a panel closes, a full-screen tile returns to the grid, the grid exits multiview.
 */
@Composable
internal fun MultiviewScreen(
    /** The channel the viewer was watching: it becomes tile 0. Null opens the queued channels, or the last grid. */
    startKey: String?,
    onExit: () -> Unit,
    /** Plays a channel in the normal full-screen player (a failed tile's way out). */
    onOpenInPlayer: (String) -> Unit,
    viewModel: MultiviewViewModel = hiltViewModel(),
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
    var menuSlot by remember { mutableStateOf<Int?>(null) }
    var layoutOpen by remember { mutableStateOf(false) }
    val overlayOpen = pickerSlot != null || menuSlot != null || layoutOpen
    val scope = rememberCoroutineScope()
    val requesters = remember { HashMap<Int, FocusRequester>() }
    val press = remember { CenterPressTracker() }
    val latestState by androidx.compose.runtime.rememberUpdatedState(state)

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

    LaunchedEffect(state.started) { if (state.started) restoreFocus() }
    // Requesters of tiles that are gone are dropped with them.
    LaunchedEffect(state.tiles.map { tileKey(it) }) {
        val live = latestState.tiles.map { tileKey(it) }.toSet()
        requesters.keys.retainAll(live)
    }

    fun activate(tile: MultiviewTileUi) {
        when {
            state.fullscreenSlot != null -> menuSlot = tile.slot
            tile.status == TileStatus.EMPTY -> pickerSlot = tile.slot
            tile.status == TileStatus.FAILED -> menuSlot = tile.slot
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
            MultiviewBackAction.CLOSE_PICKER -> { pickerSlot = null; restoreFocus() }
            MultiviewBackAction.CLOSE_LAYOUT_PICKER -> { layoutOpen = false; restoreFocus() }
            MultiviewBackAction.CLOSE_TILE_MENU -> { menuSlot = null; restoreFocus() }
            MultiviewBackAction.EXIT_FULLSCREEN -> { controller.exitFullscreen(); restoreFocus() }
            MultiviewBackAction.EXIT_MULTIVIEW -> onExit()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val fullscreen = state.fullscreenSlot != null
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(if (fullscreen) 0.dp else GRID_MARGIN)
                .onSizeChanged {
                    controller.viewportWidthPx = it.width
                    controller.viewportHeightPx = it.height
                },
        ) {
            state.tiles.forEach { tile ->
                composeKey(tileKey(tile)) {
                    MultiviewTile(
                        tile = tile,
                        surfaceOwner = if (tile.tileId >= 0) viewModel.surfaceOwner(tile.tileId) else null,
                        focusRequester = requesterFor(tile),
                        canFocus = !overlayOpen,
                        onFocused = { controller.focus(tile.slot) },
                        onKey = { event -> onTileKey(tile, event) },
                        modifier = Modifier
                            .offset(x = maxWidth * tile.rect.left, y = maxHeight * tile.rect.top)
                            .size(maxWidth * tile.rect.width, maxHeight * tile.rect.height)
                            .padding(if (fullscreen) 0.dp else TILE_GAP),
                    )
                }
            }
        }

        // One line, once per session, after a tile was added and the box may struggle. The add already went ahead.
        state.capacityNotice?.let { about ->
            com.nuvio.tv.ui.components.NuvioUndoToast(
                message = androidx.compose.ui.res.stringResource(com.nuvio.tv.R.string.multiview_capacity_notice, about),
                actionLabel = androidx.compose.ui.res.stringResource(com.nuvio.tv.R.string.multiview_notice_ok),
                onAction = { controller.dismissCapacityNotice(); restoreFocus() },
                onDismiss = { controller.dismissCapacityNotice(); restoreFocus() },
                durationMillis = com.nuvio.tv.ui.screens.iptv.GuideHideUndoPolicy.UNDO_WINDOW_MS,
                key = about,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(vertical = 32.dp, horizontal = 32.dp),
            )
        }

        val menuTile = menuSlot?.let { slot -> state.tiles.firstOrNull { it.slot == slot } }
        if (menuTile != null) {
            MultiviewTileMenu(
                title = menuTile.name ?: androidx.compose.ui.res.stringResource(com.nuvio.tv.R.string.multiview_title),
                items = MultiviewTileMenuPolicy.items(
                    status = menuTile.status,
                    failure = menuTile.failure,
                    pinnedHere = menuTile.pinned,
                    paneCount = state.tileCount,
                    fullscreen = fullscreen,
                ),
                onItem = { item ->
                    val slot = menuTile.slot
                    menuSlot = null
                    when (item) {
                        TileMenuItem.FULL_SCREEN -> { controller.enterFullscreen(slot); restoreFocus() }
                        TileMenuItem.BACK_TO_GRID -> { controller.exitFullscreen(); restoreFocus() }
                        TileMenuItem.OPEN_IN_PLAYER -> menuTile.channelKey?.let(onOpenInPlayer)
                        TileMenuItem.RETRY -> { controller.retry(slot); restoreFocus() }
                        TileMenuItem.KEEP_SOUND -> { controller.pinAudio(slot); restoreFocus() }
                        TileMenuItem.FOLLOW_FOCUS -> { controller.unpinAudio(); restoreFocus() }
                        TileMenuItem.ADD_CHANNEL, TileMenuItem.REPLACE_CHANNEL -> pickerSlot = slot
                        TileMenuItem.REMOVE -> { controller.remove(slot); restoreFocus() }
                        TileMenuItem.CHANGE_LAYOUT -> layoutOpen = true
                        TileMenuItem.EXIT -> onExit()
                    }
                },
            )
        }
        if (layoutOpen) {
            MultiviewLayoutPicker(
                tileCount = state.tileCount,
                current = state.layout,
                onPick = { choice ->
                    layoutOpen = false
                    controller.setLayout(choice.layout, choice.tileCount)
                    restoreFocus()
                },
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
                onPick = { pick ->
                    pickerSlot = null
                    controller.assign(pickSlot, pick)
                    restoreFocus()
                },
            )
        }
    }
}

private val GRID_MARGIN = 24.dp
private val TILE_GAP = 4.dp
