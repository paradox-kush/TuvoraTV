package com.nuvio.tv.ui.screens.search

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.tv.material3.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    // The chips item can leave the list while focused (rows reload, query cleared): never leave "focused" stuck true,
    // which would keep the chips' Back handler alive and eat every Back.
    DisposableEffect(Unit) { onDispose { onFocusChanged(false) } }
    Row(
        modifier = modifier
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                // A held Down that began on the field repeats onto the chips; swallow repeats so it stops here
                // instead of running on into row 1 (each chip move is one deliberate press).
                if (native.repeatCount > 0 &&
                    (native.keyCode == KeyEvent.KEYCODE_DPAD_UP || native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                ) {
                    return@onPreviewKeyEvent true
                }
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
            val isSelected = scope in selected
            val chipModifier = (if (index == 0) Modifier.focusRequester(firstChipFocusRequester) else Modifier)
                // Expose the multi-select state to accessibility services (the chip reported checked=false).
                .semantics {
                    role = Role.Checkbox
                    toggleableState = if (isSelected) ToggleableState.On else ToggleableState.Off
                }
            // Selected = gold fill + check mark. Focus = thick ring, independent of selection, so a focused
            // chip never reads as "selected" and a selected one never loses its ring when focused.
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(scope) },
                modifier = chipModifier,
                colors = FilterChipDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                    selectedContainerColor = NuvioTheme.colors.Secondary,
                    focusedSelectedContainerColor = NuvioTheme.colors.Secondary,
                    contentColor = NuvioTheme.colors.TextSecondary,
                    focusedContentColor = NuvioTheme.colors.TextPrimary,
                    selectedContentColor = NuvioTheme.colors.OnSecondary,
                    focusedSelectedContentColor = NuvioTheme.colors.OnSecondary,
                ),
                border = FilterChipDefaults.border(
                    border = Border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = shape),
                    focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape),
                    selectedBorder = Border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Primary), shape = shape),
                    focusedSelectedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape),
                ),
                shape = FilterChipDefaults.shape(shape = shape),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    if (isSelected) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(text = label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
