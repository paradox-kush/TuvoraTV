package com.nuvio.tv.ui.screens.multiview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.PlaylistDisplayPolicy
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.multiview.PickerEmpty
import com.nuvio.tv.core.multiview.PickerEmptyPolicy
import com.nuvio.tv.core.multiview.PickerGroup
import com.nuvio.tv.core.multiview.PickerOpeningPolicy
import com.nuvio.tv.ui.screens.iptv.ChannelListRow
import com.nuvio.tv.ui.screens.iptv.GuideCategory
import com.nuvio.tv.ui.screens.iptv.GuideFocusRequestLoop
import com.nuvio.tv.ui.screens.iptv.GuideSearchDialog
import com.nuvio.tv.ui.screens.iptv.GuideSpecial
import com.nuvio.tv.ui.screens.iptv.XtreamLiveGuideViewModel
import com.nuvio.tv.ui.screens.iptv.rememberEffectiveLoadStatus
import com.nuvio.tv.ui.screens.iptv.requestFocusOrFalse
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/**
 * The channel picker: the guide's own playlist, group and channel lists (and its search), in an opaque right-side panel
 * over a flat scrim. Only the profile's playlists are offered, so only channels this profile may watch can be picked.
 * Browsing never tunes anything; OK on a channel assigns it to the tile and closes the panel.
 *
 * It opens on the first of Recent, Favorites and All channels with a channel that is not on screen, focus on that
 * channel. Left from the channels returns to the group you came from, never to the first row. No Sports tab in v1.
 */
@Composable
internal fun BoxScope.MultiviewChannelPicker(
    accounts: List<XtreamAccount>,
    /** Channel keys already on screen, marked in the list. Picking one moves focus to its tile. */
    onScreenKeys: Set<String>,
    /** The playlist to open on: the one the focused tile's channel belongs to. */
    preferredAccountId: String?,
    /** The name of the channel being replaced, or null when the picker fills an empty tile. */
    replacingName: String?,
    onPick: (ChannelPick) -> Unit,
    viewModel: XtreamLiveGuideViewModel = hiltViewModel(),
) {
    // A flat 50% scrim over the grid, no gradient and no blur.
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)))
    val border = NuvioTheme.colors.Border
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(PICKER_WIDTH)
            .background(NuvioTheme.colors.Background)
            .drawBehind { drawRect(border, topLeft = Offset.Zero, size = Size(1.dp.toPx(), size.height)) },
    ) {
        if (accounts.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center).padding(NuvioTheme.spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.multiview_picker_no_playlist),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            return@Box
        }
        PickerBody(accounts, onScreenKeys, preferredAccountId, replacingName, onPick, viewModel)
    }
}

private enum class PickerView { GROUPS, PLAYLISTS }

@Composable
private fun playlistLabel(accounts: List<XtreamAccount>, account: XtreamAccount): String {
    val position = accounts.indexOfFirst { it.id == account.id } + 1
    return PlaylistDisplayPolicy.labelOrNull(account.name)
        ?: stringResource(R.string.multiview_picker_playlist_fallback, position.coerceAtLeast(1))
}

