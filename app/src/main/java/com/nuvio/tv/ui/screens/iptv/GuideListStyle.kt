package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Restyle "B" (2026-11-20): the guide list is two panels (groups, guide), rows divided by hairlines, the programme
 * cells one darker lane with a tick at each start, and focus a solid fill. Tokens live in [GuideFocusLanguage];
 * this file is the drawing. Everything here is a plain fill or a line: no blur, shadow or alpha layer (budget sticks).
 */
internal object GuideListStyle {
    /** The panel's corner radius (the mock's 16). */
    val PanelRadius = 16.dp

    /** Row hairlines and programme ticks: white at low alpha, so they read on the panel, the lane and AMOLED black. */
    val Hairline = Color.White.copy(alpha = 0.10f)

    /** The airing-now block on the lane (the mock's slightly lighter cell). */
    val LiveBlock = Color.White.copy(alpha = 0.10f)

    /** The monogram tile's container: white at low alpha over the panel (the mock's #262626). */
    val TileContainer = Color.White.copy(alpha = 0.10f)

    val FocusFill = Color(GuideFocusLanguage.LIST_FOCUS_FILL_ARGB)
    val FocusContent = Color(GuideFocusLanguage.LIST_FOCUS_CONTENT_ARGB)

    /** Panel and lane greys from the mock; pure black on AMOLED surfaces, where the hairlines carry the structure. */
    @Composable
    fun panel(): Color = if (NuvioTheme.colors.BackgroundElevated == Color.Black) Color.Black else Color(0xFF161616)

    @Composable
    fun lane(): Color = if (NuvioTheme.colors.BackgroundElevated == Color.Black) Color.Black else Color(0xFF111111)
}

/** One panel: a rounded surface that holds a whole list. */
internal fun Modifier.guidePanel(): Modifier = composed {
    clip(RoundedCornerShape(GuideListStyle.PanelRadius)).background(GuideListStyle.panel())
}

/**
 * A list row's marks from a [GuideFocusLanguage.ListLook]: the solid focus fill or the quiet selected fill, a thin
 * accent bar for the selected row, a red edge for the playing one, and a bottom hairline. All drawn behind the
 * content in one pass.
 */
internal fun Modifier.guideListRow(
    look: GuideFocusLanguage.ListLook,
    divider: Boolean,
    barColor: Color,
    playingColor: Color,
): Modifier = this.drawBehind {
    val hair = GuideFocusLanguage.HAIRLINE_DP.dp.toPx()
    when {
        look.solidFill -> drawRect(GuideListStyle.FocusFill)
        look.quietFill -> drawRect(Color.White.copy(alpha = GuideFocusLanguage.QUIET_FILL_ALPHA))
    }
    if (divider && look.hairline) {
        drawRect(GuideListStyle.Hairline, topLeft = Offset(0f, size.height - hair), size = Size(size.width, hair))
    }
    if (look.selectedBar) drawRect(barColor, size = Size(GuideFocusLanguage.SELECTED_BAR_DP.dp.toPx(), size.height))
    if (look.playingEdge) drawRect(playingColor, size = Size(GuideFocusLanguage.PLAYING_EDGE_DP.dp.toPx(), size.height))
}
