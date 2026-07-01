package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Calendar
import java.util.Locale

/**
 * TiViMate-style Live TV guide: category column -> channel list with now/next EPG, and a
 * live preview of the focused channel up top. Press a channel to go fullscreen.
 */
@Composable
fun LiveGuide(
    account: XtreamAccount,
    onPlayChannel: (title: String, streamUrl: String, contentId: String) -> Unit,
    selectedTabRequester: FocusRequester? = null,
    viewModel: XtreamLiveGuideViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteLiveIds.collectAsStateWithLifecycle()
    LaunchedEffect(account.id) { viewModel.setAccount(account) }

    // RIGHT from a category must land on the channel list — without this, focus search looks
    // rightward at the (non-focusable) preview pane and jumps up to the tabs instead.
    val channelListFocus = remember { FocusRequester() }

    Row(Modifier.fillMaxSize()) {
        // Category column
        LazyColumn(
            modifier = Modifier.width(260.dp).fillMaxHeight().focusRestorer(),
            contentPadding = PaddingValues(vertical = NuvioTheme.spacing.sm)
        ) {
            itemsIndexed(uiState.categories, key = { _, it -> it.id }) { index, cat ->
                GuideCategoryRow(
                    label = cat.name,
                    selected = cat.id == uiState.selectedCategoryId,
                    rightFocus = channelListFocus,
                    // First category routes UP back to the active tab so the tabs stay reachable.
                    upFocus = if (index == 0) selectedTabRequester else null,
                    onFocused = { viewModel.selectCategory(cat.id) }
                )
            }
        }

        // Preview + channel list
        Column(Modifier.fillMaxSize()) {
            ChannelPreviewPane(
                channelName = uiState.focusedChannel?.name,
                logo = uiState.focusedChannel?.logo,
                epg = uiState.focusedChannel?.let { uiState.epg[it.streamId] },
                modifier = Modifier.fillMaxWidth().height(300.dp)
            )

            when {
                uiState.loadingChannels -> StatusLine("Loading channels…")
                uiState.channels.isEmpty() -> StatusLine("No channels here")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize()
                        .focusRequester(channelListFocus)
                        .focusRestorer(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
                ) {
                    items(uiState.channels, key = { it.contentId }) { ch ->
                        LaunchedEffect(ch.streamId) { viewModel.ensureEpg(ch.streamId) }
                        GuideChannelRow(
                            name = ch.name,
                            logo = ch.logo,
                            nowTitle = uiState.epg[ch.streamId]?.now?.title,
                            isFavorite = ch.contentId in favoriteIds,
                            onFocused = { viewModel.onChannelFocused(ch) },
                            onClick = { viewModel.recordPlayed(ch); onPlayChannel(ch.name, ch.streamUrl, ch.contentId) },
                            onLongClick = { viewModel.toggleFavorite(ch) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(NuvioTheme.spacing.xxxl)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun GuideCategoryRow(
    label: String,
    selected: Boolean,
    rightFocus: FocusRequester,
    upFocus: FocusRequester? = null,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(focused) { if (focused) latestOnFocused() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties {
                right = rightFocus
                if (upFocus != null) up = upFocus
            }
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    focused -> NuvioTheme.colors.Primary
                    selected -> NuvioTheme.colors.BackgroundElevated
                    else -> Color.Transparent
                }
            )
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused || selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GuideChannelRow(
    name: String,
    logo: String?,
    nowTitle: String?,
    isFavorite: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val latestOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(isFocused) { if (isFocused) latestOnFocused() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.md, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused) NuvioTheme.colors.Primary else NuvioTheme.colors.BackgroundElevated)
            .onFocusChanged { isFocused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        AsyncImage(
            model = logo,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1
            )
            Text(
                text = nowTitle ?: "No information",
                style = MaterialTheme.typography.bodySmall,
                color = if (isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1
            )
        }
        if (isFavorite) {
            Text("★", style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
        }
    }
}

/**
 * Preview pane: the focused channel's logo + now/next EPG. Pressing the channel plays it
 * fullscreen. (Inline live video would need a second mpv instance, which is unstable next to
 * the fullscreen player on a software GPU — fullscreen-on-press is the reliable path.)
 */
@Composable
private fun ChannelPreviewPane(
    channelName: String?,
    logo: String?,
    epg: GuideEpg?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (!logo.isNullOrBlank()) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.4f).height(120.dp).align(Alignment.Center)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(Color(0xCC000000))
                .padding(NuvioTheme.spacing.lg)
        ) {
            Text(
                text = channelName ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            epg?.now?.let { now ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${timeRange(now)}  ${now.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1
                )
            }
            epg?.next?.let { next ->
                Text(
                    text = "Next: ${timeRange(next)}  ${next.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
            if (epg?.now == null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Press OK to watch live",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

private fun timeRange(p: XtreamProgram): String = "${hhmm(p.startMs)}–${hhmm(p.endMs)}"

private fun hhmm(ms: Long): String {
    if (ms <= 0L) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}