@Composable
private fun PickerBody(
    accounts: List<XtreamAccount>,
    onScreenKeys: Set<String>,
    preferredAccountId: String?,
    replacingName: String?,
    onPick: (ChannelPick) -> Unit,
    viewModel: XtreamLiveGuideViewModel,
) {
    var accountId by remember(accounts.map { it.id }) {
        mutableStateOf(accounts.firstOrNull { it.id == preferredAccountId }?.id ?: accounts.first().id)
    }
    val account = accounts.firstOrNull { it.id == accountId } ?: accounts.first()
    // Keyed on the whole account, as the guide does, so a playlist edit re-filters.
    LaunchedEffect(account) { viewModel.setAccount(account) }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteLiveIds.collectAsStateWithLifecycle()
    val channelsLoad = rememberEffectiveLoadStatus(ui.channelsLoad)
    val categoriesLoad = rememberEffectiveLoadStatus(ui.categoriesLoad)
    val channels = remember(ui.channels) { ui.channels.distinctBy { it.contentId } }
    val onScreen by androidx.compose.runtime.rememberUpdatedState(onScreenKeys)

    var view by remember { mutableStateOf(PickerView.GROUPS) }
    var searchOpen by remember { mutableStateOf(false) }
    var focusedRow by remember { mutableStateOf(PickerEmptyPolicy.Row.OTHER) }
    var focusedGroupId by remember { mutableStateOf<String?>(null) }

    val categoryFocus = remember { FocusRequester() }
    val channelsFocus = remember { FocusRequester() }
    val playlistFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val groupState = rememberLazyListState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val showPlaylistRow = accounts.size > 1
    val selectedGroupId = ui.selectedCategoryId ?: ui.categories.firstOrNull()?.id

    /** The channel the list focuses next; the row carrying [channelsFocus] is the one with this key. */
    var entryKey by remember { mutableStateOf<String?>(null) }

    /** Set when the viewer entered a group (or the picker opened): focus its channel list once its channels land. */
    var pendingEntryGroup by remember { mutableStateOf<String?>(null) }

    /** After switching playlist: the "All channels" row of the new one takes focus once its groups have loaded. */
    var pendingAllGroup by remember { mutableStateOf(false) }

    /** While the picker opens: which of Recent / Favorites / All channels is being looked at. */
    var opening by remember { mutableStateOf<PickerGroup?>(PickerOpeningPolicy.first) }

    /** Puts D-pad focus on the selected group's row, scrolling it into view first when it is not composed. */
    suspend fun focusSelectedGroup() {
        val items = (if (showPlaylistRow) 1 else 0) + ui.categories.indexOfFirst { it.id == selectedGroupId }
        if (ui.categories.isNotEmpty() && items >= 0 && ui.categories.any { it.id == selectedGroupId }) {
            if (groupState.layoutInfo.visibleItemsInfo.none { it.index == items }) groupState.scrollToItem(items)
        }
        GuideFocusRequestLoop.requestUntilLanded { categoryFocus.requestFocusOrFalse() }
    }

    val enterChannels: (GuideCategory) -> Unit = { category ->
        viewModel.commitFocusedCategory()
        entryKey = null
        pendingEntryGroup = category.id
    }

    // Initial focus: the selected group, until the opening logic below finds a channel to land on.
    LaunchedEffect(ui.categories.isNotEmpty()) {
        if (ui.categories.isNotEmpty() && opening != null) focusSelectedGroup()
    }

    // Opening: Recent, then Favorites, then All channels, whichever first has a channel that is not on screen.
    LaunchedEffect(opening, ui.categories, ui.selectedCategoryId, ui.channels, channelsLoad, ui.accountId) {
        val group = opening ?: return@LaunchedEffect
        if (ui.accountId != account.id || ui.categories.isEmpty()) return@LaunchedEffect
        val special = when (group) {
            PickerGroup.RECENT -> GuideSpecial.RECENT
            PickerGroup.FAVORITES -> GuideSpecial.FAVORITES
            PickerGroup.ALL -> GuideSpecial.ALL
        }
        val target = ui.categories.firstOrNull { it.special == special }
        if (target != null) {
            if (ui.selectedCategoryId != target.id) {
                viewModel.selectCategory(target.id)
                return@LaunchedEffect
            }
            if (channelsLoad is LoadStatus.Loading) return@LaunchedEffect
            if (channelsLoad is LoadStatus.Failed || ui.error != null) {
                opening = null
                return@LaunchedEffect
            }
        }
        val keys = if (target != null) channels.map { it.contentId } else emptyList()
        when (val step = PickerOpeningPolicy.step(group, keys, onScreen)) {
            is PickerOpeningPolicy.Step.TryNext -> opening = step.group
            is PickerOpeningPolicy.Step.Settle -> {
                opening = null
                if (step.index != null && target != null) {
                    entryKey = keys[step.index]
                    pendingEntryGroup = target.id
                } else {
                    focusSelectedGroup()
                }
            }
        }
    }

    // Entering a group's channel list: first channel not on screen, once that group's channels have landed.
    LaunchedEffect(pendingEntryGroup, ui.selectedCategoryId, ui.channels, channelsLoad) {
        val group = pendingEntryGroup ?: return@LaunchedEffect
        if (ui.selectedCategoryId != group || channelsLoad is LoadStatus.Loading) return@LaunchedEffect
        pendingEntryGroup = null
        if (channels.isEmpty()) return@LaunchedEffect // Right on an empty group does nothing: focus stays on the group.
        val index = entryKey?.let { key -> channels.indexOfFirst { it.contentId == key } }?.takeIf { it >= 0 }
            ?: PickerOpeningPolicy.firstOffScreen(channels.map { it.contentId }, onScreen)
            ?: 0
        entryKey = channels[index].contentId
        listState.scrollToItem(index)
        GuideFocusRequestLoop.requestUntilLanded { channelsFocus.requestFocusOrFalse() }
    }

    // A new playlist: land on its "All channels".
    LaunchedEffect(pendingAllGroup, ui.accountId, ui.categories) {
        if (!pendingAllGroup || ui.accountId != account.id || ui.categories.isEmpty()) return@LaunchedEffect
        pendingAllGroup = false
        ui.categories.firstOrNull { it.special == GuideSpecial.ALL }?.let { viewModel.selectCategory(it.id) }
        focusSelectedGroup()
    }

    // The failed state's Retry row takes focus, so a failure is something the remote can act on.
    val failed = channelsLoad is LoadStatus.Failed || categoriesLoad is LoadStatus.Failed || ui.error != null
    LaunchedEffect(failed) {
        if (failed) GuideFocusRequestLoop.requestUntilLanded { retryFocus.requestFocusOrFalse() }
    }

    // The playlists sub-list: Back returns to the groups.
    BackHandler(enabled = view == PickerView.PLAYLISTS) {
        view = PickerView.GROUPS
        scope.launch { focusSelectedGroup() }
    }

    // No `focusProperties { onExit = cancelFocusChange }` here: it is inherited by every focus target below, so the
    // group rows could not hand focus to the channel list (the device pass caught this). The tiles behind the panel
    // are already unfocusable while it is open (`canFocus = !overlayOpen`).
    Column(
        Modifier
            .fillMaxSize()
            .focusGroup()
            .padding(vertical = NuvioTheme.spacing.xl, horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (replacingName != null) stringResource(R.string.multiview_picker_title_replace, replacingName)
            else stringResource(R.string.multiview_picker_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        val playlistName = playlistLabel(accounts, account)
        Text(
            text = (if (showPlaylistRow) "$playlistName · " else "") + stringResource(R.string.multiview_picker_caption),
            fontSize = 12.sp,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 2,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- groups (or the list of playlists) ----
            if (view == PickerView.PLAYLISTS) {
                Column(Modifier.width(GROUP_COLUMN_WIDTH).fillMaxHeight()) {
                    LaunchedEffect(Unit) { GuideFocusRequestLoop.requestUntilLanded { playlistFocus.requestFocusOrFalse() } }
                    MultiviewSectionLabel(stringResource(R.string.multiview_picker_playlists_title))
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(accounts, key = { _, a -> a.id }) { _, a ->
                            MultiviewPanelRow(
                                label = playlistLabel(accounts, a),
                                selected = a.id == account.id,
                                trailing = if (a.id == account.id) stringResource(R.string.multiview_picker_current) else null,
                                focusRequester = if (a.id == account.id) playlistFocus else null,
                                onClick = {
                                    if (a.id != account.id) {
                                        accountId = a.id
                                        opening = null
                                        pendingAllGroup = true
                                    } else {
                                        scope.launch { focusSelectedGroup() }
                                    }
                                    view = PickerView.GROUPS
                                },
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = groupState,
                    modifier = Modifier
                        .width(GROUP_COLUMN_WIDTH)
                        .fillMaxHeight()
                        .onPreviewKeyEvent { event -> event.key == Key.DirectionLeft },
                    contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (showPlaylistRow) {
                        item(key = PLAYLIST_ROW_KEY) {
                            MultiviewPanelRow(
                                label = stringResource(R.string.multiview_picker_playlist_row),
                                trailing = playlistLabel(accounts, account),
                                onFocused = { focusedRow = PickerEmptyPolicy.Row.PLAYLIST },
                                onKey = { event -> event.key == Key.DirectionRight },
                                onClick = { view = PickerView.PLAYLISTS },
                            )
                        }
                    }
                    itemsIndexed(ui.categories, key = { _, c -> c.id }) { _, category ->
                        val isSearch = category.special == GuideSpecial.SEARCH
                        val selected = category.id == selectedGroupId
                        MultiviewPanelRow(
                            label = if (isSearch && ui.searchQuery.isNotBlank()) "${category.name}: ${ui.searchQuery}" else category.name,
                            selected = selected,
                            focusRequester = categoryFocus.takeIf { selected },
                            onFocused = {
                                focusedRow = if (isSearch) PickerEmptyPolicy.Row.SEARCH else PickerEmptyPolicy.Row.OTHER
                                focusedGroupId = category.id
                                if (!isSearch) viewModel.onCategoryFocused(category.id)
                            },
                            // RIGHT enters the list at once, like the guide: the group commits without waiting for
                            // focus to settle. On Search it enters the results, if there are any.
                            onKey = { event ->
                                if (event.key == Key.DirectionRight) {
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (!isSearch) enterChannels(category)
                                        else if (selected && channels.isNotEmpty()) enterChannels(category)
                                    }
                                    true
                                } else false
                            },
                            onClick = {
                                if (isSearch) searchOpen = true else enterChannels(category)
                            },
                        )
                    }
                }
            }

            // ---- channels ----
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onPreviewKeyEvent { event -> channelColumnKey(event) { scope.launch { focusSelectedGroup() } } },
            ) {
                val emptyKind = PickerEmptyPolicy.forColumn(
                    row = focusedRow,
                    selected = when (ui.categories.firstOrNull { it.id == ui.selectedCategoryId }?.special) {
                        GuideSpecial.SEARCH -> PickerEmptyPolicy.Selected.SEARCH
                        GuideSpecial.FAVORITES, GuideSpecial.ALL_FAVORITES -> PickerEmptyPolicy.Selected.FAVORITES
                        GuideSpecial.RECENT -> PickerEmptyPolicy.Selected.RECENT
                        else -> PickerEmptyPolicy.Selected.OTHER
                    },
                    searchQuery = ui.searchQuery,
                    hasChannels = channels.isNotEmpty(),
                )
                when {
                    failed -> PickerFailed(ui.error, retryFocus, viewModel::retry)
                    view == PickerView.GROUPS && emptyKind != null && (emptyKind == PickerEmpty.PLAYLIST_ROW || emptyKind == PickerEmpty.SEARCH_IDLE) ->
                        PickerEmptyText(emptyKind, ui.searchQuery, playlistLabel(accounts, account))
                    channels.isEmpty() && (channelsLoad is LoadStatus.Loading || categoriesLoad is LoadStatus.Loading) ->
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(strokeWidth = 3.dp)
                            Text(
                                text = stringResource(R.string.multiview_picker_loading),
                                fontSize = 14.sp,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(top = NuvioTheme.spacing.sm),
                            )
                        }
                    emptyKind != null -> PickerEmptyText(emptyKind, ui.searchQuery, playlistLabel(accounts, account))
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(channels, key = { _, ch -> ch.contentId }) { index, channel ->
                            ChannelListRow(
                                number = index + 1,
                                channel = channel,
                                nowTitle = ui.epg[channel.streamId]?.now?.title,
                                playing = channel.contentId in onScreenKeys,
                                favorite = channel.contentId in favoriteIds,
                                focusRequester = if (channel.contentId == (entryKey ?: channels.first().contentId)) channelsFocus else null,
                                onFocused = {},
                                onClick = { onPick(ChannelPick(key = channel.contentId, name = channel.name)) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (searchOpen) {
        GuideSearchDialog(
            initialQuery = ui.searchQuery,
            onSearch = { query ->
                searchOpen = false
                opening = null
                viewModel.searchChannels(query)
                // After a search focus goes to the first result; with none, it stays on the Search row.
                val searchId = ui.categories.firstOrNull { it.special == GuideSpecial.SEARCH }?.id
                entryKey = null
                if (searchId != null) pendingEntryGroup = searchId
            },
            onDismiss = {
                searchOpen = false
                scope.launch { focusSelectedGroup() }
            },
        )
    }
}

/** Left returns to the group you came from; Right at the channel column does nothing. Everything else is the list's. */
private fun channelColumnKey(event: KeyEvent, onLeft: () -> Unit): Boolean = when (event.key) {
    Key.DirectionLeft -> {
        if (event.type == KeyEventType.KeyDown) onLeft()
        true
    }
    Key.DirectionRight -> true
    else -> false
}

@Composable
private fun BoxScope.PickerEmptyText(kind: PickerEmpty, query: String, playlist: String) {
    val (title, body) = when (kind) {
        PickerEmpty.SEARCH_IDLE -> stringResource(R.string.multiview_picker_search_idle_title) to stringResource(R.string.multiview_picker_search_idle_body)
        PickerEmpty.SEARCH_NONE -> stringResource(R.string.multiview_picker_search_none_title, query, playlist) to stringResource(R.string.multiview_picker_search_none_body)
        PickerEmpty.FAVORITES -> stringResource(R.string.multiview_picker_favorites_empty_title) to stringResource(R.string.multiview_picker_favorites_empty_body)
        PickerEmpty.RECENT -> stringResource(R.string.multiview_picker_recent_empty_title) to stringResource(R.string.multiview_picker_recent_empty_body)
        PickerEmpty.GROUP -> stringResource(R.string.multiview_picker_group_empty_title) to stringResource(R.string.multiview_picker_group_empty_body)
        PickerEmpty.PLAYLIST_ROW -> stringResource(R.string.multiview_picker_playlist_idle_title) to stringResource(R.string.multiview_picker_playlist_idle_body, playlist)
    }
    Column(
        Modifier.align(Alignment.Center).padding(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            fontSize = 14.sp,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** A failed load is never "empty": the reason, and a Retry row that takes focus. */
@Composable
private fun BoxScope.PickerFailed(error: String?, retryFocus: FocusRequester, onRetry: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(NuvioTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.multiview_picker_failed_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.Error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = error ?: stringResource(R.string.iptv_guide_unavailable),
            fontSize = 14.sp,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 4,
        )
        MultiviewPanelRow(
            label = stringResource(R.string.action_retry),
            focusRequester = retryFocus,
            modifier = Modifier.padding(top = NuvioTheme.spacing.sm),
            onClick = onRetry,
        )
    }
}

private val PICKER_WIDTH = 560.dp
private val GROUP_COLUMN_WIDTH = 190.dp
private const val PLAYLIST_ROW_KEY = "multiview-playlist-row"
