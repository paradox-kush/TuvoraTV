package com.nuvio.tv.playback.multiview

import com.nuvio.tv.core.multiview.TileFailure
import com.nuvio.tv.core.multiview.TileOpenGate
import com.nuvio.tv.core.multiview.TileOutcome
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PlaybackProgressEvidence
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import com.nuvio.tv.playback.live.LiveInitialResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileHostPanePlayerTest {
    private class Rig(
        val test: TestScope,
        val bounds: RecordingBounds = RecordingBounds(),
        selection: () -> LiveInitialResult = { targetResult() },
        val hostGate: CompletableDeferred<Unit>? = null,
        var tileCount: Int = 2,
    ) {
        val host = FakeLiveHost()
        var hostsCreated = 0
        var changes = 0
        var selections = 0
        val gate = TileOpenGate(nowMs = { test.testScheduler.currentTime })
        val player = TileHostPanePlayer(
            scope = test.backgroundScope,
            profileId = TEST_PROFILE,
            channelSelection = LiveChannelSelectionPort { selections++; selection() },
            createHost = { hostGate?.await(); hostsCreated++; host },
            gate = gate,
            bounds = bounds,
            tileCount = { tileCount },
            nowMs = { test.testScheduler.currentTime },
            changed = { changes++ },
        )
    }

    private fun TestScope.rig(
        selection: () -> LiveInitialResult = { targetResult() },
        hostGate: CompletableDeferred<Unit>? = null,
    ) = Rig(this, selection = selection, hostGate = hostGate)

    private fun Rig.open() {
        player.setAudible(false)
        player.setPlaying(false)
        player.prepare(testChannel())
        test.runCurrent()
    }

    @Test
    fun `prepare resolves the channel through the selection port and tunes the guide profile once`() = runTest {
        val r = rig()
        r.open()
        assertEquals(1, r.selections)
        assertEquals(1, r.hostsCreated)
        assertEquals(listOf("tune:${SessionProfile.GUIDE}"), r.host.calls)
        assertTrue("opening shows buffering", r.player.playback.isBuffering)
        assertFalse(r.player.playback.isPlaying)
    }

    @Test
    fun `changed fires on each playback state change and only then`() = runTest {
        val r = rig()
        r.open()
        val base = r.changes
        r.host.state.value = snap(PlaybackState.STARTING_PRIMARY, generation = 2)
        runCurrent()
        assertEquals("still buffering: same mapped state", base, r.changes)

        r.host.state.value = playingSnapshot(generation = 2)
        runCurrent()
        assertEquals(base + 1, r.changes)
        assertTrue(r.player.playback.isPlaying)

        r.host.state.value = playingSnapshot(generation = 2).copy(positionMs = 500)
        runCurrent()
        assertEquals("a position tick is not a state change", base + 1, r.changes)

        r.host.state.value = snap(PlaybackState.LIVE_RECONNECTING, generation = 2, isReconnecting = true)
        runCurrent()
        assertEquals(base + 2, r.changes)
        assertTrue(r.player.playback.isReconnecting)
    }

    @Test
    fun `a mute asked for before the picture is applied once the session can take it`() = runTest {
        val r = rig()
        r.open()
        r.player.setAudible(true)
        runCurrent()
        assertEquals("not while the session is still opening", 0, r.host.count("audio:true"))

        r.host.state.value = playingSnapshot(generation = 2)
        runCurrent()
        assertEquals(1, r.host.count("audio:true"))

        r.player.setAudible(false)
        runCurrent()
        assertEquals("audio track is switched off, not just the volume", 1, r.host.count("audio:false"))
    }

    @Test
    fun `an engine rebuilt by a reconnect gets the audio choice again`() = runTest {
        val r = rig()
        r.open()
        r.host.state.value = playingSnapshot(generation = 2)
        r.player.setAudible(true)
        runCurrent()
        r.host.state.value = playingSnapshot(generation = 3)
        runCurrent()
        assertEquals(2, r.host.count("audio:true"))
    }

    @Test
    fun `pause and resume follow the core and only reach an active session`() = runTest {
        val r = rig()
        r.open()
        r.player.setPlaying(true)
        runCurrent()
        assertEquals("nothing to pause or resume while opening", 0, r.host.count("pause") + r.host.count("resume"))

        r.host.state.value = playingSnapshot(generation = 2, playWhenReady = true)
        runCurrent()
        assertEquals("already playing: no command", 0, r.host.count("resume"))

        r.player.setPlaying(false)
        runCurrent()
        assertEquals(1, r.host.count("pause"))

        r.host.state.value = playingSnapshot(generation = 2, playWhenReady = false)
        runCurrent()
        r.player.setPlaying(true)
        runCurrent()
        assertEquals(1, r.host.count("resume"))
    }

    @Test
    fun `a tile held paused before its first frame is paused the moment it can be`() = runTest {
        val r = rig()
        r.open() // core: setPlaying(false) before focus
        r.host.state.value = snap(PlaybackState.PLAYING, generation = 2, playWhenReady = true,
            progress = PlaybackProgressEvidence(
                receivedBytes = true, discoveredTracks = true, decoderReady = true, rendererReady = true,
            ))
        runCurrent()
        assertEquals(1, r.host.count("pause"))
    }

    @Test
    fun `no progress for twenty seconds is no data with one report and the host stopped`() = runTest {
        val r = rig()
        r.open()
        r.host.state.value = snap(PlaybackState.STARTING_PRIMARY, generation = 2)
        runCurrent()
        val before = r.changes
        advanceTimeBy(19_999)
        runCurrent()
        assertNull(r.player.playback.failure)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(TileFailure.NoData, r.player.playback.failure)
        assertEquals(before + 1, r.changes)
        assertEquals(1, r.bounds.reports.size)
        val report = r.bounds.reports.single()
        assertTrue(report.outcome is TileOutcome.TimedOut)
        assertEquals(20_000L, report.outcome.durationMs)
        assertEquals("xtream", report.sourceType)
        assertEquals(2, report.tileCount)
        assertEquals("a stalled tile gives its provider connection back", 1, r.host.count("stop"))
        assertEquals("never a retry loop: one tune", 1, r.host.calls.count { it.startsWith("tune") })
        assertEquals(0, r.host.count("retry"))
    }

    @Test
    fun `progress keeps a slow but moving open alive past twenty seconds of total time`() = runTest {
        val r = rig()
        r.open()
        r.host.state.value = snap(PlaybackState.RESOLVING, generation = 2)
        runCurrent()
        advanceTimeBy(15_000)
        r.host.state.value = snap(PlaybackState.STARTING_PRIMARY, generation = 2,
            progress = PlaybackProgressEvidence(receivedBytes = true))
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertNull("still within the window since the last progress", r.player.playback.failure)
        r.host.state.value = playingSnapshot(generation = 2)
        runCurrent()
        assertTrue(r.player.playback.isPlaying)
        val loaded = r.bounds.reports.single().outcome
        assertTrue(loaded is TileOutcome.Loaded)
        assertEquals(30_000L, loaded.durationMs)
    }

    @Test
    fun `a provider refusal is typed reported and never retried by the tile`() = runTest {
        val r = rig()
        r.open()
        r.host.state.value = snap(
            PlaybackState.FAILED, generation = 2,
            failure = failure(FailureCode.NETWORK_UNREACHABLE, httpStatus = 403),
        )
        runCurrent()
        assertEquals(TileFailure.ProviderRefused, r.player.playback.failure)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("no automatic retry", 1, r.host.calls.count { it.startsWith("tune") })
        assertEquals(0, r.host.count("retry"))
        assertEquals(1, r.bounds.reports.size)
        assertEquals(TileFailure.ProviderRefused, (r.bounds.reports.single().outcome as TileOutcome.Failed).failure)
    }

    @Test
    fun `a decoder failure that dead-ends in the lone engine is reported as the decoder not as an unsupported format`() = runTest {
        val r = rig()
        r.open()
        r.host.state.value = snap(
            PlaybackState.HANDING_OFF_ONCE, generation = 2,
            failure = failure(FailureCode.VIDEO_DECODER_FAILED, com.nuvio.tv.playback.core.FailureDomain.VIDEO_DECODER),
        )
        runCurrent()
        r.host.state.value = snap(
            PlaybackState.FAILED, generation = 2,
            failure = com.nuvio.tv.playback.core.PlaybackFailure(
                FailureCode.NO_ELIGIBLE_GRAPH, com.nuvio.tv.playback.core.FailureDomain.DEVICE_RESOURCE,
                com.nuvio.tv.playback.core.FailurePhase.GRAPH_SELECTION, com.nuvio.tv.playback.core.Retryability.FATAL,
                graphRejection = com.nuvio.tv.playback.core.GraphRejection(
                    rejectedBy = com.nuvio.tv.playback.core.GraphRejectionReason.HANDOFF_EXCLUDED,
                    removedBy = mapOf(com.nuvio.tv.playback.core.GraphRejectionReason.HANDOFF_EXCLUDED to 1),
                ),
            ),
        )
        runCurrent()
        assertEquals(TileFailure.DecoderUnavailable, r.player.playback.failure)
        assertEquals(TileFailure.DecoderUnavailable, (r.bounds.reports.single().outcome as TileOutcome.Failed).failure)
        assertEquals("no engine hand-off is ever tried for a tile", 1, r.host.calls.count { it.startsWith("tune") })
    }

    @Test
    fun `a failed channel resolution is a generic failure and no host is built`() = runTest {
        val r = rig(selection = { rejectedResult() })
        r.open()
        assertEquals(TileFailure.Other, r.player.playback.failure)
        assertEquals(0, r.hostsCreated)
        assertEquals(1, r.bounds.reports.size)
    }

    @Test
    fun `a host that cannot be created is a generic failure not a decoder blame`() = runTest {
        val r = Rig(this)
        val broken = TileHostPanePlayer(
            scope = backgroundScope, profileId = TEST_PROFILE,
            channelSelection = LiveChannelSelectionPort { targetResult() },
            createHost = { error("no surface") },
            gate = r.gate, bounds = r.bounds, tileCount = { 1 },
            nowMs = { testScheduler.currentTime }, changed = {},
        )
        broken.prepare(testChannel())
        runCurrent()
        assertEquals(TileFailure.Other, broken.playback.failure)
    }

    @Test
    fun `source type is reported for each provider kind and never anything identifying`() = runTest {
        val r = rig(selection = { targetResult(sourceType = ProviderSourceType.STALKER) })
        r.open()
        r.host.state.value = playingSnapshot(generation = 2)
        runCurrent()
        assertEquals("stalker", r.bounds.reports.single().sourceType)
    }

    @Test
    fun `release frees the host and a release during creation still frees what was created`() = runTest {
        val r = rig()
        r.open()
        r.player.release()
        runCurrent()
        assertTrue(r.host.released)

        val hold = CompletableDeferred<Unit>()
        val late = rig(hostGate = hold)
        late.open()
        late.player.release()
        hold.complete(Unit)
        runCurrent()
        // Cancelled while creating: nothing may be left running.
        assertTrue("no tune after release", late.host.calls.none { it.startsWith("tune") })
    }

    @Test
    fun `calls after release are ignored and changed stays silent`() = runTest {
        val r = rig()
        r.open()
        r.player.release()
        runCurrent()
        val changes = r.changes
        r.host.state.value = playingSnapshot(generation = 2)
        r.player.setAudible(true)
        r.player.setPlaying(true)
        runCurrent()
        assertEquals(changes, r.changes)
        assertEquals(0, r.host.count("audio:true"))
    }

    @Test
    fun `the tile that queues behind another does not start its stall window until admitted`() = runTest {
        val r = rig()
        val first = r.gate.awaitTurn()
        r.open()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("the queued tile is admitted after two seconds, not stalled", 1, r.hostsCreated)
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(r.player.playback.failure)
        first.settle()
    }

    @Test
    fun `the first frame frees the gate for the next tile`() = runTest {
        val r = rig()
        r.open()
        val next = async { r.gate.awaitTurn() }
        runCurrent()
        assertFalse(next.isCompleted)
        r.host.state.value = playingSnapshot(generation = 2)
        runCurrent()
        assertTrue(next.isCompleted)
    }
}
