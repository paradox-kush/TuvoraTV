package com.nuvio.tv.ui.screens.search

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * F85: Live TV / Movies / Series multi-select over the IPTV rows of a search. D-pad: Up leaves to
 * [upTarget] (the search field), Down to [downTarget] (the first result row); both are best-effort
 * (a failed request is swallowed so the key falls through to geometric focus). Left/Right walk the chips.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun IptvSearchScopeChips(
    selected: Set<IptvSearchScope>,
    onToggle: (IptvSearchScope) -> Unit,
    firstChipFocusRequester: FocusRequester,
    upTarget: FocusRequester,
    downTarget: () -> FocusRequester?,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val target = when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> upTarget
                    KeyEvent.KEYCODE_DPAD_DOWN -> downTarget()
                    else -> return@onPreviewKeyEvent false
                } ?: return@onPreviewKeyEvent false
                if (native.action == KeyEvent.ACTION_DOWN) {
                    // Consume only when the move landed; otherwise let geometric focus try.
                    val moved = runCatching { target.requestFocus() }.getOrDefault(false)
                    if (!moved) return@onPreviewKeyEvent false
                }
                true
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IptvSearchScope.entries.forEachIndexed { index, scope ->
            val label = stringResource(
                when (scope) {
                    IptvSearchScope.LIVE -> R.string.iptv_hub_tab_live
                    IptvSearchScope.MOVIES -> R.string.type_movies
                    IptvSearchScope.SERIES -> R.string.type_series_plural
                }
            )
            val shape = RoundedCornerShape(20.dp)
            FilterChip(
                selected = scope in selected,
                onClick = { onToggle(scope) },
                modifier = if (index == 0) Modifier.focusRequester(firstChipFocusRequester) else Modifier,
                colors = FilterChipDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.Secondary,
                    selectedContainerColor = NuvioTheme.colors.Secondary,
                    focusedSelectedContainerColor = NuvioTheme.colors.Secondary,
                    contentColor = NuvioTheme.colors.TextSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondary,
                    selectedContentColor = NuvioTheme.colors.OnSecondary,
                    focusedSelectedContentColor = NuvioTheme.colors.OnSecondary,
                ),
                border = FilterChipDefaults.border(
                    border = Border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = shape),
                    focusedBorder = Border(BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing), shape = shape),
                    selectedBorder = Border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Primary), shape = shape),
                    focusedSelectedBorder = Border(BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing), shape = shape),
                ),
                shape = FilterChipDefaults.shape(shape = shape),
            ) {
                Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
