package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NextEpisodePickerHandoffPolicyTest {

    // UX25 — a binge-group-only search with no binge group to follow can never pick a stream.

    @Test
    fun `binge-group-only search without a current binge group is skipped`() {
        assertEquals(
            "IPTV / group-less stream: straight to the picker",
            true,
            NextEpisodePickerHandoffPolicy.skipSearch(bingeGroupOnly = true, currentBingeGroup = null),
        )
        assertEquals(
            "blank group counts as none",
            true,
            NextEpisodePickerHandoffPolicy.skipSearch(bingeGroupOnly = true, currentBingeGroup = "  "),
        )
    }

    @Test
    fun `binge-group-only search with a binge group still runs`() {
        assertEquals(
            "a group to follow can match on the next episode",
            false,
            NextEpisodePickerHandoffPolicy.skipSearch(bingeGroupOnly = true, currentBingeGroup = "torrentio|1080p"),
        )
    }

    @Test
    fun `full auto-play search is never skipped for lack of a binge group`() {
        assertEquals(
            "auto-play can pick any stream",
            false,
            NextEpisodePickerHandoffPolicy.skipSearch(bingeGroupOnly = false, currentBingeGroup = null),
        )
    }
}
