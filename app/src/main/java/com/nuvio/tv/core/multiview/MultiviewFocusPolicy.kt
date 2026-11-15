// Ported from homelessbrian/NuvioPlusIPTV @ a86756891 (GPL-3.0), MultiviewUiPolicy.kt (multiviewNextPane), made geometric.
package com.nuvio.tv.core.multiview

internal enum class MultiviewDirection { LEFT, RIGHT, UP, DOWN }

internal object MultiviewFocusPolicy {
    private const val EPS = 0.001f

    /**
     * The tile a D-pad press moves to, or null at an edge (never wraps, never leaves the area).
     * A neighbour lies wholly beyond the edge in that direction and overlaps across it. The nearest
     * one wins; ties go to the nearer centre, then the lower id.
     */
    fun neighbour(rects: List<TileRect>, fromId: Int, direction: MultiviewDirection): Int? {
        val from = rects.firstOrNull { it.id == fromId } ?: return null
        return rects.asSequence()
            .filter { it.id != fromId }
            .mapNotNull { c ->
                val horizontal = direction == MultiviewDirection.LEFT || direction == MultiviewDirection.RIGHT
                val gap = when (direction) {
                    MultiviewDirection.RIGHT -> c.left - from.right
                    MultiviewDirection.LEFT -> from.left - c.right
                    MultiviewDirection.DOWN -> c.top - from.bottom
                    MultiviewDirection.UP -> from.top - c.bottom
                }
                if (gap < -EPS) return@mapNotNull null
                val overlap = if (horizontal) {
                    minOf(from.bottom, c.bottom) - maxOf(from.top, c.top)
                } else {
                    minOf(from.right, c.right) - maxOf(from.left, c.left)
                }
                if (overlap <= EPS) return@mapNotNull null
                val offAxis = if (horizontal) kotlin.math.abs(c.centerY - from.centerY)
                else kotlin.math.abs(c.centerX - from.centerX)
                Triple(c.id, gap.coerceAtLeast(0f), offAxis)
            }
            .sortedWith(
                compareBy<Triple<Int, Float, Float>> { Math.round(it.second / EPS) }
                    .thenBy { Math.round(it.third / EPS) }
                    .thenBy { it.first },
            )
            .firstOrNull()?.first
    }
}
