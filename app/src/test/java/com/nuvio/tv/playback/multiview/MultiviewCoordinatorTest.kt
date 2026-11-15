package com.nuvio.tv.playback.multiview

import com.nuvio.tv.core.multiview.MultiviewLayout
import com.nuvio.tv.core.multiview.MultiviewPanePlayback
import com.nuvio.tv.core.multiview.MultiviewPanePlayer
import com.nuvio.tv.core.multiview.MultiviewSessionCore
import com.nuvio.tv.playback.core.AudioFocusEvent
import com.nuvio.tv.playback.core.AudioFocusPort
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiviewCoordinatorTest {
    private class FakeFocusPort(var grant: Boolean = true) : AudioFocusPort {
        var acquires = 0
        var abandons = 0
        val flow = MutableSharedFlow<AudioFocusEvent>(extraBufferCapacity = 8)
        override fun events(): Flow<AudioFocusEvent> = flow.asSharedFlow()
        override suspend fun acquire(): PlaybackResult<Unit> {
            acquires++
            return if (grant) PlaybackResult.Success(Unit) else PlaybackResult.Failure(
                PlaybackFailure(FailureCode.AUDIO_OUTPUT_FAILED, FailureDomain.AUDIO, FailurePhase.ENGINE_START, Retryability.FATAL),
            )
        }
        override suspend fun abandon(): PlaybackResult<Unit> { abandons++; return PlaybackResult.Success(Unit) }
    }

    private class FakeSession : AudibleTileMediaSession {
        val shown = ArrayList<AudibleTileSource?>()
        var released = 0
        override fun show(tile: AudibleTileSource?) { shown += tile }
        override fun release() { released++ }
    }

    private class FakePlayer(val changed: () -> Unit) : MultiviewPanePlayer<TileChannel> {
        override var playback = MultiviewPanePlayback()
        var heard = false
        override fun prepare(channel: TileChannel) {}
        override fun setAudible(audible: Boolean) { heard = audible }
        override fun setPlaying(playing: Boolean) {}
        override fun release() {}
        fun play() { playback = MultiviewPanePlayback(isPlaying = true); changed() }
    }

    private class FakeSource : AudibleTileSource {
        override val metadata: CleanMediaSessionMetadata = CleanMediaSessionMetadata.fromIngress("", "x")
        override val snapshot: StateFlow<PlaybackSnapshot> = MutableStateFlow(PlaybackSnapshot())
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun stop() {}
    }

    @Test
    fun `four tiles make one focus request and abandoning on release gives it back`() = runTest {
        val focus = FakeFocusPort()
        val session = FakeSession()
        val coordinator = MultiviewCoordinator(backgroundScope + UnconfinedTestDispatcher(testScheduler), focus, session)
        val players = ArrayList<FakePlayer>()
        val core = MultiviewSessionCore<TileChannel>(
            initialLayout = MultiviewLayout.GRID,
            initialTileCount = 4,
            totalBufferBytes = 24 * 1024 * 1024,
            audioFocus = coordinator,
            createPlayer = { _, _, changed -> FakePlayer(changed).also { players += it } },
            onChanged = {},
        )
        for (i in 0 until 4) core.assignChannel(i, testChannel("c$i"))
        core.onForeground()
        assertEquals("one request for the whole screen", 1, focus.acquires)
        assertEquals(4, players.size)
        assertEquals(0, focus.abandons)

        players.forEach { it.play() }
        core.focusPane(2)
        assertEquals("moving focus between tiles never re-asks", 1, focus.acquires)

        core.release()
        assertEquals("focus is abandoned once", 1, focus.abandons)
    }

    @Test
    fun `a denied request is reported so the core keeps tiles paused`() = runTest {
        val focus = FakeFocusPort(grant = false)
        val coordinator = MultiviewCoordinator(backgroundScope + UnconfinedTestDispatcher(testScheduler), focus, FakeSession())
        assertFalse(coordinator.request {})
    }

    @Test
    fun `focus gain and loss reach the core and ducking does not`() = runTest {
        val focus = FakeFocusPort()
        val coordinator = MultiviewCoordinator(backgroundScope + UnconfinedTestDispatcher(testScheduler), focus, FakeSession())
        val seen = ArrayList<Boolean>()
        assertTrue(coordinator.request { seen += it })
        focus.flow.emit(AudioFocusEvent.LOSS_TRANSIENT)
        focus.flow.emit(AudioFocusEvent.LOSS_DUCK)
        focus.flow.emit(AudioFocusEvent.GAIN)
        focus.flow.emit(AudioFocusEvent.LOSS_PERMANENT)
        assertEquals(listOf(false, true, false), seen)
    }

    @Test
    fun `after abandon the coordinator no longer listens`() = runTest {
        val focus = FakeFocusPort()
        val coordinator = MultiviewCoordinator(backgroundScope + UnconfinedTestDispatcher(testScheduler), focus, FakeSession())
        val seen = ArrayList<Boolean>()
        coordinator.request { seen += it }
        coordinator.abandon()
        focus.flow.emit(AudioFocusEvent.GAIN)
        assertTrue(seen.isEmpty())
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `the one media session shows the audible tile and nothing when none is`() = runTest {
        val session = FakeSession()
        val coordinator = MultiviewCoordinator(backgroundScope + UnconfinedTestDispatcher(testScheduler), FakeFocusPort(), session)
        val a = FakeSource()
        val b = FakeSource()
        coordinator.showAudible(a)
        coordinator.showAudible(a)
        coordinator.showAudible(b)
        coordinator.showAudible(null)
        assertEquals("a repeat is not a change", 3, session.shown.size)
        assertSame(a, session.shown[0])
        assertSame(b, session.shown[1])
        assertNull(session.shown[2])
    }

    @Test
    fun `release abandons focus and releases the session once`() = runTest {
        val focus = FakeFocusPort()
        val session = FakeSession()
        val coordinator = MultiviewCoordinator(backgroundScope + UnconfinedTestDispatcher(testScheduler), focus, session)
        coordinator.request {}
        coordinator.release()
        coordinator.release()
        assertEquals(1, focus.abandons)
        assertEquals(1, session.released)
        coordinator.showAudible(FakeSource())
        assertTrue("nothing is shown after release", session.shown.isEmpty())
    }
}
