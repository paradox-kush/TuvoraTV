package com.nuvio.tv.core.analytics

import com.nuvio.tv.core.analytics.LivePlaybackRecoveryPolicy.Decision
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
}
