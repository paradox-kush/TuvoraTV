package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusGroup
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.EpisodeBucket

/**
 * Range tabs for seasons too long to walk with a D-pad. One press moves fifty episodes instead of
 * one, which is the difference between reachable and not for a thousand-episode soap.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodeRangeTabs(
    ranges: List<EpisodeBucket>,
    selectedRange: String?,
    onRangeSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedTabFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
) {
    if (ranges.isEmpty()) return

    val tabShape: Shape = remember { RoundedCornerShape(20.dp) }
    val tabBorder = CardDefaults.border(
        focusedBorder = Border(
            border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
            shape = RoundedCornerShape(20.dp),
        ),
    )
    val tabScale = CardDefaults.scale(focusedScale = 1.0f)
    val textSecondary = NuvioTheme.extendedColors.textSecondary
    val selectedIndex = remember(ranges, selectedRange) {
        ranges.indexOfFirst { it.label == selectedRange }.coerceAtLeast(0)
    }
    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)

    LaunchedEffect(ranges, selectedRange) {
        val index = ranges.indexOfFirst { it.label == selectedRange }
        if (index < 0) return@LaunchedEffect
        val visible = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }
        if (index !in visible) lazyListState.scrollToItem(index)
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup(),
        state = lazyListState,
        contentPadding = PaddingValues(
            horizontal = NuvioTheme.spacing.xxxl,
            vertical = NuvioTheme.spacing.md,
        ),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        items(ranges, key = { it.label }) { range ->
            val isSelected = range.label == selectedRange
            var isFocused by remember { mutableStateOf(false) }

            Card(
                onClick = { onRangeSelected(range.label) },
                // The neighbouring rows override their up/down with focusProperties, which wins over
                // geometric search — so this row has to claim its own edges or it is unreachable.
                modifier = Modifier
                    .onFocusChanged { isFocused = it.isFocused }
                    .then(
                        if (isSelected && selectedTabFocusRequester != null) {
                            Modifier.focusRequester(selectedTabFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                    .focusProperties {
                        upFocusRequester?.let { up = it }
                        downFocusRequester?.let { down = it }
                    },
                shape = CardDefaults.shape(shape = tabShape),
                colors = CardDefaults.colors(
                    containerColor = if (isSelected) {
                        NuvioTheme.colors.SurfaceVariant
                    } else {
                        NuvioTheme.colors.BackgroundCard
                    },
                    focusedContainerColor = NuvioTheme.colors.Secondary,
                ),
                border = tabBorder,
                scale = tabScale,
            ) {
                Text(
                    text = range.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        isFocused -> NuvioTheme.colors.OnSecondary
                        isSelected -> NuvioTheme.colors.TextPrimary
                        else -> textSecondary
                    },
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp),
                )
            }
        }
    }
}
