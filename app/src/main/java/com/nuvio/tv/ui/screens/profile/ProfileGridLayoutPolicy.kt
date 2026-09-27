package com.nuvio.tv.ui.screens.profile

/**
 * How the who's-watching grid lays out [itemCount] cards (profiles + the Add card) in [maxWidth] dp.
 *
 * Cards shrink to compact when full-size cards don't fit even with the tight gap. If the chosen size
 * still overflows, the row must scroll: a plain Row hands later children only the leftover width, so a
 * 7th profile on a 960 dp TV got 8 dp and an 8th got 0 dp (B61).
 */
internal object ProfileGridLayoutPolicy {
    data class Layout(val compact: Boolean, val gap: Float, val scrollable: Boolean)

    fun layout(
        itemCount: Int,
        maxWidth: Float,
        cardWidth: Float,
        compactCardWidth: Float,
        gap: Float,
        compactGap: Float,
    ): Layout {
        val defaultWidth = rowWidth(itemCount, cardWidth, gap)
        val fullSizeTightWidth = rowWidth(itemCount, cardWidth, compactGap)
        val compact = defaultWidth > maxWidth && fullSizeTightWidth > maxWidth
        val chosenGap = if (defaultWidth > maxWidth) compactGap else gap
        val chosenWidth = rowWidth(itemCount, if (compact) compactCardWidth else cardWidth, chosenGap)
        return Layout(compact = compact, gap = chosenGap, scrollable = chosenWidth > maxWidth)
    }

    fun rowWidth(itemCount: Int, cardWidth: Float, gap: Float): Float =
        if (itemCount <= 0) 0f else cardWidth * itemCount + gap * (itemCount - 1)
}
