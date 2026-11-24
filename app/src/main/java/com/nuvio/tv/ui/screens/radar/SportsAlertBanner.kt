@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.radar.alerts.AlertBanner
import com.nuvio.tv.core.radar.alerts.SportsAlertController
import com.nuvio.tv.ui.theme.NuvioTheme

/** "Hold OK to watch" / "Hold OK to open" / "Hold OK to open Sports". */
internal fun sportsAlertHint(banner: AlertBanner, canWatch: Boolean): String = when {
    banner.isSummary -> "Hold OK to open Sports"
    canWatch -> "Hold OK to watch"
    else -> "Hold OK to open"
}

/**
 * The sports alert banner: top-right, drawn over everything (players included) at the root of the activity content.
 * It is NOT focusable and never requests focus; the action is a hold of OK, handled at activity key dispatch
 * ([SportsAlertController.onKeyEvent]). Auto-dismisses after ten seconds (controller-owned timer).
 */
@Composable
fun BoxScope.SportsAlertBannerHost(controller: SportsAlertController) {
    val banner by controller.banner.collectAsStateWithLifecycle()
    val watchContentId by controller.watchContentId.collectAsStateWithLifecycle()
    // Keep the last banner while the exit animation plays.
    var last by remember { mutableStateOf<AlertBanner?>(null) }
    if (banner != null && last != banner) last = banner
    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopEnd).padding(NuvioTheme.spacing.xl),
    ) {
        val shown = banner ?: last ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(NuvioTheme.radii.md)
        Box(
            modifier = Modifier
                .width(420.dp)
                .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border.copy(alpha = 0.35f), shape)
                .background(NuvioTheme.colors.BackgroundElevated, shape)
                .padding(NuvioTheme.spacing.md),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(24.dp))
                Column(Modifier.padding(start = NuvioTheme.spacing.sm)) {
                    Text(
                        text = shown.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    shown.lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    shown.detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = sportsAlertHint(shown, watchContentId != null),
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(top = NuvioTheme.spacing.xxs),
                    )
                }
            }
        }
    }
}
