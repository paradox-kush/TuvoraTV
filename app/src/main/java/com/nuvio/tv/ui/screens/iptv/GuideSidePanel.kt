package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/** Width of a right-side IPTV panel (the approved Multiview panel width). */
internal val GUIDE_PANEL_WIDTH = 340.dp

/** The 5% TV safe area (48 dp sides, 27 dp top and bottom on a 960 x 540 dp screen). */
private val SAFE_SIDE = 48.dp
private val SAFE_TOP_BOTTOM = 27.dp

/** One row of a right-side panel: a label, an optional consequence line, and an optional section caption above. */
internal data class GuidePanelItem<K>(
    val key: K,
    val label: String,
    val sub: String? = null,
    val sectionLabel: String? = null,
    val selected: Boolean = false,
)

/**
 * Redesign change 3: a right-side panel over a 50% scrim (no AlertDialog, no Cancel row: Back or MENU close it, A14).
 * The D-pad is handled here explicitly rather than by Compose focus search, which would leave the panel for the guide
 * rows behind the scrim: UP / DOWN walk the rows with no wrap (a held UP can never land on Hide), LEFT / RIGHT do
 * nothing, and the press that opened the panel is ignored until a fresh press arrives ([PanelKeyGate], UX213).
 *
 * [focusKey] is the row that holds the cursor on open (and after a row that keeps the panel open ran).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <K : Any> BoxScope.GuideSidePanel(
    title: String,
    subtitle: String?,
    items: List<GuidePanelItem<K>>,
    focusKey: K?,
    onItem: (K) -> Unit,
    onClose: () -> Unit,
    width: Dp = GUIDE_PANEL_WIDTH,
) {
    val requesters = remember { HashMap<K, FocusRequester>() }
    fun requesterFor(key: K) = requesters.getOrPut(key) { FocusRequester() }
    var focusedIndex by remember { mutableStateOf(-1) }
    val gate = remember { PanelKeyGate() }
    BackHandler(onBack = onClose)

    // Entry: the asked row (else row 1) takes the cursor once it is attached.
    val target = focusKey?.takeIf { key -> items.any { it.key == key } } ?: items.firstOrNull()?.key
    LaunchedEffect(target, items.size) {
        val key = target ?: return@LaunchedEffect
        GuideFocusRequestLoop.requestUntilLanded { requesterFor(key).requestFocusOrFalse() }
    }

    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)))
    val border = NuvioTheme.colors.Border
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(width + SAFE_SIDE)
            .background(NuvioTheme.colors.Background)
            .drawBehind { drawRect(border, topLeft = Offset.Zero, size = Size(1.dp.toPx(), size.height)) }
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                // Back is answered by the BackHandler; never swallow it here.
                if (event.key == Key.Back) return@onPreviewKeyEvent false
                if (!gate.admit(down, event.nativeKeyEvent.repeatCount)) return@onPreviewKeyEvent true
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (down && items.isNotEmpty()) {
                            val step = if (event.key == Key.DirectionUp) -1 else 1
                            val next = (focusedIndex + step).coerceIn(0, items.lastIndex)
                            if (next != focusedIndex) requesterFor(items[next].key).requestFocusOrFalse()
                        }
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> true
                    Key.Menu -> {
                        if (down) onClose()
                        true
                    }
                    else -> false
                }
            }
            .padding(start = NuvioTheme.spacing.lg, end = SAFE_SIDE, top = SAFE_TOP_BOTTOM, bottom = SAFE_TOP_BOTTOM),
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
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        items.forEachIndexed { index, item -> androidx.compose.runtime.key(item.key) {
            if (item.sectionLabel != null) {
                Text(
                    text = item.sectionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.md),
                )
            }
            var focused by remember(item.key) { mutableStateOf(false) }
            val shape = RoundedCornerShape(NuvioTheme.radii.sm)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .guideFocusLook(GuideFocusLanguage.look(focused = focused, selected = item.selected), shape)
                    .focusRequester(requesterFor(item.key))
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) focusedIndex = index
                    }
                    // Long-press is the same as OK: no second-level gestures in a panel.
                    .combinedClickable(onClick = { onItem(item.key) }, onLongClick = { onItem(item.key) })
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    fontWeight = if (item.selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.sub != null) {
                    Text(
                        text = item.sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } }
    }
}

/** Redesign change 3: the channel's options, as rows from [GuideChannelPanelPolicy]. */
@Composable
internal fun BoxScope.GuideChannelPanel(
    channelName: String,
    rows: List<GuideChannelPanelPolicy.Row>,
    focusRow: GuideChannelPanelPolicy.Row?,
    guideSize: com.nuvio.tv.core.iptv.GuideLayoutPreset,
    onRow: (GuideChannelPanelPolicy.Row) -> Unit,
    onClose: () -> Unit,
) {
    val items = rows.mapIndexed { index, row ->
        GuidePanelItem(
            key = row,
            label = androidx.compose.ui.res.stringResource(
                when (row) {
                    GuideChannelPanelPolicy.Row.WATCH_FULLSCREEN -> com.nuvio.tv.R.string.iptv_panel_watch_fullscreen
                    GuideChannelPanelPolicy.Row.OPEN_GUIDE -> com.nuvio.tv.R.string.iptv_panel_open_guide
                    GuideChannelPanelPolicy.Row.ADD_FAVORITE -> com.nuvio.tv.R.string.iptv_guide_add_favorite
                    GuideChannelPanelPolicy.Row.REMOVE_FAVORITE -> com.nuvio.tv.R.string.iptv_guide_remove_favorite
                    GuideChannelPanelPolicy.Row.ADD_TO_MULTIVIEW -> com.nuvio.tv.R.string.iptv_guide_add_to_multiview
                    GuideChannelPanelPolicy.Row.MOVE_UP -> com.nuvio.tv.R.string.iptv_guide_move_up
                    GuideChannelPanelPolicy.Row.MOVE_DOWN -> com.nuvio.tv.R.string.iptv_guide_move_down
                    GuideChannelPanelPolicy.Row.UNPIN -> com.nuvio.tv.R.string.iptv_guide_unpin
                    GuideChannelPanelPolicy.Row.FIX_GUIDE -> com.nuvio.tv.R.string.iptv_guide_fix_guide
                    GuideChannelPanelPolicy.Row.GUIDE_SIZE -> com.nuvio.tv.R.string.iptv_guide_size
                    GuideChannelPanelPolicy.Row.HIDE -> com.nuvio.tv.R.string.iptv_guide_hide_channel
                    GuideChannelPanelPolicy.Row.STREAM_INFO -> com.nuvio.tv.R.string.cd_stream_info
                }
            ),
            sub = when (row) {
                GuideChannelPanelPolicy.Row.OPEN_GUIDE -> androidx.compose.ui.res.stringResource(com.nuvio.tv.R.string.iptv_panel_open_guide_sub)
                GuideChannelPanelPolicy.Row.UNPIN -> androidx.compose.ui.res.stringResource(com.nuvio.tv.R.string.iptv_panel_unpin_sub)
                GuideChannelPanelPolicy.Row.HIDE -> androidx.compose.ui.res.stringResource(com.nuvio.tv.R.string.iptv_panel_hide_sub)
                GuideChannelPanelPolicy.Row.FIX_GUIDE -> androidx.compose.ui.res.stringResource(com.nuvio.tv.R.string.iptv_panel_fix_guide_sub)
                GuideChannelPanelPolicy.Row.GUIDE_SIZE -> androidx.compose.ui.res.stringResource(guideSizeLabel(guideSize))
                else -> null
            },
            sectionLabel = GuideChannelPanelPolicy.labelAbove(rows, index)?.let { section ->
                androidx.compose.ui.res.stringResource(
                    when (section) {
                        GuideChannelPanelPolicy.Section.WATCH -> com.nuvio.tv.R.string.iptv_panel_section_watch
                        GuideChannelPanelPolicy.Section.FAVORITES -> com.nuvio.tv.R.string.iptv_panel_section_favorites
                        GuideChannelPanelPolicy.Section.MULTIVIEW -> com.nuvio.tv.R.string.multiview_entry
                        GuideChannelPanelPolicy.Section.ORDER -> com.nuvio.tv.R.string.iptv_panel_section_order
                        GuideChannelPanelPolicy.Section.GUIDE -> com.nuvio.tv.R.string.iptv_panel_section_guide
                        GuideChannelPanelPolicy.Section.TIDY -> com.nuvio.tv.R.string.iptv_panel_section_tidy
                        GuideChannelPanelPolicy.Section.INFO -> com.nuvio.tv.R.string.iptv_panel_section_info
                    }
                )
            },
        )
    }
    GuideSidePanel(title = channelName, subtitle = null, items = items, focusKey = focusRow, onItem = onRow, onClose = onClose)
}

