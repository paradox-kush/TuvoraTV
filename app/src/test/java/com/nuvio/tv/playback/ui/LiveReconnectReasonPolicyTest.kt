package com.nuvio.tv.playback.ui

import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.HttpStatusFailures
import com.nuvio.tv.playback.core.LiveReconnectProgress
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.Retryability
import org.junit.Assert.assertEquals
import org.junit.Test

/** JUnit order: assertEquals(message, expected, actual). */
class LiveReconnectReasonPolicyTest {

    private fun status(code: Int) = HttpStatusFailures.forStatus(code, FailurePhase.PLAYBACK)

    private fun failure(code: FailureCode, domain: FailureDomain = FailureDomain.NETWORK) =
        PlaybackFailure(code, domain, FailurePhase.RECOVERY, Retryability.RETRYABLE_WITH_FRESH_REQUEST)

    @Test
    fun `each provider status names its own reason`() {
        assertEquals("456", LiveReconnectReason.PROVIDER_FIREWALL, LiveReconnectReasonPolicy.reason(status(456)))
        listOf(429, 458, 509).forEach {
            assertEquals("$it", LiveReconnectReason.CONNECTION_LIMIT, LiveReconnectReasonPolicy.reason(status(it)))
        }
        assertEquals("404", LiveReconnectReason.STREAM_NOT_FOUND, LiveReconnectReasonPolicy.reason(status(404)))
        assertEquals("410", LiveReconnectReason.STREAM_NOT_FOUND, LiveReconnectReasonPolicy.reason(status(410)))
        listOf(500, 502, 503, 504, 520).forEach {
            assertEquals("$it", LiveReconnectReason.PROVIDER_SERVER_ERROR, LiveReconnectReasonPolicy.reason(status(it)))
        }
        assertEquals("401", LiveReconnectReason.PROVIDER_REFUSED, LiveReconnectReasonPolicy.reason(status(401)))
        assertEquals("403", LiveReconnectReason.PROVIDER_REFUSED, LiveReconnectReasonPolicy.reason(status(403)))
    }

    @Test
    fun `a failure without a status is named by its code`() {
        assertEquals(LiveReconnectReason.PROVIDER_UNREACHABLE, LiveReconnectReasonPolicy.reason(failure(FailureCode.NETWORK_UNREACHABLE)))
        assertEquals(LiveReconnectReason.PROVIDER_UNREACHABLE, LiveReconnectReasonPolicy.reason(failure(FailureCode.NETWORK_TIMEOUT)))
        assertEquals(
            LiveReconnectReason.CONNECTION_LIMIT,
            LiveReconnectReasonPolicy.reason(failure(FailureCode.PROVIDER_CONNECTION_LIMIT, FailureDomain.AUTHORIZATION_PROVIDER_LIMIT)),
        )
        assertEquals(LiveReconnectReason.STREAM_NOT_FOUND, LiveReconnectReasonPolicy.reason(failure(FailureCode.SOURCE_NOT_FOUND)))
    }

    @Test
    fun `an end-file or a stall reads as the stream stopped`() {
        assertEquals("end-file", LiveReconnectReason.STREAM_STOPPED, LiveReconnectReasonPolicy.reason(null))
        assertEquals("stall", LiveReconnectReason.STREAM_STOPPED, LiveReconnectReasonPolicy.reason(failure(FailureCode.NO_PROGRESS)))
        assertEquals("an odd 3xx", LiveReconnectReason.STREAM_STOPPED, LiveReconnectReasonPolicy.reason(status(302)))
        assertEquals("any other 4xx is a refusal", LiveReconnectReason.PROVIDER_REFUSED, LiveReconnectReasonPolicy.reason(status(451)))
    }

    @Test
    fun `the presenter carries attempt budget and reason only while reconnecting`() {
        val reconnecting = PlaybackSnapshot(
            state = PlaybackState.LIVE_RECONNECTING,
            isReconnecting = true,
            liveReconnect = LiveReconnectProgress(attempt = 2, maxAttempts = 3, cause = status(458)),
        )
        assertEquals(
            LiveReconnectStatus(attempt = 2, maxAttempts = 3, reason = LiveReconnectReason.CONNECTION_LIMIT),
            LivePlaybackUiPresenter.present(reconnecting).reconnect,
        )

        val beforeFirstAttempt = PlaybackSnapshot(state = PlaybackState.LIVE_RECONNECTING, isReconnecting = true)
        assertEquals(
            "before the loop's first attempt there is a reason but no number",
            LiveReconnectStatus(attempt = null, maxAttempts = null, reason = LiveReconnectReason.STREAM_STOPPED),
            LivePlaybackUiPresenter.present(beforeFirstAttempt).reconnect,
        )

        assertEquals("playing has no reconnect line", null, LivePlaybackUiPresenter.present(PlaybackSnapshot(state = PlaybackState.PLAYING)).reconnect)
    }
}
