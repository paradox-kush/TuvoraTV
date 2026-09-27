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

    // UX26 — the picker reuses a finished search instead of asking every source again.

    @Test
    fun `finished search with streams hands its results to the picker`() {
        assertEquals(
            "no refetch after a completed no-match search",
            true,
            NextEpisodePickerHandoffPolicy.reuseSearchResults(
                searchComplete = true,
                hasStreams = true,
                selectedStreamFailed = false,
            ),
        )
    }

    @Test
    fun `interrupted search is refetched`() {
        assertEquals(
            "hard timeout left sources unanswered",
            false,
            NextEpisodePickerHandoffPolicy.reuseSearchResults(
                searchComplete = false,
                hasStreams = true,
                selectedStreamFailed = false,
            ),
        )
    }

    @Test
    fun `search without any stream is refetched`() {
        assertEquals(
            "nothing to show — let the picker try again",
            false,
            NextEpisodePickerHandoffPolicy.reuseSearchResults(
                searchComplete = true,
                hasStreams = false,
                selectedStreamFailed = false,
            ),
        )
    }

    @Test
    fun `a chosen stream that failed to resolve forces fresh links`() {
        assertEquals(
            "debrid resolve failed: the collected links may be stale",
            false,
            NextEpisodePickerHandoffPolicy.reuseSearchResults(
                searchComplete = true,
                hasStreams = true,
                selectedStreamFailed = true,
            ),
        )
    }
}
