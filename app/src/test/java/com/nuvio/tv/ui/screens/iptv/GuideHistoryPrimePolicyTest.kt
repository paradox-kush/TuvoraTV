package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideHistoryPrimePolicyTest {
    private fun ch(id: String, streamId: Int) = GuideChannel(contentId = id, name = id, logo = null, streamUrl = "", streamId = streamId, catchUpDays = 3)

    private val lineup = listOf(ch("a", 1), ch("b", 2), ch("c", 3))

    @Test
    fun `the channel focused as the list lands gets its history fetched`() {
        assertEquals(
            "row one is focused by the lineup itself, so the UI focus event is a no-op and only this prime can fetch it",
            "a",
            GuideHistoryPrimePolicy.primeTarget(lineup, "a", catchUpSupported = true)?.contentId,
        )
    }

    @Test
    fun `a preserved focus further down is the one primed`() {
        assertEquals("b", GuideHistoryPrimePolicy.primeTarget(lineup, "b", true)?.contentId)
    }

    @Test
    fun `nothing is fetched when the account has no catch-up`() {
        assertNull("no archive, no history", GuideHistoryPrimePolicy.primeTarget(lineup, "a", false))
    }

    @Test
    fun `nothing is fetched without a focused channel in the lineup`() {
        assertNull("unknown focus", GuideHistoryPrimePolicy.primeTarget(lineup, "zzz", true))
        assertNull("null focus", GuideHistoryPrimePolicy.primeTarget(lineup, null, true))
    }

    @Test
    fun `a channel without a stream id has no history`() {
        assertNull("streamId 0", GuideHistoryPrimePolicy.primeTarget(listOf(ch("a", 0)), "a", true))
    }
}
