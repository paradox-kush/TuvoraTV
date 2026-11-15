package com.nuvio.tv.ui.screens.multiview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Width of the tile menu and the layout panel; the grid is laid out in the area left of it while either is open. */
internal val MENU_PANEL_WIDTH = 340.dp

/**
 * One row of a panel, in the Live TV channel-list row style: 48 dp tall at least, `radii.sm`, focused = `Primary` at 22%
 * fill with a 2 dp `Primary` border. It never scales, so a focused row cannot run past the screen edge (the TV `Button`
 * did). [selected] is the current choice (bold, soft fill) and stays distinct from focus.
 */
@Composable
internal fun MultiviewPanelRow(
    label: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
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
                val gained = it.isFocused && !focused
                focused = it.isFocused
                if (gained) onFocused?.invoke()
            }
            .then(onKey?.let { Modifier.onPreviewKeyEvent(it) } ?: Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    fontSize = 12.sp,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(
                text = trailing,
                fontSize = 12.sp,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

/** A small caps label between groups of rows. Not focusable. */
@Composable
internal fun MultiviewSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm),
    )
}

/**
 * A right-side panel in the style of the Live TV channel list: opaque `Background`, a 1 dp `Border` on its left edge,
 * a focus group for its rows. There is no `focusProperties { onExit = cancelFocusChange }` here: focus properties are
 * inherited by every child, so rows could not hand focus to each other (the picker bug). The tiles behind a panel are
 * unfocusable while it is open (`canFocus = !overlayOpen`), so focus cannot land on one and re-aim the sound.
 *
 * [onLeft] runs on Left (the menu closes, the layout panel returns to the menu).
 */
@Composable
internal fun BoxScope.MultiviewSidePanel(
    title: String,
    width: Dp,
    caption: String? = null,
    onLeft: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = NuvioTheme.colors.Border
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(width)
            .background(NuvioTheme.colors.Background)
            .drawBehind { drawRect(border, topLeft = Offset.Zero, size = Size(1.dp.toPx(), size.height)) }
            .focusGroup()
            .then(
                if (onLeft == null) Modifier else Modifier.onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionLeft) {
                        if (event.type == KeyEventType.KeyDown) onLeft()
                        true
                    } else false
                },
            )
            .padding(vertical = NuvioTheme.spacing.xl, horizontal = NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = caption.orEmpty(),
            fontSize = 12.sp,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 3,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
        )
        content()
    }
}

/**
 * The tile menu: what [items] says, in that order, the tile's own items first and then a "MULTIVIEW" group. The
 * [entryItem] takes focus (the first row, or the row the viewer came back from). Left, Back and Menu close it.
 */
@Composable
internal fun BoxScope.MultiviewTileMenu(
    title: String,
    caption: String?,
    items: List<TileMenuItem>,
    entryItem: TileMenuItem?,
    layoutLabel: String,
    tileCount: Int,
    onClose: () -> Unit,
    onItem: (TileMenuItem) -> Unit,
) {
    val entry = remember { FocusRequester() }
    LaunchedEffect(Unit) { GuideFocusRequestLoop.requestUntilLanded { entry.requestFocusOrFalse() } }
    val entryTarget = entryItem?.takeIf { it in items } ?: items.first()
    MultiviewSidePanel(title = title, caption = caption, width = MENU_PANEL_WIDTH, onLeft = onClose) {
        var sectionShown = false
        items.forEach { item ->
            if (item.inMultiviewGroup && !sectionShown) {
                sectionShown = true
                MultiviewSectionLabel(stringResource(R.string.multiview_menu_section))
            }
            MultiviewPanelRow(
                label = stringResource(item.labelRes()),
                sub = when (item) {
                    TileMenuItem.KEEP_SOUND -> stringResource(R.string.multiview_menu_keep_sound_sub)
                    TileMenuItem.WATCH_IN_PLAYER -> stringResource(R.string.multiview_menu_watch_in_player_sub)
                    TileMenuItem.ADD_ANOTHER ->
                        stringResource(R.string.multiview_menu_add_another_sub, tileCount + 1, MultiviewLayoutPolicy.MAX_TILES)
                    else -> null
                },
                trailing = if (item == TileMenuItem.CHANGE_LAYOUT) layoutLabel else null,
                focusRequester = if (item == entryTarget) entry else null,
                onClick = { onItem(item) },
            )
        }
    }
}

