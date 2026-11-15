package com.nuvio.tv.ui.screens.multiview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.multiview.LayoutChoice
import com.nuvio.tv.core.multiview.MultiviewLayout
import com.nuvio.tv.core.multiview.MultiviewLayoutChoices
import com.nuvio.tv.core.multiview.MultiviewLayoutPolicy
import com.nuvio.tv.core.multiview.TileMenuItem
import com.nuvio.tv.ui.screens.iptv.GuideFocusRequestLoop
import com.nuvio.tv.ui.screens.iptv.requestFocusOrFalse
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A side panel over the grid, in the style of the Live TV channel list: a scrim on the right, a focus group the D-pad
 * cannot leave (past its edges there is only the grid, and focus landing there would re-aim the sound).
 */
@Composable
internal fun BoxScope.MultiviewSidePanel(
    title: String,
    width: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(width)
            .background(
                Brush.horizontalGradient(
                    listOf(NuvioTheme.colors.Background.copy(alpha = 0.82f), NuvioTheme.colors.Background.copy(alpha = 0.97f)),
                ),
            )
            .focusGroup()
            .focusProperties { onExit = { cancelFocusChange() } }
            .padding(vertical = NuvioTheme.spacing.xl, horizontal = NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
        )
        content()
    }
}

/** The tile menu: what [items] says, in that order. The first item takes focus. */
@Composable
internal fun BoxScope.MultiviewTileMenu(
    title: String,
    items: List<TileMenuItem>,
    onItem: (TileMenuItem) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { GuideFocusRequestLoop.requestUntilLanded { first.requestFocusOrFalse() } }
    MultiviewSidePanel(title = title, width = 380.dp) {
        items.forEachIndexed { index, item ->
            Button(
                onClick = { onItem(item) },
                modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
            ) { Text(stringResource(item.labelRes())) }
        }
    }
}

private fun TileMenuItem.labelRes(): Int = when (this) {
    TileMenuItem.FULL_SCREEN -> R.string.multiview_menu_full_screen
    TileMenuItem.BACK_TO_GRID -> R.string.multiview_menu_back_to_grid
    TileMenuItem.OPEN_IN_PLAYER -> R.string.multiview_menu_open_in_player
    TileMenuItem.RETRY -> R.string.multiview_menu_retry
    TileMenuItem.KEEP_SOUND -> R.string.multiview_menu_keep_sound
    TileMenuItem.FOLLOW_FOCUS -> R.string.multiview_menu_follow_focus
    TileMenuItem.ADD_CHANNEL -> R.string.multiview_menu_add_channel
    TileMenuItem.REPLACE_CHANNEL -> R.string.multiview_menu_replace
    TileMenuItem.REMOVE -> R.string.multiview_menu_remove
    TileMenuItem.CHANGE_LAYOUT -> R.string.multiview_menu_layout
    TileMenuItem.EXIT -> R.string.multiview_menu_exit
}

/** Small pictures of the arrangements that fit the tiles on screen, plus the ones that add empty tiles. */
@Composable
internal fun BoxScope.MultiviewLayoutPicker(
    tileCount: Int,
    current: MultiviewLayout,
    onPick: (LayoutChoice) -> Unit,
) {
    val options = remember(tileCount) { MultiviewLayoutChoices.options(tileCount) }
    val entry = options.indexOfFirst { it.layout == current && it.tileCount == tileCount }.coerceAtLeast(0)
    val entryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { GuideFocusRequestLoop.requestUntilLanded { entryFocus.requestFocusOrFalse() } }
    MultiviewSidePanel(title = stringResource(R.string.multiview_layout_title), width = 340.dp) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        ) {
            items(options.size) { index ->
                val choice = options[index]
                LayoutCell(
                    choice = choice,
                    selected = index == entry,
                    focusRequester = if (index == entry) entryFocus else null,
                    onClick = { onPick(choice) },
                )
            }
        }
    }
}

@Composable
private fun LayoutCell(
    choice: LayoutChoice,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    val rects = remember(choice) { MultiviewLayoutPolicy.bounds(choice.layout, choice.tileCount) }
    val fill = if (selected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) NuvioTheme.colors.Primary.copy(alpha = 0.22f) else Color.Transparent)
            .border(
                if (focused) NuvioTheme.spacing.xxs else 1.dp,
                if (focused) NuvioTheme.colors.Primary else NuvioTheme.colors.Border,
                shape,
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 112.dp, height = 63.dp)) {
            val gap = 2.dp.toPx()
            rects.forEach { r ->
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(r.left * size.width + gap, r.top * size.height + gap),
                    size = Size(r.width * size.width - 2 * gap, r.height * size.height - 2 * gap),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        Column {
            Text(
                text = stringResource(choice.layout.nameRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                text = if (choice.tileCount == 1) stringResource(R.string.multiview_layout_one_tile)
                else stringResource(R.string.multiview_layout_tiles, choice.tileCount),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
    }
}

private fun MultiviewLayout.nameRes(): Int = when (this) {
    MultiviewLayout.SINGLE -> R.string.multiview_layout_single
    MultiviewLayout.SIDE_BY_SIDE -> R.string.multiview_layout_side_by_side
    MultiviewLayout.TWO_STACKED -> R.string.multiview_layout_two_stacked
    MultiviewLayout.FOCUS -> R.string.multiview_layout_focus
    MultiviewLayout.GRID -> R.string.multiview_layout_grid
}
