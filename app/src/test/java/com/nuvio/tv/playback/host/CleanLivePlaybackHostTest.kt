package com.nuvio.tv.playback.host

import android.os.Looper
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.playback.core.ActiveWorkReleaseReason
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackCommand
import com.nuvio.tv.playback.core.PlaybackLifecyclePort
import com.nuvio.tv.playback.core.PlaybackOutputApplication
import com.nuvio.tv.playback.core.PlaybackOutputController
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackOutputRequest
import com.nuvio.tv.playback.core.PlaybackOutputStatus
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import com.nuvio.tv.playback.mediasession.CleanMediaSessionOwner
import com.nuvio.tv.playback.ui.PlaybackSessionController
import com.nuvio.tv.playback.wiring.ProductionPlaybackHost
import com.nuvio.tv.playback.wiring.ProductionPlaybackSessionFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CleanLivePlaybackHostTest {
    @Test
    fun `host owns child scope immutable presentation and engine neutral commands`() = runTest {
        val fixture = fixture()
        val host = fixture.create()
        val safeMetadata = CleanMediaSessionMetadata.fromIngress(
            redactedContentFingerprint = "ab12ab12ab12ab12",
            title = "Live News",
        )

        assertNotSame(fixture.parentJob, fixture.composedHost.parentScope.coroutineContext[Job])
        fixture.snapshot.value = PlaybackSnapshot(
            generation = 1,
            state = PlaybackState.PLAYING,
            profile = SessionProfile.FULLSCREEN,
            playWhenReady = true,
            isPlaying = true,
        )
        fixture.operations.clear()
        val tuneGeneration = host.tune(liveSelection("one"), SessionProfile.GUIDE, safeMetadata)
        val zapGeneration = host.zap(liveSelection("two"), SessionProfile.FULLSCREEN, safeMetadata)
        host.pause()
        host.resume()
        host.retry()
        host.changeProfile(SessionProfile.GUIDE)
        host.stop()

        assertEquals(PlaybackState.PLAYING, host.snapshot.value.state)
        assertEquals(2L, tuneGeneration)
        assertEquals(3L, zapGeneration)
        assertEquals(null, host.presentation.value.bottomStatusCode)
        assertEquals(
            listOf(
                PlaybackCommand.SurfaceAvailable,
                PlaybackCommand.Tune(liveSelection("one"), SessionProfile.GUIDE),
                PlaybackCommand.Zap(liveSelection("two"), SessionProfile.FULLSCREEN),
                PlaybackCommand.Pause,
                PlaybackCommand.Resume,
                PlaybackCommand.Retry,
                PlaybackCommand.SessionProfileChanged(SessionProfile.GUIDE),
                PlaybackCommand.Stop,
            ).map { it::class },
            fixture.commands.map { it::class },
        )
        verify(exactly = 2) { fixture.mediaOwner.updateMetadata(safeMetadata) }
        assertEquals(
            listOf(
                "command:Tune",
                "metadata",
                "command:Zap",
                "metadata",
                "command:Pause",
                "command:Resume",
                "command:Retry",
                "command:SessionProfileChanged",
                "command:Stop",
            ),
            fixture.operations,
        )
        host.release()
        fixture.parentJob.cancel()
    }

    @Test
    fun `accepted command returns only after the session publishes its generation`() = runTest {
        val fixture = fixture(autoAdvanceAcceptedGeneration = false)
        val host = fixture.create()
        val metadata = CleanMediaSessionMetadata.fromIngress(
            redactedContentFingerprint = "ab12ab12ab12ab12",
            title = "Live News",
        )

        val acknowledgement = async {
            host.tune(liveSelection("one"), SessionProfile.FULLSCREEN, metadata)
        }
        runCurrent()

        assertFalse(acknowledgement.isCompleted)
        assertTrue(fixture.commands.last() is PlaybackCommand.Tune)
        verify(exactly = 1) { fixture.mediaOwner.updateMetadata(metadata) }

        fixture.snapshot.value = fixture.snapshot.value.copy(generation = 1)
        assertEquals(1L, acknowledgement.await())
        host.release()
        fixture.parentJob.cancel()
    }

    // Review follow-up: retry() dispatched fire-and-forget, so a zap enqueued right after it
    // captured the pre-retry generation and acceptCommand attributed the RETRY's bump to the
    // zap — downstream generation-fenced calls then silently no-oped. Retry must acknowledge
    // its own bump under the command mutex before the next command can run.
    @Test
    fun `retry acknowledges its own generation so a following zap cannot inherit it`() = runTest {
        val fixture = fixture(autoAdvanceAcceptedGeneration = false)
        val host = fixture.create()
        val metadata = CleanMediaSessionMetadata.fromIngress(
            redactedContentFingerprint = "ab12ab12ab12ab12",
            title = "Live News",
        )
        fixture.snapshot.value =
            fixture.snapshot.value.copy(generation = 1, state = PlaybackState.FAILED)

        val retry = async { host.retry() }
        runCurrent()
        assertTrue(fixture.commands.last() is PlaybackCommand.Retry)

        val zap = async { host.zap(liveSelection("two"), SessionProfile.FULLSCREEN, metadata) }
        runCurrent()
        assertFalse(fixture.commands.any { it is PlaybackCommand.Zap })

        fixture.snapshot.value = fixture.snapshot.value.copy(generation = 2)
        runCurrent()
        retry.await()
        assertTrue(fixture.commands.any { it is PlaybackCommand.Zap })

        fixture.snapshot.value = fixture.snapshot.value.copy(generation = 3)
        assertEquals(3L, zap.await())
        host.release()
        fixture.parentJob.cancel()
    }

    @Test
    fun `media session is the only release authority and concurrent release is idempotent`() = runTest {
        val fixture = fixture()
        coEvery { fixture.mediaOwner.release() } coAnswers {
            fixture.releaseOrder += "provider-barrier"
            fixture.releaseSession()
        }
        val host = fixture.create()

        listOf(async { host.release() }, async { host.release() }).awaitAll()

        coVerify(exactly = 1) { fixture.mediaOwner.release() }
        assertEquals(1, fixture.sessionReleaseCount)
        assertEquals(listOf("provider-barrier"), fixture.releaseOrder)
        assertFalse(fixture.composedHost.parentScope.coroutineContext[Job]!!.isActive)
        assertFalse(fixture.surfaces.startHosting())
        fixture.parentJob.cancel()
    }

    @Test
    fun `controller fallback releases only when media session creation fails`() = runTest {
        val fixture = fixture(mediaSessionCreationFails = true)
        val host = fixture.create()

        host.release()
        host.release()

        assertEquals(1, fixture.sessionReleaseCount)
        coVerify(exactly = 0) { fixture.mediaOwner.release() }
        fixture.parentJob.cancel()
    }

    @Test
    fun `sanitized metadata cannot break live playback when platform update fails`() = runTest {
        val fixture = fixture()
        every { fixture.mediaOwner.updateMetadata(any()) } throws IllegalStateException("closed")
        val host = fixture.create()
        val sanitized = CleanMediaSessionMetadata.fromIngress(
            redactedContentFingerprint = "https://provider.invalid/live?token=secret",
            title = "Authorization: Bearer secret",
        )

        host.tune(liveSelection("safe"), SessionProfile.FULLSCREEN, sanitized)

        assertEquals("clean-playback", sanitized.safeMediaId)
        assertEquals("Tuvora", sanitized.title)
        assertTrue(fixture.commands.any { it is PlaybackCommand.Tune })
        host.release()
        fixture.parentJob.cancel()
    }

    @Test
    fun `non live selections are rejected before command or metadata mutation`() = runTest {
        val fixture = fixture()
        val host = fixture.create()
        val metadata = CleanMediaSessionMetadata.fromIngress("ab12ab12ab12ab12", "Movie")
        val selection = ProviderPlaybackSelection(
            sourceType = ProviderSourceType.XTREAM,
            accountId = ProviderSelectionId("account"),
            itemId = ProviderSelectionId("item"),
            contentKey = ProviderSelectionId("content"),
            contentType = ContentType.VOD,
        )

        val failure = runCatching {
            host.tune(selection, SessionProfile.FULLSCREEN, metadata)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(listOf(PlaybackCommand.SurfaceAvailable::class), fixture.commands.map { it::class })
        verify(exactly = 0) { fixture.mediaOwner.updateMetadata(any()) }
        host.release()
        fixture.parentJob.cancel()
    }

    private fun fixture(
        mediaSessionCreationFails: Boolean = false,
        autoAdvanceAcceptedGeneration: Boolean = true,
    ) = Fixture(mediaSessionCreationFails, autoAdvanceAcceptedGeneration)

    private fun liveSelection(id: String) = ProviderPlaybackSelection(
        sourceType = ProviderSourceType.XTREAM,
        accountId = ProviderSelectionId("account-$id"),
        itemId = ProviderSelectionId("item-$id"),
        contentKey = ProviderSelectionId("content-$id"),
        contentType = ContentType.LIVE,
    )

    private class Fixture(
        private val mediaSessionCreationFails: Boolean,
        private val autoAdvanceAcceptedGeneration: Boolean,
    ) {
        val parentJob = SupervisorJob()
        private val parentScope = CoroutineScope(Dispatchers.Unconfined + parentJob)
        val snapshot = MutableStateFlow(PlaybackSnapshot())
        val commands = mutableListOf<PlaybackCommand>()
        val operations = mutableListOf<String>()
        var sessionReleaseCount = 0
        val releaseOrder = mutableListOf<String>()
        val mediaOwner = mockk<CleanMediaSessionOwner>(relaxed = true)
        private val sessionFactory = mockk<ProductionPlaybackSessionFactory>()
        private val controller = PlaybackSessionController(
            snapshot = snapshot,
            dispatchCommand = {
                commands += it
                operations += "command:${it::class.simpleName}"
                if (
                    autoAdvanceAcceptedGeneration &&
                    (it is PlaybackCommand.Tune || it is PlaybackCommand.Zap)
                ) {
                    snapshot.value = snapshot.value.copy(generation = snapshot.value.generation + 1)
                }
            },
            releaseSession = ::releaseSession,
        )
        val surfaces = CleanLiveSurfaceCoordinator(
            owner = FrameLayout(RuntimeEnvironment.getApplication()),
            callbackScope = parentScope,
            constructibleModes = setOf(SurfaceMode.SURFACE_VIEW),
            secureMedia3SurfaceViewSupported = false,
            mainDispatcher = Dispatchers.Unconfined,
        )
        lateinit var composedHost: ProductionPlaybackHost

        init {
            every { mediaOwner.updateMetadata(any()) } answers {
                operations += "metadata"
            }
            coEvery { sessionFactory.create(any(), any()) } coAnswers {
                composedHost = secondArg<ProductionPlaybackHost>()
                controller
            }
        }

        suspend fun create(): CleanLivePlaybackHost = CleanLivePlaybackHost.create(
            context = RuntimeEnvironment.getApplication(),
            preferenceProfileId = PlaybackProfileId("profile"),
            parentScope = parentScope,
            sessionFactory = sessionFactory,
            surfaces = surfaces,
            outputController = NoopOutputController,
            lifecycle = PlaybackLifecyclePort { emptyFlow() },
            applicationLooper = Looper.getMainLooper(),
            applicationDispatcher = Dispatchers.Unconfined,
            mediaSessionFactory = CleanMediaSessionOwnerFactory { _, _, _, _, _ ->
                if (mediaSessionCreationFails) error("synthetic creation failure")
                mediaOwner
            },
        )

        fun releaseSession() {
            sessionReleaseCount += 1
            snapshot.value = snapshot.value.copy(state = PlaybackState.STOPPED)
        }
    }

    private object NoopOutputController : PlaybackOutputController {
        override suspend fun apply(
            request: PlaybackOutputRequest,
        ): PlaybackResult<PlaybackOutputApplication> = PlaybackResult.Success(
            PlaybackOutputApplication(PlaybackOutputStatus.NOT_REQUESTED),
        )

        override suspend fun reset(
            releasedGeneration: Long?,
            reason: ActiveWorkReleaseReason,
        ): PlaybackResult<Unit> = PlaybackResult.Success(Unit)
    }
}
