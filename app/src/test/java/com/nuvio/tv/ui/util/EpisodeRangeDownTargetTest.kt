package com.nuvio.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The episode-range chips' "down" press must land on an episode that is part of the currently
 * selected range — anything else resolves to an unattached FocusRequester and the move is silently
 * dropped, which is why the D-pad appeared stuck after switching range.
 */
class EpisodeRangeDownTargetTest {

    private val range = listOf("s1e51", "s1e52", "s1e53")

    @Test
    fun `last-focused id inside the range is kept`() {
        assertEquals(
            "an in-range last-focused episode stays the down-target",
            "s1e52",
            episodeRangeDownTargetId(range, "s1e52"),
        )
    }

    @Test
    fun `last-focused id from another range falls back to the range's first episode`() {
        // "s1e12" belongs to the 1-50 range; after switching to 51-100 its card is not composed.
        assertEquals(
            "an out-of-range last-focused episode falls back to the first of the range",
            "s1e51",
            episodeRangeDownTargetId(range, "s1e12"),
        )
    }

    @Test
    fun `no last-focused id lands on the range's first episode`() {
        assertEquals(
            "first load with nothing focused lands on the first of the range",
            "s1e51",
            episodeRangeDownTargetId(range, null),
        )
    }

    @Test
    fun `an empty range has no down-target`() {
        assertNull("no episodes means no target", episodeRangeDownTargetId(emptyList(), "s1e51"))
    }
}
