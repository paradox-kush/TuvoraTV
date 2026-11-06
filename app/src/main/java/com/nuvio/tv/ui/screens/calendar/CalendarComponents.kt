@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The Calendar's focusables. Every one draws the house focus ring on top of a fill change, whatever the settings UI
 * style, so D-pad focus is always visible (TV focus-highlight rule).
 */
@Composable
internal fun calendarFocusBorder(shape: Shape) = CardDefaults.border(
    border = Border.None,
    focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
)

@Composable
internal fun calendarCardColors(selected: Boolean = false) = CardDefaults.colors(
    containerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.22f) else NuvioTheme.colors.BackgroundCard,
    focusedContainerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.32f) else NuvioTheme.colors.BackgroundElevated
)

/** A pill used for the List/Month toggle and the settings choices. Selected = filled, focused = ring. */
@Composable
internal fun CalendarChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = calendarCardColors(selected),
        border = calendarFocusBorder(shape),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.04f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = 10.dp)
        )
    }
}

/** A round icon action (bell, hide, settings, month arrows). */
@Composable
internal fun CalendarIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        colors = IconButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.25f) else NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(50))
        )
    ) {
        Box { Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp)) }
    }
}
