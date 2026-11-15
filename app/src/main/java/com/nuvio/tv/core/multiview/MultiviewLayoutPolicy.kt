// Ported from homelessbrian/NuvioPlusIPTV @ a86756891 (GPL-3.0), MultiviewSessionCore.kt (MultiviewLayout) and MultiviewUiPolicy.kt (multiviewPaneBounds).
package com.nuvio.tv.core.multiview

/** A tile's place in the video area, as fractions 0..1. [id] is the slot index (reading order). */
internal data class TileRect(
    val id: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Arrangements. A layout supports a range of tile counts ([minTiles]..[maxTiles]);
 * [selectTiles] is the count it takes when picked while the current count is outside that range.
 */
internal enum class MultiviewLayout(val minTiles: Int, val maxTiles: Int, val selectTiles: Int) {
    SINGLE(1, 1, 1),
    SIDE_BY_SIDE(2, 2, 2),
    TWO_STACKED(2, 2, 2),

    /** One big tile on the left, a column of small ones on the right. */
    FOCUS(3, 4, 3),

    /** 2x2; with three tiles the third spans the bottom row. */
    GRID(3, 4, 4);

    fun supports(tileCount: Int): Boolean = tileCount in minTiles..maxTiles
}

internal object MultiviewLayoutPolicy {
    const val MAX_TILES = 4

    /** The layout a new set of [count] tiles starts in. */
    fun defaultFor(count: Int): MultiviewLayout = when {
        count <= 1 -> MultiviewLayout.SINGLE
        count == 2 -> MultiviewLayout.SIDE_BY_SIDE
        count == 3 -> MultiviewLayout.FOCUS
        else -> MultiviewLayout.GRID
    }

    /** [layout] if it can hold [count] tiles, otherwise the default for that count. */
    fun effective(layout: MultiviewLayout, count: Int): MultiviewLayout {
        val n = count.coerceIn(1, MAX_TILES)
        return if (layout.supports(n)) layout else defaultFor(n)
    }

    /** Non-overlapping rectangles, one per tile, in reading order. Empty for a count of 0 or less. */
    fun bounds(layout: MultiviewLayout, tileCount: Int): List<TileRect> {
        if (tileCount <= 0) return emptyList()
        val n = tileCount.coerceAtMost(MAX_TILES)
        return when (effective(layout, n)) {
            MultiviewLayout.SINGLE -> listOf(TileRect(0, 0f, 0f, 1f, 1f))
            MultiviewLayout.SIDE_BY_SIDE -> listOf(
                TileRect(0, 0f, 0f, .5f, 1f),
                TileRect(1, .5f, 0f, 1f, 1f),
            )
            MultiviewLayout.TWO_STACKED -> listOf(
                TileRect(0, 0f, 0f, 1f, .5f),
                TileRect(1, 0f, .5f, 1f, 1f),
            )
            MultiviewLayout.FOCUS -> {
                val small = n - 1
                listOf(TileRect(0, 0f, 0f, BIG_SHARE, 1f)) + (0 until small).map { i ->
                    TileRect(i + 1, BIG_SHARE, i / small.toFloat(), 1f, (i + 1) / small.toFloat())
                }
            }
            MultiviewLayout.GRID -> if (n == 3) listOf(
                TileRect(0, 0f, 0f, .5f, .5f),
                TileRect(1, .5f, 0f, 1f, .5f),
                TileRect(2, 0f, .5f, 1f, 1f),
            ) else listOf(
                TileRect(0, 0f, 0f, .5f, .5f),
                TileRect(1, .5f, 0f, 1f, .5f),
                TileRect(2, 0f, .5f, .5f, 1f),
                TileRect(3, .5f, .5f, 1f, 1f),
            )
        }
    }

    /** Width share of the big tile in FOCUS (the fork's THREE_BIG_LEFT). */
    private const val BIG_SHARE = .6f
}
