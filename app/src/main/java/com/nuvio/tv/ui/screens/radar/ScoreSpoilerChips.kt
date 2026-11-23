@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.tv.core.radar.ScoreSpoilerSettings
import com.nuvio.tv.ui.screens.calendar.CalendarChip
import com.nuvio.tv.ui.theme.NuvioTheme

/** "Delay: Off" / "Delay: 15 s" ... — the text of the delay chip. */
internal fun scoreDelayLabel(seconds: Int): String =
    if (seconds <= 0) "Delay: Off" else "Delay: $seconds s"

/**
 * Sports hub header chips: one press toggles "Scores shown" / "Scores hidden"; while shown, a second chip cycles the delay
 * (Off, 15 s, 30 s, 60 s). Both are the Calendar's pill (fill + focus ring on D-pad focus). Plain text, no dialog.
 */
@Composable
internal fun ScoreSpoilerChips(
    settings: ScoreSpoilerSettings,
    onToggleShown: () -> Unit,
    onCycleDelay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalendarChip(
            label = if (settings.shown) "Scores shown" else "Scores hidden",
            selected = !settings.shown,
            onClick = onToggleShown,
        )
        if (settings.shown) {
            CalendarChip(
                label = scoreDelayLabel(ScoreSpoilerSettings.sanitizeDelay(settings.delaySeconds)),
                selected = settings.delaySeconds > 0,
                onClick = onCycleDelay,
            )
        }
    }
}
