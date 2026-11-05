package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.LoadSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LivePauseStateTest {
    @Test
    fun `failure reasons are exactly the spec's`() {
        assertEquals(
            "reasons in spec order",
            listOf("NO_SPACE", "PROVIDER_REFUSED", "STALLED", "SEQUENCE_RESET", "UNSUPPORTED_STREAM", "TIMED_OUT"),
            LivePauseFailure.values().map { it.name },
        )
    }

    @Test
    fun `preparing carries a BoundedLoad status with the live pause deadline`() {
        val loading = BoundedLoad.begin(LoadSurface.LIVE_PAUSE, nowMs = 1_000L)
        val state: LivePauseState = LivePauseState.Preparing(loading)
        assertEquals("deadline 20 s after start", 21_000L, ((state as LivePauseState.Preparing).status).deadlineAtMs)
        assertEquals(
            "the UI ends the wait at the deadline",
            LoadStatus.Failed(timedOut = true),
            BoundedLoad.effectiveAt(state.status, 21_000L),
        )
    }

    @Test
    fun `off is a singleton and shifted compares by value`() {
        assertSame("off", LivePauseState.Off, LivePauseState.Off)
        val a = LivePauseState.Shifted(true, 5_000, 0, 60_000, 55_000, false)
        assertEquals("value equality", a, LivePauseState.Shifted(true, 5_000, 0, 60_000, 55_000, false))
    }
}
