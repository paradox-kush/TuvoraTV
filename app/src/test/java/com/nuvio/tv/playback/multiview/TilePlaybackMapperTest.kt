package com.nuvio.tv.playback.multiview

import com.nuvio.tv.core.multiview.TileFailure
import com.nuvio.tv.core.multiview.TilePhase
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.PlaybackProgressEvidence
import com.nuvio.tv.playback.core.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePlaybackMapperTest {
    private val stall = 20_000L

    private fun failed(f: com.nuvio.tv.playback.core.PlaybackFailure?) =
        TilePlaybackMapper.map(snap(PlaybackState.FAILED, failure = f), stalled = false, stallMs = stall)

    @Test
    fun `a playing tile is playing and not buffering`() {
        val p = TilePlaybackMapper.map(playingSnapshot(), false, stall)
        assertTrue(p.isPlaying)
        assertFalse(p.isBuffering)
        assertFalse(p.isReconnecting)
        assertEquals(null, p.failure)
    }

    @Test
    fun `opening states are buffering`() {
        listOf(
            PlaybackState.IDLE, PlaybackState.RESOLVING, PlaybackState.SELECTING_GRAPH,
            PlaybackState.ATTACHING_SURFACE, PlaybackState.STARTING_PRIMARY,
        ).forEach { state ->
            val p = TilePlaybackMapper.map(snap(state), false, stall)
            assertTrue("$state buffering", p.isBuffering)
            assertFalse("$state playing", p.isPlaying)
        }
    }

    @Test
    fun `a dropped stream is reconnecting and keeps its picture state honest`() {
        val p = TilePlaybackMapper.map(snap(PlaybackState.LIVE_RECONNECTING, isReconnecting = true), false, stall)
        assertTrue(p.isReconnecting)
        assertFalse(p.isPlaying)
        val still = TilePlaybackMapper.map(
            playingSnapshot().copy(isReconnecting = true), false, stall,
        )
        assertTrue("reconnect flag on a playing snapshot", still.isReconnecting)
    }

    @Test
    fun `a tile paused on its first frame is neither playing nor buffering`() {
        val p = TilePlaybackMapper.map(playingSnapshot(playWhenReady = false), false, stall)
        assertFalse(p.isPlaying)
        assertFalse(p.isBuffering)
        assertEquals(null, p.failure)
    }

    @Test
    fun `an http refusal is a provider refusal`() {
        listOf(401, 403, 429, 456, 458, 509).forEach {
            assertEquals("HTTP $it", TileFailure.ProviderRefused,
                failed(failure(FailureCode.NETWORK_UNREACHABLE, httpStatus = it)).failure)
        }
        assertEquals(TileFailure.ProviderRefused, failed(failure(FailureCode.AUTHORIZATION_REJECTED)).failure)
        assertEquals(TileFailure.ProviderRefused, failed(failure(FailureCode.PROVIDER_CONNECTION_LIMIT)).failure)
    }

    @Test
    fun `other http statuses and network codes are network failures`() {
        assertEquals(TileFailure.Network, failed(failure(FailureCode.NETWORK_UNREACHABLE, httpStatus = 502)).failure)
        assertEquals(TileFailure.Network, failed(failure(FailureCode.SOURCE_NOT_FOUND)).failure)
        assertEquals(TileFailure.Network, failed(failure(FailureCode.NETWORK_UNREACHABLE)).failure)
        assertEquals(TileFailure.Network, failed(failure(FailureCode.NETWORK_TIMEOUT)).failure)
        assertEquals(TileFailure.Network, failed(failure(FailureCode.TLS_HANDSHAKE_FAILED, FailureDomain.TLS)).failure)
    }

    @Test
    fun `decoder failures are decoder unavailable`() {
        listOf(
            FailureCode.VIDEO_DECODER_UNAVAILABLE, FailureCode.VIDEO_DECODER_FAILED,
            FailureCode.VIDEO_RENDERER_FAILED, FailureCode.RESOURCE_BUDGET_EXCEEDED,
        ).forEach { code ->
            assertEquals("$code", TileFailure.DecoderUnavailable, failed(failure(code, FailureDomain.VIDEO_DECODER)).failure)
        }
    }

    @Test
    fun `no data and exhausted reconnects are no data`() {
        assertEquals(TileFailure.NoData, failed(failure(FailureCode.NO_PROGRESS)).failure)
        assertEquals(TileFailure.NoData, failed(failure(FailureCode.LIVE_RECONNECT_EXHAUSTED)).failure)
    }

    @Test
    fun `an unplayable format is unsupported`() {
        assertEquals(TileFailure.Unsupported, failed(failure(FailureCode.NO_ELIGIBLE_GRAPH, FailureDomain.DEVICE_RESOURCE)).failure)
        assertEquals(TileFailure.Unsupported, failed(failure(FailureCode.DRM_UNSUPPORTED, FailureDomain.DRM)).failure)
    }

    @Test
    fun `an unknown cause is the generic failure never a decoder blame`() {
        assertEquals(TileFailure.Other, failed(failure(FailureCode.UNKNOWN, FailureDomain.UNKNOWN)).failure)
        assertEquals(TileFailure.Other, failed(failure(FailureCode.DEMUX_FAILED, FailureDomain.DEMUX)).failure)
        assertEquals(TileFailure.Other, failed(null).failure)
    }

    private fun handoffDeadEnd() = com.nuvio.tv.playback.core.PlaybackFailure(
        code = FailureCode.NO_ELIGIBLE_GRAPH,
        domain = FailureDomain.DEVICE_RESOURCE,
        phase = com.nuvio.tv.playback.core.FailurePhase.GRAPH_SELECTION,
        retryability = com.nuvio.tv.playback.core.Retryability.FATAL,
        graphRejection = com.nuvio.tv.playback.core.GraphRejection(
            rejectedBy = com.nuvio.tv.playback.core.GraphRejectionReason.HANDOFF_EXCLUDED,
            removedBy = mapOf(com.nuvio.tv.playback.core.GraphRejectionReason.HANDOFF_EXCLUDED to 2),
        ),
    )

    @Test
    fun `a hand-off dead end is read as the failure that caused it not as an unsupported format`() {
        val cause = failure(FailureCode.VIDEO_DECODER_FAILED, FailureDomain.VIDEO_DECODER)
        val dead = snap(PlaybackState.FAILED, failure = handoffDeadEnd())
        assertEquals(TileFailure.DecoderUnavailable, TilePlaybackMapper.map(dead, false, stall, cause).failure)
        assertEquals(
            "with no earlier cause the dead end is all there is",
            TileFailure.Unsupported, TilePlaybackMapper.map(dead, false, stall, null).failure,
        )
        val real = snap(PlaybackState.FAILED, failure = failure(FailureCode.NETWORK_UNREACHABLE, httpStatus = 403))
        assertEquals(
            "a failure that already says something is never replaced",
            TileFailure.ProviderRefused, TilePlaybackMapper.map(real, false, stall, cause).failure,
        )
    }

    @Test
    fun `a stalled open reads as no data while the session is still waiting`() {
        val p = TilePlaybackMapper.map(snap(PlaybackState.STARTING_PRIMARY), stalled = true, stallMs = stall)
        assertEquals(TileFailure.NoData, p.failure)
    }

    @Test
    fun `phase and stage rise with the open and fall back on a reconnect`() {
        val resolving = snap(PlaybackState.RESOLVING)
        val starting = snap(PlaybackState.STARTING_PRIMARY, progress = PlaybackProgressEvidence(receivedBytes = true))
        val reconnecting = snap(PlaybackState.LIVE_RECONNECTING)
        assertEquals(TilePhase.WAITING, TilePlaybackMapper.phaseOf(resolving))
        assertEquals(TilePhase.PLAYING, TilePlaybackMapper.phaseOf(playingSnapshot()))
        assertEquals(TilePhase.FAILED, TilePlaybackMapper.phaseOf(snap(PlaybackState.FAILED)))
        assertEquals(TilePhase.IDLE, TilePlaybackMapper.phaseOf(snap(PlaybackState.STOPPED)))
        assertTrue(TilePlaybackMapper.stageOf(starting) > TilePlaybackMapper.stageOf(resolving))
        assertTrue(TilePlaybackMapper.stageOf(resolving) > TilePlaybackMapper.stageOf(reconnecting))
    }
}
