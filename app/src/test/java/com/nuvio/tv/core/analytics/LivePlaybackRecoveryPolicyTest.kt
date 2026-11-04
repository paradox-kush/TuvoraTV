package com.nuvio.tv.core.analytics

import com.nuvio.tv.core.analytics.LivePlaybackRecoveryPolicy.Decision
import com.nuvio.tv.core.analytics.LivePlaybackFreezePolicy.Kind
import com.nuvio.tv.core.analytics.LivePlaybackRecoveryPolicy.Input
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaybackRecoveryPolicyTest {

    @Test
    fun `first reconnect fires immediately`() {
        // The freeze has already been visible for the detection threshold; waiting longer only
        // extends how long the viewer stares at a frozen picture.
        assertEquals(
            Decision.Reconnect,
            LivePlaybackRecoveryPolicy.evaluate(Input(attempts = 0, sinceLastAttemptMs = 0L)),
        )
    }

    @Test
    fun `second reconnect waits for backoff`() {
        assertEquals(
            Decision.Wait,
            LivePlaybackRecoveryPolicy.evaluate(Input(attempts = 1, sinceLastAttemptMs = 500L)),
        )
        assertEquals(
            Decision.Reconnect,
            LivePlaybackRecoveryPolicy.evaluate(Input(attempts = 1, sinceLastAttemptMs = 2_000L)),
        )
    }

    @Test
    fun `backoff grows with each attempt`() {
        assertEquals(
            Decision.Wait,
            LivePlaybackRecoveryPolicy.evaluate(Input(attempts = 3, sinceLastAttemptMs = 9_000L)),
        )
        assertEquals(
            Decision.Reconnect,
            LivePlaybackRecoveryPolicy.evaluate(Input(attempts = 3, sinceLastAttemptMs = 10_000L)),
        )
    }

    @Test
    fun `gives up once attempts are exhausted`() {
        // A channel the provider no longer serves must surface as an error rather than loop
        // forever behind a loading spinner.
        assertEquals(
            Decision.GiveUp,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(attempts = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS, sinceLastAttemptMs = Long.MAX_VALUE),
            ),
        )
    }

    @Test
    fun `attempts beyond the last backoff entry still give up rather than reconnect`() {
        assertEquals(
            Decision.GiveUp,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(attempts = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS + 5, sinceLastAttemptMs = Long.MAX_VALUE),
            ),
        )
    }

    @Test
    fun `linear live mode keeps retrying beyond the finite budget at capped backoff`() {
        assertEquals(
            Decision.Wait,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(
                    attempts = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS + 50,
                    sinceLastAttemptMs = 19_999L,
                    retryIndefinitely = true,
                ),
            ),
        )
        assertEquals(
            Decision.Reconnect,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(
                    attempts = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS + 50,
                    sinceLastAttemptMs = 20_000L,
                    retryIndefinitely = true,
                ),
            ),
        )
    }

    // --- the ladder: a live connection is not spent on a fault that is not the connection ----

    @Test
    fun `a video-only freeze resets the decoder before touching the connection`() {
        // Audio is still arriving, so the link works. Re-resolving it can cost the stream:
        // Stalker create_link tokens are single-use and providers cap concurrent connections.
        repeat(LivePlaybackRecoveryPolicy.VIDEO_RESET_ATTEMPTS) { attempt ->
            assertEquals(
                Decision.ResetVideo,
                LivePlaybackRecoveryPolicy.evaluate(
                    Input(attempts = attempt, sinceLastAttemptMs = Long.MAX_VALUE, kind = Kind.VIDEO_STALLED),
                ),
            )
        }
    }

    @Test
    fun `a video-only freeze escalates to a reconnect once resets stop helping`() {
        assertEquals(
            Decision.Reconnect,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(
                    attempts = LivePlaybackRecoveryPolicy.VIDEO_RESET_ATTEMPTS,
                    sinceLastAttemptMs = Long.MAX_VALUE,
                    kind = Kind.VIDEO_STALLED,
                ),
            ),
        )
    }

    @Test
    fun `a dead pipe reconnects straight away`() {
        // Nothing is arriving, so resetting the decoder cannot help — only a new connection can.
        for (kind in listOf(Kind.STALLED, Kind.ENDED)) {
            assertEquals(
                Decision.Reconnect,
                LivePlaybackRecoveryPolicy.evaluate(
                    Input(attempts = 0, sinceLastAttemptMs = Long.MAX_VALUE, kind = kind),
                ),
            )
        }
    }

    @Test
    fun `an engine with no video-reset primitive reconnects instead of stalling the ladder`() {
        assertEquals(
            Decision.Reconnect,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(
                    attempts = 0,
                    sinceLastAttemptMs = Long.MAX_VALUE,
                    kind = Kind.VIDEO_STALLED,
                    videoResetAttempts = 0,
                ),
            ),
        )
    }

    @Test
    fun `backoff still gates the video reset`() {
        assertEquals(
            Decision.Wait,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(attempts = 1, sinceLastAttemptMs = 500L, kind = Kind.VIDEO_STALLED),
            ),
        )
    }

    @Test
    fun `indefinite ladder gives up after attempts that never rendered a frame`() {
        // 2026-11-04 Onn: the provider closed the stream, five reconnects each ended in ~1s, and
        // the screen sat black with no message. A channel that will not come back is a Failed
        // outcome with Retry, not an endless (or silent) ladder.
        assertEquals(
            Decision.GiveUp,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(
                    attempts = 5,
                    sinceLastAttemptMs = 60_000L,
                    retryIndefinitely = true,
                    attemptsWithoutPlayback = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK,
                ),
            ),
        )
    }

    @Test
    fun `indefinite ladder keeps trying while attempts still render frames in between`() {
        // A flaky channel that plays between drops has used many attempts overall but none in a
        // row without playback: it must never be given up on.
        assertEquals(
            Decision.Reconnect,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(
                    attempts = 12,
                    sinceLastAttemptMs = 60_000L,
                    retryIndefinitely = true,
                    attemptsWithoutPlayback = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK - 1,
                ),
            ),
        )
    }

    @Test
    fun `give up wins over the backoff wait`() {
        assertEquals(
            Decision.GiveUp,
            LivePlaybackRecoveryPolicy.evaluate(
                Input(
                    attempts = 5,
                    sinceLastAttemptMs = 0L,
                    retryIndefinitely = true,
                    attemptsWithoutPlayback = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS_WITHOUT_PLAYBACK,
                ),
            ),
        )
    }

    @Test
    fun `delay before the next attempt follows the backoff table and caps`() {
        assertEquals(0L, LivePlaybackRecoveryPolicy.backoffMs(0))
        assertEquals(2_000L, LivePlaybackRecoveryPolicy.backoffMs(1))
        assertEquals(20_000L, LivePlaybackRecoveryPolicy.backoffMs(4))
        assertEquals(20_000L, LivePlaybackRecoveryPolicy.backoffMs(40))
    }
}
