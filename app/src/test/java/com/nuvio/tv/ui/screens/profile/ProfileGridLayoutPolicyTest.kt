package com.nuvio.tv.ui.screens.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression (B61, 2026-09-27): the who's-watching grid was a plain Row with no scroll, so cards past
 * the available width got the leftover space — 8 dp for a 7th profile on a 960 dp TV, 0 dp for an 8th,
 * and a 4th card already hidden on 640 dp high-density boxes. The layout must scroll instead.
 */
class ProfileGridLayoutPolicyTest {
    private fun layout(items: Int, maxWidth: Float) = ProfileGridLayoutPolicy.layout(
        itemCount = items, maxWidth = maxWidth,
        cardWidth = 152f, compactCardWidth = 128f, gap = 28f, compactGap = 12f,
    )

    @Test
    fun `six items fit a 960 dp TV without scrolling`() {
        val l = layout(items = 6, maxWidth = 848f)
        assertTrue("compact cards", l.compact)
        assertFalse("6 compact cards (828 dp) fit in 848 dp", l.scrollable)
    }

    @Test
    fun `seven items on a 960 dp TV scroll instead of squeezing the last card`() {
        val l = layout(items = 7, maxWidth = 848f)
        assertTrue("compact cards", l.compact)
        assertTrue("7 compact cards (968 dp) overflow 848 dp", l.scrollable)
        assertEquals("compact gap", 12f, l.gap, 0f)
    }

    @Test
    fun `four items on a high density 640 dp box scroll`() {
        assertTrue(layout(items = 4, maxWidth = 528f).scrollable)
    }

    @Test
    fun `a few items at full size keep the roomy gap and do not scroll`() {
        val l = layout(items = 3, maxWidth = 848f)
        assertFalse("full size", l.compact)
        assertEquals("roomy gap", 28f, l.gap, 0f)
        assertFalse(l.scrollable)
    }
}
