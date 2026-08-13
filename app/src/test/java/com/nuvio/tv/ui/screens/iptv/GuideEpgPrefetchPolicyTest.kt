package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideEpgPrefetchPolicyTest {

    // Ordering and clipping are checked at a small radius so the expected lists stay readable;
    // `default radius covers a screenful` pins the value production actually uses.

    @Test
    fun `nearest first, alternating outwards`() {
        assertEquals(
            listOf(5, 4, 6, 3, 7, 2, 8),
            GuideEpgPrefetchPolicy.indexesAround(center = 5, size = 20, radius = 3),
        )
    }

    @Test
    fun `clipped at the start of the list`() {
        assertEquals(
            listOf(0, 1, 2, 3),
            GuideEpgPrefetchPolicy.indexesAround(center = 0, size = 20, radius = 3),
        )
    }

    @Test
    fun `clipped at the end of the list`() {
        assertEquals(
            listOf(19, 18, 17, 16),
            GuideEpgPrefetchPolicy.indexesAround(center = 19, size = 20, radius = 3),
        )
    }

    @Test
    fun `a short list is not over-read`() {
        assertEquals(
            listOf(0, 1),
            GuideEpgPrefetchPolicy.indexesAround(center = 0, size = 2, radius = 3),
        )
    }

    @Test
    fun `nothing to prefetch for an empty or impossible position`() {
        assertEquals(emptyList<Int>(), GuideEpgPrefetchPolicy.indexesAround(center = 0, size = 0))
        assertEquals(emptyList<Int>(), GuideEpgPrefetchPolicy.indexesAround(center = -1, size = 10))
        assertEquals(emptyList<Int>(), GuideEpgPrefetchPolicy.indexesAround(center = 10, size = 10))
    }

    /**
     * The guide draws a timeline of cells rather than one row per screen, so the window has to cover
     * what is actually visible. Pinned because narrowing it would quietly bring back the empty rows
     * this policy exists to prevent.
     */
    @Test
    fun `default radius covers a screenful`() {
        assertEquals(8, GuideEpgPrefetchPolicy.RADIUS)
        assertEquals(17, GuideEpgPrefetchPolicy.indexesAround(center = 20, size = 100).size)
    }

    /**
     * Reported on Discord: "Epg only shows on the first channel. But when I select the 2nd channel
     * it populates the rest of the epg for the group, this has to be done in every group."
     *
     * Opening a category primed exactly one channel, and the guide pre-marks the first channel as
     * focused — so the initial focus event was swallowed as a no-op change and the window around it
     * never ran. Everything below the first row read "No information" until the viewer moved, in
     * every group, forever.
     *
     * Arriving at a category and landing on its first channel are the same situation, so they get
     * the same window.
     */
    @Test
    fun `opening a category primes the same window as landing on its first channel`() {
        assertEquals(
            GuideEpgPrefetchPolicy.onFocusChanged(center = 0, size = 40),
            GuideEpgPrefetchPolicy.onChannelsLoaded(size = 40),
        )
    }

    @Test
    fun `an empty category primes nothing`() {
        assertEquals(emptyList<Int>(), GuideEpgPrefetchPolicy.onChannelsLoaded(size = 0))
    }
}
