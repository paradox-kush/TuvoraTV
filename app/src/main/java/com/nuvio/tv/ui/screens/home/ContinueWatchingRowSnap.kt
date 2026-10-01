package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos

/**
 * UX12: scrolls Continue Watching back to its start when a newly started title takes the front
 * while the row was showing its old front card (a LazyRow keeps its first card by key when items
 * land in front, leaving the new title off-screen). The decision is [ContinueWatchingRowWindowPolicy].
 */
@Composable
internal fun SnapContinueWatchingToNewLeader(
    rowKey: String,
    itemKeys: List<String>,
    listState: LazyListState,
    rowHasFocus: () -> Boolean,
) {
    val leaderKey = itemKeys.firstOrNull()
    val previousLeader = remember(rowKey, listState) { mutableStateOf(leaderKey) }
    val currentKeys = rememberUpdatedState(itemKeys)
    val currentRowHasFocus = rememberUpdatedState(rowHasFocus)
    LaunchedEffect(rowKey, listState, leaderKey) {
        val previous = previousLeader.value
        previousLeader.value = leaderKey
        if (previous == null || previous == leaderKey) return@LaunchedEffect
        // Let the row measure the new list first: that is when it re-anchors on its old first card.
        withFrameNanos { }
        if (
            ContinueWatchingRowWindowPolicy.shouldSnapToStart(
                rowKey = rowKey,
                previousLeaderKey = previous,
                itemKeys = currentKeys.value,
                firstVisibleIndex = listState.firstVisibleItemIndex,
                rowHasFocus = currentRowHasFocus.value(),
            ) && !listState.isScrollInProgress
        ) {
            runCatching { listState.scrollToItem(0) }
        }
    }
}