/** The guide's size as a right-side panel, opening on the size in use; OK applies it. */
@Composable
internal fun BoxScope.GuideLayoutPanel(
    selected: com.nuvio.tv.core.iptv.GuideLayoutPreset,
    onSelect: (com.nuvio.tv.core.iptv.GuideLayoutPreset) -> Unit,
    onClose: () -> Unit,
) {
    GuideSidePanel(
        title = androidx.compose.ui.res.stringResource(com.nuvio.tv.R.string.iptv_guide_size),
        subtitle = null,
        items = com.nuvio.tv.core.iptv.GuideLayoutPreset.entries.map { preset ->
            GuidePanelItem(preset, androidx.compose.ui.res.stringResource(guideSizeLabel(preset)), selected = preset == selected)
        },
        focusKey = selected,
        onItem = onSelect,
        onClose = onClose,
    )
}

@androidx.annotation.StringRes
internal fun guideSizeLabel(preset: com.nuvio.tv.core.iptv.GuideLayoutPreset): Int = when (preset) {
    com.nuvio.tv.core.iptv.GuideLayoutPreset.COMPACT -> com.nuvio.tv.R.string.iptv_guide_size_compact
    com.nuvio.tv.core.iptv.GuideLayoutPreset.STANDARD -> com.nuvio.tv.R.string.iptv_guide_size_standard
    com.nuvio.tv.core.iptv.GuideLayoutPreset.LARGE -> com.nuvio.tv.R.string.iptv_guide_size_large
}
