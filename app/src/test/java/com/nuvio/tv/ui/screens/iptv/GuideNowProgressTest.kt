package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B5: the progress line on channel list rows. JUnit: (message, expected, actual). */
class GuideNowProgressTest {
    @Test
    fun `an airing programme shows how far it has run`() {
        assertEquals("half way", 0.5f, GuideNowProgress.fraction(1_000L, 3_000L, 2_000L)!!, 0.0001f)
        assertEquals("just started", 0f, GuideNowProgress.fraction(1_000L, 3_000L, 1_000L)!!, 0.0001f)
    }

    @Test
    fun `no line for a programme that is not airing or has no times`() {
        assertNull("ended", GuideNowProgress.fraction(1_000L, 3_000L, 3_000L))
        assertNull("later", GuideNowProgress.fraction(5_000L, 6_000L, 3_000L))
        assertNull("no guide", GuideNowProgress.fraction(null, null, 3_000L))
        assertNull("broken times", GuideNowProgress.fraction(3_000L, 3_000L, 3_000L))
    }
}
