package com.nuvio.tv.ui.screens.radar

/**
 * Where D-pad focus goes in the match channel sheet after its rows are re-grouped (results stream in, a
 * channel's confidence is upgraded and it moves between groups). Identity, never a row number.
 *
 * Still listed -> the same channel. Gone -> the nearest remaining row, judged by where it sat in the previous
 * order (the row that slid into its place first, then the closest above). Nothing was focused, or the list is
 * empty -> no target.
 */
internal object ChannelFocusRestorePolicy {
    fun target(focusedId: String?, previousIds: List<String>, newIds: List<String>): String? {
        if (focusedId == null || newIds.isEmpty()) return null
        if (focusedId in newIds) return focusedId
        val at = previousIds.indexOf(focusedId)
        if (at < 0) return newIds.first()
        val remaining = newIds.toSet()
        for (distance in 1 until previousIds.size) {
            previousIds.getOrNull(at + distance)?.takeIf { it in remaining }?.let { return it }
            previousIds.getOrNull(at - distance)?.takeIf { it in remaining }?.let { return it }
        }
        return newIds.first()
    }
}