private fun TileMenuItem.labelRes(): Int = when (this) {
    TileMenuItem.BACK_TO_GRID -> R.string.multiview_menu_back_to_grid
    TileMenuItem.WATCH_IN_PLAYER -> R.string.multiview_menu_watch_in_player
    TileMenuItem.OPEN_IN_PLAYER -> R.string.multiview_menu_open_in_player
    TileMenuItem.RETRY -> R.string.multiview_menu_retry
    TileMenuItem.KEEP_SOUND -> R.string.multiview_menu_keep_sound
    TileMenuItem.FOLLOW_FOCUS -> R.string.multiview_menu_follow_focus
    TileMenuItem.ADD_CHANNEL -> R.string.multiview_menu_add_channel
    TileMenuItem.REPLACE_CHANNEL -> R.string.multiview_menu_replace
    TileMenuItem.REMOVE -> R.string.multiview_menu_remove
    TileMenuItem.CLEAR_CHANNEL -> R.string.multiview_menu_clear
    TileMenuItem.REMOVE_TILE -> R.string.multiview_menu_remove_tile
    TileMenuItem.ADD_ANOTHER -> R.string.multiview_menu_add_another
    TileMenuItem.CHANGE_LAYOUT -> R.string.multiview_menu_layout
    TileMenuItem.EXIT -> R.string.multiview_menu_exit
}

/** The layout's name as the menu's trailing text and the panel's cells show it. */
@Composable
internal fun layoutFamilyLabel(family: MultiviewLayoutPolicy.Family): String = stringResource(
    when (family) {
        MultiviewLayoutPolicy.Family.SAME_SIZE -> R.string.multiview_layout_same_size
        MultiviewLayoutPolicy.Family.ONE_BIG -> R.string.multiview_layout_one_big
    },
)

/**
 * Two cells, "Same size" and "One big", each drawn for the tiles on screen. Focus opens on the current one; OK applies
 * and closes; Left and Back return to the tile menu. Nothing scrolls.
 */
@Composable
internal fun BoxScope.MultiviewLayoutPicker(
    tileCount: Int,
    current: MultiviewLayout,
    onPick: (MultiviewLayoutPolicy.Family) -> Unit,
    onBack: () -> Unit,
) {
    val options = remember(tileCount) { MultiviewLayoutChoices.options(tileCount) }
    val currentFamily = MultiviewLayoutPolicy.familyOf(current)
    val entryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { GuideFocusRequestLoop.requestUntilLanded { entryFocus.requestFocusOrFalse() } }
    MultiviewSidePanel(
        title = stringResource(R.string.multiview_layout_title),
        caption = stringResource(R.string.multiview_layout_caption, tileCount),
        width = MENU_PANEL_WIDTH,
        onLeft = onBack,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            options.forEach { choice ->
                val family = MultiviewLayoutPolicy.familyOf(choice.layout)
                LayoutCell(
                    choice = choice,
                    family = family,
                    isCurrent = family == currentFamily,
                    focusRequester = if (family == currentFamily) entryFocus else null,
                    onClick = { onPick(family) },
                )
            }
        }
    }
}

@Composable
private fun LayoutCell(
    choice: LayoutChoice,
    family: MultiviewLayoutPolicy.Family,
    isCurrent: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    val rects = remember(choice) { MultiviewLayoutPolicy.bounds(choice.layout, choice.tileCount) }
    val fill = if (isCurrent) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary.copy(alpha = 0.55f)
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
                text = layoutFamilyLabel(family),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            )
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.multiview_layout_current),
                    fontSize = 12.sp,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
    }
}
