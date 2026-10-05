package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * F08: the channel list over fullscreen live video — browse the lineup and switch without leaving
 * fullscreen (the guide's own list sits behind the video while it plays full-frame).
 *
 * Browsing never tunes: only OK does, so walking the list can't spend provider connections or
 * flicker the video. It lists the same lineup the guide shows (the current category, overlay
 * applied), opens on the playing channel, and uses the guide row's focus vocabulary (Primary fill +
 * 2dp Primary border). BACK or RIGHT closes it.
 */
@Composable
internal fun BoxScope.LiveChannelListOverlay(
    channels: List<GuideChannel>,
    epg: Map<Int, GuideEpg>,
    playingContentId: String?,
    favoriteIds: Set<String>,
    onBrowse: (GuideChannel) -> Unit,
    onPick: (GuideChannel) -> Unit,
    onClose: () -> Unit,
) {
    val startIndex = channels.indexOfFirst { it.contentId == playingContentId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    val startFocus = remember { FocusRequester() }
    val latestClose by rememberUpdatedState(onClose)

    // Composed after the fullscreen BackHandler, so this one wins while the list is open.
    BackHandler(onBack = { latestClose() })
    LaunchedEffect(Unit) { startFocus.requestFocusOrFalse() }

    Column(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(CHANNEL_LIST_WIDTH)
            .background(
                Brush.horizontalGradient(
                    listOf(NuvioTheme.colors.Background.copy(alpha = 0.96f), NuvioTheme.colors.Background.copy(alpha = 0.82f)),
                ),
            )
            // Focus never leaves the panel by the D-pad: past the first/last row there is only the
            // guide hidden behind the video, and landing there would re-aim it.
            .focusGroup()
            .focusProperties { onExit = { cancelFocusChange() } }
            .onPreviewKeyEvent { event ->
                // RIGHT steps away from the side panel; LEFT has nowhere to go. Neither may leak to
                // focus search, which would land on the guide hidden behind the video.
                when (event.key) {
                    Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) latestClose()
                        true
                    }
                    Key.DirectionLeft -> true
                    else -> false
                }
            }
            .padding(vertical = NuvioTheme.spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.iptv_guide_channel_list_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.sm),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(channels, key = { _, ch -> ch.contentId }) { index, channel ->
                ChannelListRow(
                    number = index + 1,
                    channel = channel,
                    nowTitle = epg[channel.streamId]?.now?.title,
                    playing = channel.contentId == playingContentId,
                    favorite = channel.contentId in favoriteIds,
                    focusRequester = if (index == startIndex) startFocus else null,
                    onFocused = { onBrowse(channel) },
                    onClick = { onPick(channel) },
                )
            }
        }
    }
}

@Composable
private fun ChannelListRow(
    number: Int,
    channel: GuideChannel,
    nowTitle: String?,
    playing: Boolean,
    favorite: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val latestFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(focused) { if (focused) latestFocused() }
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHANNEL_LIST_ROW_HEIGHT)
            .clip(shape)
            .background(if (focused) NuvioTheme.colors.Primary.copy(alpha = 0.22f) else Color.Transparent)
            .border(
                if (focused) NuvioTheme.spacing.xxs else 0.dp,
                if (focused) NuvioTheme.colors.Primary else Color.Transparent,
                shape,
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.width(34.dp),
            maxLines = 1,
        )
        AsyncImage(
            model = channel.logo,
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(NuvioTheme.radii.xs)),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                if (playing) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Primary,
                        modifier = Modifier.size(NuvioTheme.spacing.lg),
                    )
                }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (favorite) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Primary,
                        modifier = Modifier.size(NuvioTheme.spacing.md),
                    )
                }
            }
            nowTitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val CHANNEL_LIST_WIDTH = 440.dp
private val CHANNEL_LIST_ROW_HEIGHT = 56.dp
