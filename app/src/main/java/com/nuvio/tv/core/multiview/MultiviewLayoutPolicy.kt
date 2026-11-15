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

    /** "One big": one big tile on the left, a column of small ones on the right. Two tiles are one big, one small. */
    FOCUS(2, 4, 3),

    /** "Same size" for three or four: 2x2; with three tiles the third sits centred below. */
    GRID(3, 4, 4);

    fun supports(tileCount: Int): Boolean = tileCount in minTiles..maxTiles
}

internal object MultiviewLayoutPolicy {
    const val MAX_TILES = 4

    /** The layout a new set of [count] tiles starts in. */
    fun defaultFor(count: Int): MultiviewLayout = when {
        count <= 1 -> MultiviewLayout.SINGLE
        count == 2 -> MultiviewLayout.SIDE_BY_SIDE
        else -> MultiviewLayout.GRID
    }

    /** The two arrangements the viewer chooses between; the layout only arranges the tiles that are there. */
    enum class Family { SAME_SIZE, ONE_BIG }

    fun familyOf(layout: MultiviewLayout): Family =
        if (layout == MultiviewLayout.FOCUS) Family.ONE_BIG else Family.SAME_SIZE

    /** The arrangement of [family] for [count] tiles. A stacked pair counts as Same size. */
    fun layoutFor(family: Family, count: Int): MultiviewLayout = when {
        count <= 1 -> MultiviewLayout.SINGLE
        family == Family.ONE_BIG -> MultiviewLayout.FOCUS
        else -> defaultFor(count)
    }

    /** [layout] if it can hold [count] tiles, otherwise the default for that count. */
    fun effective(layout: MultiviewLayout, count: Int): MultiviewLayout {
        val n = count.coerceIn(1, MAX_TILES)
        return if (layout.supports(n)) layout else defaultFor(n)
    }

    /** The safe area (5% overscan of a 960 x 540 dp screen) every grid is packed into, and the gap between tiles. */
    const val SAFE_WIDTH_DP = 864f
    const val SAFE_HEIGHT_DP = 486f
    const val GAP_DP = 8f

    /** A tile narrower than this (dp) is a small tile: name only, small badge, short failure title. */
    const val SMALL_TILE_DP = 300f

    fun isSmall(widthDp: Float): Boolean = widthDp < SMALL_TILE_DP

    private const val ASPECT = 16f / 9f

    /**
     * Non-overlapping 16:9 rectangles packed with a [gapDp] gap and centred in a stage of
     * [stageWidthDp] x [stageHeightDp], as fractions 0..1 of that stage, one per tile, in reading order. A single tile
     * fills the stage. Empty for a count of 0 or less.
     */
    fun bounds(
        layout: MultiviewLayout,
        tileCount: Int,
        stageWidthDp: Float = SAFE_WIDTH_DP,
        stageHeightDp: Float = SAFE_HEIGHT_DP,
        gapDp: Float = GAP_DP,
    ): List<TileRect> {
        if (tileCount <= 0) return emptyList()
        val n = tileCount.coerceAtMost(MAX_TILES)
        val w = stageWidthDp.coerceAtLeast(1f)
        val h = stageHeightDp.coerceAtLeast(1f)
        val g = gapDp
        val dp: List<FloatArray> = when (effective(layout, n)) { // x, y, width, height in dp
            MultiviewLayout.SINGLE -> listOf(floatArrayOf(0f, 0f, w, h))
            MultiviewLayout.SIDE_BY_SIDE -> {
                var tw = (w - g) / 2f
                var th = tw / ASPECT
                if (th > h) { th = h; tw = th * ASPECT }
                val x0 = (w - (2 * tw + g)) / 2f
                val y0 = (h - th) / 2f
                listOf(floatArrayOf(x0, y0, tw, th), floatArrayOf(x0 + tw + g, y0, tw, th))
            }
            MultiviewLayout.TWO_STACKED -> {
                var th = (h - g) / 2f
                var tw = th * ASPECT
                if (tw > w) { tw = w; th = tw / ASPECT }
                val x0 = (w - tw) / 2f
                val y0 = (h - (2 * th + g)) / 2f
                listOf(floatArrayOf(x0, y0, tw, th), floatArrayOf(x0, y0 + th + g, tw, th))
            }
            MultiviewLayout.FOCUS -> {
                val k = n - 1
                // The big tile takes the width that leaves the small column exactly the same height as the big one
                // (a lone small tile is half the big one's width); the group spans the stage width.
                val bigW = if (k == 1) (w * 2f / 3f).let(Math::round).toFloat() - g / 2f
                else (k * (w - g) * 9f / 16f + (k - 1) * g) / ((k + 1) * 9f / 16f)
                val bigH = bigW / ASPECT
                val smallW = w - g - bigW
                val smallH = smallW / ASPECT
                val y0 = (h - bigH) / 2f
                listOf(floatArrayOf(0f, y0, bigW, bigH)) + (0 until k).map { i ->
                    floatArrayOf(bigW + g, y0 + i * (smallH + g), smallW, smallH)
                }
            }
            MultiviewLayout.GRID -> {
                val tw = minOf((h - g) / 2f * ASPECT, (w - g) / 2f)
                val th = tw / ASPECT
                val x0 = (w - (2 * tw + g)) / 2f
                val y0 = (h - (2 * th + g)) / 2f
                if (n == 3) listOf(
                    floatArrayOf(x0, y0, tw, th),
                    floatArrayOf(x0 + tw + g, y0, tw, th),
                    floatArrayOf((w - tw) / 2f, y0 + th + g, tw, th),
                ) else listOf(
                    floatArrayOf(x0, y0, tw, th),
                    floatArrayOf(x0 + tw + g, y0, tw, th),
                    floatArrayOf(x0, y0 + th + g, tw, th),
                    floatArrayOf(x0 + tw + g, y0 + th + g, tw, th),
                )
            }
        }
        return dp.mapIndexed { i, r ->
            TileRect(i, r[0] / w, r[1] / h, (r[0] + r[2]) / w, (r[1] + r[3]) / h)
        }
    }
}
