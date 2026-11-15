package com.nuvio.tv.ui.screens.iptv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Redesign change 4 (05-fullscreen): the zap banner, separate from the control bar. It names the channel a zap
 * landed on (or, while surfing, the one aimed at) with number, logo, name, what is on with progress and what is
 * next, and the clock. It has NO focus target, so it can never swallow a key, and it leaves by itself after
 * [LiveZapModePolicy.BANNER_MS]. The `bannerVisible` read happens here, in this composable's own scope, so a zap
 * press recomposes the banner and not the guide (B180).
 */
@Composable
internal fun BoxScope.GuideZapBanner(
    fullscreen: Boolean,
    controls: FullscreenControlsState,
    viewModel: XtreamLiveGuideViewModel,
    playingContentId: String?,
    nowMs: Long,
    /** Surf mode: the aimed channel waiting for OK or the countdown (null when nothing is pending). Read here, not by the guide. */
    surfAimOf: () -> String?,
    /** Bumped on each aim, so the countdown bar restarts. */
    surfTickOf: () -> Int,
) {
    if (!fullscreen || !controls.bannerVisible || controls.visible) return
    val surfAim = surfAimOf()
    val surfTick = surfTickOf()
    LaunchedEffect(controls.bannerTick) {
        delay(LiveZapModePolicy.BANNER_MS)
        controls.bannerVisible = false
    }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val aimedId = ui.focusedChannelId ?: playingContentId
    val channel = ui.channels.firstOrNull { it.contentId == aimedId } ?: return
    val number = ui.channels.indexOfFirst { it.contentId == channel.contentId } + 1
    val epg = remember(ui.epg[channel.streamId], nowMs) { ui.epg[channel.streamId]?.let { GuideWindowRows.atTime(it, nowMs) } }
    val playingName = ui.channels.firstOrNull { it.contentId == playingContentId }?.name
    val surfing = surfAim != null && surfAim != playingContentId

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            // The 5% TV safe area: 48 dp sides, 27 dp bottom.
            .padding(start = 48.dp, end = 48.dp, bottom = 27.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            if (number > 0) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (GuideNoGuidePolicy.needsMonogram(channel.logo)) {
                MonogramTile(name = channel.name, modifier = Modifier.size(44.dp))
            } else {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(NuvioTheme.radii.xs)),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = epg?.now?.let { "${timeRange(it)}  ${it.title}" } ?: stringResource(R.string.iptv_guide_no_information),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = guideHhMm(nowMs),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        epg?.now?.let { now ->
            val duration = (now.endMs - now.startMs).coerceAtLeast(1L)
            val progress = ((nowMs - now.startMs).toFloat() / duration).coerceIn(0f, 1f)
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(NuvioTheme.colors.Border)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(NuvioTheme.colors.FocusRing))
            }
        }
        epg?.next?.let { next ->
            Text(
                text = stringResource(R.string.iptv_guide_next_programme, timeRange(next), next.title),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs),
            )
        }
        if (surfing) {
            // Surf: the picture is still the old channel; the bar runs down to the tune. OK tunes now, Back cancels.
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text(
                    text = playingName?.let { stringResource(R.string.iptv_zap_surf_still_watching, it) }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.iptv_zap_surf_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                )
            }
            val countdown = remember(surfTick) { Animatable(1f) }
            LaunchedEffect(surfTick) {
                countdown.animateTo(0f, tween(LiveZapModePolicy.SURF_COMMIT_MS.toInt(), easing = LinearEasing))
            }
            Box(
                Modifier.padding(top = NuvioTheme.spacing.xs).fillMaxWidth().height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(NuvioTheme.colors.Border),
            ) {
                Box(Modifier.fillMaxWidth(countdown.value).fillMaxHeight().background(Color.White))
            }
        }
    }
}
