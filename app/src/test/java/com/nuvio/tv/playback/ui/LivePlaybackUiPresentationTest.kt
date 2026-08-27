package com.nuvio.tv.playback.ui

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.PreviewAvailability
import com.nuvio.tv.playback.core.PreviewUnavailableReason
import com.nuvio.tv.playback.core.RedirectPolicy
import com.nuvio.tv.playback.core.RequestSummary
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.StreamAvailability
import com.nuvio.tv.playback.core.StreamUnavailableReason
import com.nuvio.tv.playback.core.TerminalAvailabilityEvidence
import com.nuvio.tv.playback.core.TlsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackUiPresentationTest {
    @Test
    fun `spinner mapping is exhaustive across every playback state`() {
        val spinnerStates = setOf(
            PlaybackState.RESOLVING,
            PlaybackState.SELECTING_GRAPH,
            PlaybackState.ATTACHING_SURFACE,
            PlaybackState.STARTING_PRIMARY,
            PlaybackState.RECOVERING_IN_PLACE,
            PlaybackState.HANDING_OFF_ONCE,
            PlaybackState.LIVE_RECONNECTING,
        )
        PlaybackState.entries.forEach { state ->
            val ui = LivePlaybackUiPresenter.present(
                PlaybackSnapshot(state = state, playWhenReady = state in spinnerStates),
            )
            assertEquals("spinner for $state", state in spinnerStates, ui.spinnerVisible)
        }

        assertTrue(
            LivePlaybackUiPresenter.present(
                PlaybackSnapshot(
                    state = PlaybackState.DEGRADED,
                    playWhenReady = true,
                    isBuffering = true,
                ),
            ).spinnerVisible,
        )
    }

    @Test
    fun `bottom status mapping is exhaustive across every playback state`() {
        val expected = mapOf(
            PlaybackState.IDLE to null,
            PlaybackState.RESOLVING to LivePlaybackUiStatusCode.RESOLVING,
            PlaybackState.SELECTING_GRAPH to LivePlaybackUiStatusCode.STARTING,
            PlaybackState.ATTACHING_SURFACE to LivePlaybackUiStatusCode.STARTING,
            PlaybackState.STARTING_PRIMARY to LivePlaybackUiStatusCode.STARTING,
            PlaybackState.PLAYING to null,
            PlaybackState.DEGRADED to null,
            PlaybackState.RECOVERING_IN_PLACE to LivePlaybackUiStatusCode.RECOVERING,
            PlaybackState.HANDING_OFF_ONCE to LivePlaybackUiStatusCode.HANDING_OFF,
            PlaybackState.LIVE_RECONNECTING to LivePlaybackUiStatusCode.RECONNECTING,
            PlaybackState.RELEASING to LivePlaybackUiStatusCode.RELEASING,
            PlaybackState.STOPPED to LivePlaybackUiStatusCode.STOPPED,
            PlaybackState.FAILED to null,
        )

        assertEquals(PlaybackState.entries.toSet(), expected.keys)
        expected.forEach { (state, status) ->
            val ui = LivePlaybackUiPresenter.present(
                PlaybackSnapshot(state = state, playWhenReady = true),
            )
            assertEquals("status for $state", status, ui.bottomStatusCode)
        }
    }

    @Test
    fun `live EOF presentation stays indefinitely reconnecting and never becomes a terminal error`() {
        val ui = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.LIVE_RECONNECTING,
                requestSummary = liveSummary(),
                playWhenReady = true,
                isReconnecting = true,
                failure = failure(FailureCode.NETWORK_TIMEOUT, Retryability.RETRYABLE_WITH_FRESH_REQUEST),
                streamAvailability = StreamAvailability.Available,
            ),
        )

        assertTrue(ui.spinnerVisible)
        assertEquals(LivePlaybackUiStatusCode.RECONNECTING, ui.bottomStatusCode)
        assertNull(ui.bottomErrorCode)
        assertTrue(ui.controlsEnabled)
        assertTrue(ui.playWhenReady)
        assertFalse(ui.isPaused)
        assertSame(StreamAvailability.Available, ui.streamAvailability)
    }

    @Test
    fun `fatal source unavailability wins over playback details and disables controls`() {
        val unavailable = StreamAvailability.TerminallyUnavailable(
            StreamUnavailableReason.AUTHORIZATION,
            TerminalAvailabilityEvidence.SOURCE_CONFIRMED,
        )
        val ui = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.FAILED,
                profile = SessionProfile.GUIDE,
                requestSummary = liveSummary(),
                failure = failure(FailureCode.AUTHORIZATION_REJECTED, Retryability.FATAL),
                streamAvailability = unavailable,
            ),
        )

        assertFalse(ui.spinnerVisible)
        assertNull(ui.bottomStatusCode)
        assertEquals(
            LivePlaybackUiErrorCode.StreamUnavailable(StreamUnavailableReason.AUTHORIZATION),
            ui.bottomErrorCode,
        )
        assertFalse(ui.controlsEnabled)
        assertFalse(ui.openFullscreenEnabled)
        assertSame(unavailable, ui.streamAvailability)
    }

    @Test
    fun `guide preview-only failure keeps fullscreen action available`() {
        val preview = PreviewAvailability.Unavailable(
            PreviewUnavailableReason.GUIDE_RENDER_PATH_UNAVAILABLE,
        )
        val ui = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.FAILED,
                profile = SessionProfile.GUIDE,
                requestSummary = liveSummary(),
                previewAvailability = preview,
                streamAvailability = StreamAvailability.Unknown,
                failure = failure(FailureCode.VIDEO_RENDERER_FAILED, Retryability.FATAL),
            ),
        )

        assertEquals(
            LivePlaybackUiErrorCode.PreviewUnavailable(
                PreviewUnavailableReason.GUIDE_RENDER_PATH_UNAVAILABLE,
            ),
            ui.bottomErrorCode,
        )
        assertSame(preview, ui.previewAvailability)
        assertSame(StreamAvailability.Unknown, ui.streamAvailability)
        assertFalse(ui.controlsEnabled)
        assertTrue(ui.openFullscreenEnabled)
    }

    @Test
    fun `fullscreen ignores stale guide preview failure and reports playback failure`() {
        val ui = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.FAILED,
                profile = SessionProfile.FULLSCREEN,
                requestSummary = liveSummary(),
                previewAvailability = PreviewAvailability.Unavailable(
                    PreviewUnavailableReason.GUIDE_RENDER_PATH_UNAVAILABLE,
                ),
                failure = failure(FailureCode.VIDEO_DECODER_FAILED, Retryability.FATAL),
            ),
        )

        assertEquals(
            LivePlaybackUiErrorCode.PlaybackFailed(FailureCode.VIDEO_DECODER_FAILED),
            ui.bottomErrorCode,
        )
        assertFalse(ui.openFullscreenEnabled)
    }

    @Test
    fun `playing and paused states use authoritative play intent`() {
        val playing = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.PLAYING,
                playWhenReady = true,
                isPlaying = true,
            ),
        )
        val paused = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.DEGRADED,
                playWhenReady = false,
                isPlaying = false,
                isBuffering = false,
            ),
        )

        assertTrue(playing.controlsEnabled)
        assertTrue(playing.isPlaying)
        assertFalse(playing.isPaused)
        assertNull(playing.bottomStatusCode)
        assertTrue(paused.controlsEnabled)
        assertFalse(paused.isPlaying)
        assertTrue(paused.isPaused)
        assertFalse(paused.spinnerVisible)
        assertEquals(LivePlaybackUiStatusCode.PAUSED, paused.bottomStatusCode)
    }

    @Test
    fun `release and stop never show a spinner or leave controls enabled`() {
        val releasing = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.RELEASING,
                profile = SessionProfile.GUIDE,
                requestSummary = liveSummary(),
            ),
        )
        val stopped = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.STOPPED,
                profile = SessionProfile.GUIDE,
                requestSummary = liveSummary(),
            ),
        )

        assertFalse(releasing.spinnerVisible)
        assertFalse(releasing.controlsEnabled)
        assertFalse(releasing.openFullscreenEnabled)
        assertEquals(LivePlaybackUiStatusCode.RELEASING, releasing.bottomStatusCode)
        assertFalse(stopped.spinnerVisible)
        assertFalse(stopped.controlsEnabled)
        assertFalse(stopped.openFullscreenEnabled)
        assertEquals(LivePlaybackUiStatusCode.STOPPED, stopped.bottomStatusCode)
    }

    @Test
    fun `terminal playback failure uses only normalized failure code`() {
        val ui = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.FAILED,
                failure = failure(FailureCode.NO_ELIGIBLE_GRAPH, Retryability.FATAL),
            ),
        )

        assertEquals(
            LivePlaybackUiErrorCode.PlaybackFailed(FailureCode.NO_ELIGIBLE_GRAPH),
            ui.bottomErrorCode,
        )
        assertNull(ui.bottomStatusCode)
    }

    @Test
    fun `handoff hides the triggering error until graph exhaustion is terminal`() {
        val ui = LivePlaybackUiPresenter.present(
            PlaybackSnapshot(
                state = PlaybackState.HANDING_OFF_ONCE,
                playWhenReady = true,
                failure = failure(FailureCode.VIDEO_DECODER_FAILED, Retryability.HANDOFF_ELIGIBLE),
            ),
        )

        assertTrue(ui.spinnerVisible)
        assertEquals(LivePlaybackUiStatusCode.HANDING_OFF, ui.bottomStatusCode)
        assertNull(ui.bottomErrorCode)
        assertTrue(ui.controlsEnabled)
    }

    @Test
    fun `every preview and stream reason remains a stable structured code`() {
        PreviewUnavailableReason.entries.forEach { reason ->
            val ui = LivePlaybackUiPresenter.present(
                PlaybackSnapshot(
                    state = PlaybackState.FAILED,
                    profile = SessionProfile.GUIDE,
                    requestSummary = liveSummary(),
                    previewAvailability = PreviewAvailability.Unavailable(reason),
                ),
            )
            assertEquals(LivePlaybackUiErrorCode.PreviewUnavailable(reason), ui.bottomErrorCode)
        }
        StreamUnavailableReason.entries.forEach { reason ->
            val ui = LivePlaybackUiPresenter.present(
                PlaybackSnapshot(
                    state = PlaybackState.FAILED,
                    streamAvailability = StreamAvailability.TerminallyUnavailable(
                        reason,
                        TerminalAvailabilityEvidence.SOURCE_CONFIRMED,
                    ),
                ),
            )
            assertEquals(LivePlaybackUiErrorCode.StreamUnavailable(reason), ui.bottomErrorCode)
        }
    }

    @Test
    fun `presentation contract exposes no free form text channel`() {
        val contractTypes = listOf(
            LivePlaybackUiState::class.java,
            LivePlaybackUiErrorCode.PreviewUnavailable::class.java,
            LivePlaybackUiErrorCode.StreamUnavailable::class.java,
            LivePlaybackUiErrorCode.PlaybackFailed::class.java,
        )
        val stringFields = contractTypes.flatMap { type ->
            type.declaredFields.filter { field -> field.type == String::class.java }
        }

        assertTrue("secret-capable String fields: $stringFields", stringFields.isEmpty())
    }

    private fun failure(code: FailureCode, retryability: Retryability) = PlaybackFailure(
        code = code,
        domain = FailureDomain.UNKNOWN,
        phase = FailurePhase.PLAYBACK,
        retryability = retryability,
    )

    private fun liveSummary() = RequestSummary(
        scheme = "https",
        contentType = ContentType.LIVE,
        hasAuthorization = false,
        hasCustomHeaders = false,
        hasCookies = false,
        hasUserAgent = false,
        hasReferer = false,
        hasOrigin = false,
        hasDrm = false,
        redirectPolicy = RedirectPolicy.FOLLOW,
        crossHostAuthorization = CrossHostAuthorization.STRIP,
        tlsPolicy = TlsPolicy.STRICT,
        dnsPolicy = DnsPolicy.SYSTEM,
        providerConnectionConstrained = true,
    )
}
