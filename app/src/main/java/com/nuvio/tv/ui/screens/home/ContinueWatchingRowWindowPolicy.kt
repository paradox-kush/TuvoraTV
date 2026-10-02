package com.nuvio.tv.ui.screens.home

/**
 * UX12: where Home's Continue Watching row opens when the viewer returns (e.g. from the player).
 *
 * Catalog rows come back on the card they started on (0becbc42d). Continue Watching is
 * recency-ordered instead: a title started meanwhile is inserted at index 0, so "the card it
 * started on" sat one place to the right and the new title was just off-screen until a relaunch.
 * A LazyRow does the same thing live: when items land in front it keeps its first card by key.
 */
internal object ContinueWatchingRowWindowPolicy {

    private fun isRecencyRow(rowKey: String): Boolean = rowKey == MODERN_CONTINUE_WATCHING_ROW_KEY

    /**
     * First visible index for a row being rebuilt. [anchorKey] is the card that started the row's
     * window when it was left and [savedIndex] that card's index then. For Continue Watching the
     * window is kept only while nothing moved in front of it; otherwise it opens at the start.
     */
    fun restoredFirstIndex(rowKey: String, itemKeys: List<String>, anchorKey: String?, savedIndex: Int): Int {
        val anchorIndex = anchorKey?.let { itemKeys.indexOf(it) }?.takeIf { it >= 0 }
        if (!isRecencyRow(rowKey)) return anchorIndex ?: savedIndex
        return if (anchorIndex != null && anchorIndex == savedIndex) anchorIndex else 0
    }

    /**
     * True when the row should scroll back to its start because a new title took the front while
     * the row was showing its old front card. Never while the viewer is in the row, and never when
     * they had scrolled past the old front card (their window is theirs).
     */
    fun shouldSnapToStart(
        rowKey: String,
        previousLeaderKey: String?,
        itemKeys: List<String>,
        firstVisibleIndex: Int,
        rowHasFocus: Boolean,
    ): Boolean {
        if (!isRecencyRow(rowKey) || rowHasFocus) return false
        val leader = itemKeys.firstOrNull() ?: return false
        if (previousLeaderKey == null || previousLeaderKey == leader) return false
        if (firstVisibleIndex <= 0) return false
        val oldLeaderIndex = itemKeys.indexOf(previousLeaderKey)
        if (oldLeaderIndex < 0) return false
        return firstVisibleIndex <= oldLeaderIndex
    }
}
