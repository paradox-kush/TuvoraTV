package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvVodRecoveryPolicyTest {

    @Test
    fun `eof-reached ends playback even when the position stops short of the duration`() {
        // keep-open=yes parks the core on the last frame; time-pos often lags the container duration.
        assertTrue(MpvVodRecoveryPolicy.isAtEnd(positionMs = 5_390_000L, durationMs = 5_400_000L, eofReached = true))
        assertFalse(MpvVodRecoveryPolicy.isAtEnd(positionMs = 5_390_000L, durationMs = 5_400_000L, eofReached = false))
    }

    @Test
    fun `position within half a second of the duration is the end`() {
        assertTrue(MpvVodRecoveryPolicy.isAtEnd(positionMs = 5_399_600L, durationMs = 5_400_000L, eofReached = false))
        assertFalse("unknown duration", MpvVodRecoveryPolicy.isAtEnd(positionMs = 10_000L, durationMs = 0L, eofReached = false))
    }

    @Test
    fun `VOD failures retry twice then stop`() {
        assertTrue(MpvVodRecoveryPolicy.shouldAutoRetry(isLiveFeed = false, retriesUsed = 0, httpStatus = null))
        assertTrue(MpvVodRecoveryPolicy.shouldAutoRetry(isLiveFeed = false, retriesUsed = 1, httpStatus = 503))
        assertFalse(MpvVodRecoveryPolicy.shouldAutoRetry(isLiveFeed = false, retriesUsed = 2, httpStatus = null))
    }

    @Test
    fun `permanent HTTP statuses never retry`() {
        listOf(400, 401, 403, 404, 410).forEach { status ->
            assertFalse("$status", MpvVodRecoveryPolicy.shouldAutoRetry(isLiveFeed = false, retriesUsed = 0, httpStatus = status))
        }
    }

    @Test
    fun `live feeds keep their own reconnect ladder`() {
        assertFalse(MpvVodRecoveryPolicy.shouldAutoRetry(isLiveFeed = true, retriesUsed = 0, httpStatus = null))
    }

    @Test
    fun `retries come back only after stable playback past the resume point`() {
        assertFalse(MpvVodRecoveryPolicy.shouldResetRetries(retriesUsed = 1, isPlaying = true, positionMs = 64_000L, resumedFromMs = 60_000L))
        assertTrue(MpvVodRecoveryPolicy.shouldResetRetries(retriesUsed = 1, isPlaying = true, positionMs = 65_000L, resumedFromMs = 60_000L))
        assertFalse("paused", MpvVodRecoveryPolicy.shouldResetRetries(retriesUsed = 1, isPlaying = false, positionMs = 90_000L, resumedFromMs = 60_000L))
        assertFalse("nothing to reset", MpvVodRecoveryPolicy.shouldResetRetries(retriesUsed = 0, isPlaying = true, positionMs = 90_000L, resumedFromMs = 60_000L))
    }
}
