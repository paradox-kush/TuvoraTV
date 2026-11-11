package com.nuvio.tv.playback.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTuneDeadlinePolicyTest {
    @Test
    fun `deadline is longer than the whole playback start budget so watchdogs report first`() {
        val budget = LiveTuneDeadlinePolicy.worstCaseStartBudgetMs()
        assertTrue(
            "deadline ${LiveTuneDeadlinePolicy.DEADLINE_MS} must exceed start budget $budget",
            LiveTuneDeadlinePolicy.DEADLINE_MS > budget,
        )
    }

    @Test
    fun `deadline is bounded so a loading state cannot last more than two minutes`() {
        assertTrue("deadline must be finite and sane", LiveTuneDeadlinePolicy.DEADLINE_MS in 1..120_000)
    }

    @Test
    fun `expiry releases the host and fails rather than staying loading`() {
        assertEquals(
            "expiry outcome",
            LiveTuneDeadlinePolicy.Expiry.RELEASE_HOST_AND_FAIL,
            LiveTuneDeadlinePolicy.expiry,
        )
    }
}
