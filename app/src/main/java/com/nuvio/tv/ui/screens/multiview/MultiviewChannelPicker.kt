package com.nuvio.tv.ui.screens.multiview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.ui.screens.iptv.ChannelListRow
import com.nuvio.tv.ui.screens.iptv.GuideCategory
import com.nuvio.tv.ui.screens.iptv.GuideFocusRequestLoop
import com.nuvio.tv.ui.screens.iptv.GuideSearchDialog
import com.nuvio.tv.ui.screens.iptv.GuideSpecial
import com.nuvio.tv.ui.screens.iptv.XtreamLiveGuideViewModel
import com.nuvio.tv.ui.screens.iptv.rememberEffectiveLoadStatus
import com.nuvio.tv.ui.screens.iptv.requestFocusOrFalse
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The channel picker: the guide's own playlist, group and channel lists (and its search), in a side panel. Only the
 * profile's playlists are offered, so only channels this profile may watch can be picked. Browsing never tunes
 * anything; OK on a channel assigns it to the tile and closes the panel.
 *
 * No Sports tab in v1.
 */
@Composable
internal fun BoxScope.MultiviewChannelPicker(
    accounts: List<XtreamAccount>,
    /** Channel keys already on screen, marked in the list. Picking one moves focus to its tile. */
    onScreenKeys: Set<String>,
    /** The playlist to open on: the one the focused tile's channel belongs to. */
    preferredAccountId: String?,
    onPick: (ChannelPick) -> Unit,
    viewModel: XtreamLiveGuideViewModel = hiltViewModel(),
) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(PICKER_WIDTH)
            .background(
                Brush.horizontalGradient(
                    listOf(NuvioTheme.colors.Background.copy(alpha = 0.9f), NuvioTheme.colors.Background.copy(alpha = 0.98f)),
                ),
            ),
    ) {
        if (accounts.isEmpty()) {
            Text(
                text = stringResource(R.string.multiview_picker_no_playlist),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.align(Alignment.Center).padding(NuvioTheme.spacing.xl),
            )
            return@Box
        }
        PickerBody(accounts, onScreenKeys, preferredAccountId, onPick, viewModel)
    }
}

@Composable
private fun PickerBody(
    accounts: List<XtreamAccount>,
    onScreenKeys: Set<String>,
    preferredAccountId: String?,
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
    var searchOpen by remember { mutableStateOf(false) }
    val categoryFocus = remember { FocusRequester() }
    val channelsFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Enter on the selected group once the groups have loaded.
    LaunchedEffect(ui.categories.isNotEmpty()) {
        if (ui.categories.isNotEmpty()) GuideFocusRequestLoop.requestUntilLanded { categoryFocus.requestFocusOrFalse() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .focusGroup()
            .focusProperties { onExit = { cancelFocusChange() } }
            .padding(vertical = NuvioTheme.spacing.xl, horizontal = NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.multiview_picker_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        if (accounts.size > 1) {
            Button(
                onClick = {
                    val next = accounts[(accounts.indexOfFirst { it.id == account.id } + 1) % accounts.size]
                    accountId = next.id
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        R.string.multiview_picker_playlist,
                        com.nuvio.tv.core.iptv.PlaylistDisplayPolicy.displayName(account.name),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            LazyColumn(
                modifier = Modifier.width(GROUP_COLUMN_WIDTH).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(ui.categories, key = { _, c -> c.id }) { _, category ->
                    GroupRow(
                        category = category,
                        searchQuery = ui.searchQuery,
                        selected = category.id == ui.selectedCategoryId,
                        focusRequester = categoryFocus.takeIf { category.id == (ui.selectedCategoryId ?: ui.categories.first().id) },
                        onFocused = { if (category.special != GuideSpecial.SEARCH) viewModel.onCategoryFocused(category.id) },
                        onOpen = {
                            if (category.special == GuideSpecial.SEARCH) searchOpen = true
                            else viewModel.commitFocusedCategory()
                        },
                        onEnterChannels = {
                            viewModel.commitFocusedCategory()
                            channelsFocus.requestFocusOrFalse()
                        },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val channels = remember(ui.channels) { ui.channels.distinctBy { it.contentId } }
                when {
                    channelsLoad is LoadStatus.Failed || categoriesLoad is LoadStatus.Failed || ui.error != null -> Column(
                        Modifier.align(Alignment.Center),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    ) {
                        Text(
                            text = ui.error ?: stringResource(R.string.iptv_guide_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.Error,
                        )
                        Button(onClick = viewModel::retry) { Text(stringResource(R.string.action_retry)) }
                    }
                    channels.isEmpty() && (channelsLoad is LoadStatus.Loading || categoriesLoad is LoadStatus.Loading) ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    channels.isEmpty() -> Text(
                        text = stringResource(R.string.multiview_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.align(Alignment.Center),
                    )
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
                                focusRequester = if (index == 0) channelsFocus else null,
                                onFocused = {},
                                onClick = {
                                    onPick(
                                        ChannelPick(
                                            key = channel.contentId,
                                            label = ChannelLabel(channel.name, ui.epg[channel.streamId]?.now?.title),
                                        ),
                                    )
                                },
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
                viewModel.searchChannels(query)
            },
            onDismiss = { searchOpen = false },
        )
    }
}

@Composable
private fun GroupRow(
    category: GuideCategory,
    searchQuery: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
    onEnterChannels: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    val label = if (category.special == GuideSpecial.SEARCH && searchQuery.isNotBlank()) "${category.name}: $searchQuery" else category.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(
                when {
                    focused -> NuvioTheme.colors.Primary.copy(alpha = 0.22f)
                    selected -> NuvioTheme.colors.FocusBackground.copy(alpha = 0.5f)
                    else -> Color.Transparent
                },
            )
            .border(
                if (focused) NuvioTheme.spacing.xxs else 0.dp,
                if (focused) NuvioTheme.colors.Primary else Color.Transparent,
                shape,
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // RIGHT enters the list at once, like the guide: the group commits without waiting for focus to settle.
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionRight && category.special != GuideSpecial.SEARCH) {
                    if (event.type == KeyEventType.KeyDown) onEnterChannels()
                    true
                } else false
            }
            .clickable(onClick = onOpen)
            .padding(horizontal = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val PICKER_WIDTH = 820.dp
private val GROUP_COLUMN_WIDTH = 240.dp
