package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

// Sync-12 device pass (2026-10-04, Onn): a +60 s audio delay left ExoPlayer on "Starting stream…"
// forever — AudioDelayMediaSource shifts audio timestamps, so ExoPlayer must buffer |delay| ahead,
// and its time/byte-capped buffer never got there. The per-device delay re-applied every session,
// so ExoPlayer stayed broken on that TV. libmpv applies the delay natively and handles ±60 s.
class AudioDelayRangePolicyTest {

    @Test
    fun `exoplayer caps the applied delay to what its buffer can hold`() {
        assertEquals("+60 s on ExoPlayer", 10_000, AudioDelayRangePolicy.effectiveDelayMs(60_000, usingMpv = false))
        assertEquals("-60 s on ExoPlayer", -10_000, AudioDelayRangePolicy.effectiveDelayMs(-60_000, usingMpv = false))
        assertEquals("small delays pass through", 250, AudioDelayRangePolicy.effectiveDelayMs(250, usingMpv = false))
        assertEquals("at the cap", 10_000, AudioDelayRangePolicy.effectiveDelayMs(10_000, usingMpv = false))
    }

    @Test
    fun `libmpv keeps the full range`() {
        assertEquals("+60 s on libmpv", 60_000, AudioDelayRangePolicy.effectiveDelayMs(60_000, usingMpv = true))
        assertEquals("beyond the range clamps", -60_000, AudioDelayRangePolicy.effectiveDelayMs(-90_000, usingMpv = true))
    }

    @Test
    fun `the adjustable range follows the engine`() {
        assertEquals(10_000, AudioDelayRangePolicy.maxAbsDelayMs(usingMpv = false))
        assertEquals(AUDIO_DELAY_MAX_MS, AudioDelayRangePolicy.maxAbsDelayMs(usingMpv = true))
    }
}
