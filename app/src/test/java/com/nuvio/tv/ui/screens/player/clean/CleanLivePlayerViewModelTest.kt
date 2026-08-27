package com.nuvio.tv.ui.screens.player.clean

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import com.nuvio.tv.ui.navigation.CleanLiveLaunchConsumeFailure
import com.nuvio.tv.ui.navigation.CleanLiveLaunchConsumeResult
import com.nuvio.tv.ui.navigation.CleanLiveLaunchEntry
import com.nuvio.tv.ui.navigation.CleanLiveLaunchMetadata
import com.nuvio.tv.ui.navigation.CleanLiveLaunchOrigin
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CleanLivePlayerViewModelTest {
    @Test
    fun `destination attachment is owned by the independent ViewModel scope`() = runTest {
        val fixture = fixture()

        fixture.viewModel.attachDestination(
            "route",
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )
        advanceUntilIdle()

        assertEquals(listOf("create", "tune"), fixture.operations)
        assertTrue(fixture.factory.ownerJob()!!.isActive)
        assertTrue(fixture.viewModel.routeState.value is CleanLivePlayerRouteState.Ready)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `initialize consumes creates and tunes exactly once in fullscreen`() = runTest {
        val fixture = fixture(profileId = 7)

        fixture.viewModel.initialize("route-one", fixture.activity, fixture.lifecycle, fixture.owner)
        fixture.viewModel.initialize("route-two", fixture.activity, fixture.lifecycle, fixture.owner)
        advanceUntilIdle()

        assertEquals(1, fixture.consumer.calls)
        assertEquals(listOf("create", "tune"), fixture.operations)
        assertEquals("7", fixture.factory.input?.preferenceProfileId)
        assertSame(fixture.owner, fixture.factory.input?.surfaceOwner)
        assertEquals(SessionProfile.FULLSCREEN, fixture.host.tunedProfile)
        assertSame(fixture.entry.selection, fixture.host.tunedSelection)
        assertEquals("Live News", fixture.host.tunedMetadata?.title)
        assertEquals("clean-${"ab".repeat(16)}", fixture.host.tunedMetadata?.safeMediaId)
        assertTrue(fixture.viewModel.routeState.value is CleanLivePlayerRouteState.Ready)

        fixture.viewModel.pause()
        fixture.viewModel.resume()
        fixture.viewModel.retry()
        assertEquals(listOf("create", "tune", "pause", "resume", "retry"), fixture.operations)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `non-empty surface owner is rejected before token consumption and remains retryable`() = runTest {
        val fixture = fixture()
        fixture.owner.addView(android.view.View(fixture.owner.context))

        val failure = runCatching {
            fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, fixture.consumer.calls)
        assertEquals(0, fixture.factory.calls)

        fixture.owner.removeAllViews()
        fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)
        assertEquals(1, fixture.consumer.calls)
        assertEquals(1, fixture.factory.calls)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `ready state follows host facts and keeps only sanitized launch metadata`() = runTest {
        val fixture = fixture()
        fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)
        val updated = PlaybackSnapshot(
            generation = 8,
            state = PlaybackState.PLAYING,
            profile = SessionProfile.FULLSCREEN,
            playWhenReady = true,
            isPlaying = true,
        )
        fixture.host.snapshotFlow.value = updated
        advanceUntilIdle()

        val ready = fixture.viewModel.routeState.value as CleanLivePlayerRouteState.Ready
        assertEquals(8, ready.snapshot.generation)
        assertEquals(PlaybackState.PLAYING, ready.snapshot.state)
        assertEquals("Live News", ready.metadata.title)
        assertEquals(CleanLiveLaunchOrigin.SEARCH, ready.origin)
        assertTrue(ready.presentation.isPlaying)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `different surface owner releases before recreating without consuming token again`() = runTest {
        val fixture = fixture()
        fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)
        val replacementOwner = FrameLayout(fixture.owner.context)

        fixture.viewModel.initialize(
            "route-is-already-consumed",
            fixture.activity,
            fixture.lifecycle,
            replacementOwner,
        )

        assertEquals(1, fixture.consumer.calls)
        assertEquals(2, fixture.factory.calls)
        assertEquals(
            listOf("create", "tune", "release", "create", "tune"),
            fixture.operations,
        )
        assertEquals(1, fixture.host.releaseCalls)
        assertSame(replacementOwner, fixture.factory.input?.surfaceOwner)
        assertSame(fixture.entry.selection, fixture.recreatedHost.tunedSelection)
        assertTrue(fixture.viewModel.routeState.value is CleanLivePlayerRouteState.Ready)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `profile race during surface recreation releases both hosts and rejects`() = runTest {
        var profileId = 1
        val fixture = fixture(
            profileSource = CleanLiveDestinationProfileSource { profileId },
            factoryCreated = { call -> if (call == 2) profileId = 2 },
        )
        fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)

        fixture.viewModel.initialize(
            "route",
            fixture.activity,
            fixture.lifecycle,
            FrameLayout(fixture.owner.context),
        )

        assertEquals(
            listOf("create", "tune", "release", "create", "release"),
            fixture.operations,
        )
        assertNull(fixture.recreatedHost.tunedSelection)
        assertEquals(1, fixture.host.releaseCalls)
        assertEquals(1, fixture.recreatedHost.releaseCalls)
        assertEquals(
            CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.PROFILE_MISMATCH),
            fixture.viewModel.routeState.value,
        )
    }

    @Test
    fun `missing expired and initial profile mismatch never create a host`() = runTest {
        CleanLiveLaunchConsumeFailure.entries.forEach { failure ->
            val fixture = fixture(
                consumeResult = CleanLiveLaunchConsumeResult.Rejected(failure),
            )

            fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)

            assertEquals(
                CleanLivePlayerRouteState.Rejected(failure.routeReason()),
                fixture.viewModel.routeState.value,
            )
            assertEquals(1, fixture.consumer.calls)
            assertEquals(0, fixture.factory.calls)
            fixture.viewModel.releaseBeforeExit()
        }
    }

    @Test
    fun `profile race after host creation releases before rejecting and never tunes`() = runTest {
        var profileId = 1
        val fixture = fixture(
            profileSource = CleanLiveDestinationProfileSource { profileId },
            factoryCreated = { profileId = 2 },
        )

        fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)

        assertEquals(listOf("create", "release"), fixture.operations)
        assertNull(fixture.host.tunedSelection)
        assertEquals(1, fixture.host.releaseCalls)
        assertEquals(
            CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.PROFILE_MISMATCH),
            fixture.viewModel.routeState.value,
        )
        assertFalse(fixture.factory.ownerJob()!!.isActive)
    }

    @Test
    fun `profile race during tune releases before rejecting and never publishes ready`() = runTest {
        var profileId = 1
        val fixture = fixture(
            profileSource = CleanLiveDestinationProfileSource { profileId },
            tuneCompleted = { profileId = 2 },
        )

        fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)

        assertEquals(listOf("create", "tune", "release"), fixture.operations)
        assertEquals(1, fixture.host.releaseCalls)
        assertEquals(
            CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.PROFILE_MISMATCH),
            fixture.viewModel.routeState.value,
        )
        assertFalse(fixture.factory.ownerJob()!!.isActive)
    }

    @Test
    fun `tune failure waits for affirmative host release before returning rejection`() = runTest {
        val releaseBarrier = CompletableDeferred<Unit>()
        val fixture = fixture(
            tuneFailure = IllegalStateException("synthetic tune failure"),
            releaseBarrier = releaseBarrier,
        )

        val initialize = async {
            fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)
        }
        runCurrent()

        assertFalse(initialize.isCompleted)
        assertEquals(listOf("create", "tune", "release"), fixture.operations)
        assertTrue(fixture.factory.ownerJob()!!.isActive)
        assertEquals(CleanLivePlayerRouteState.Initializing, fixture.viewModel.routeState.value)

        releaseBarrier.complete(Unit)
        initialize.await()

        assertEquals(
            CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.TUNE_FAILED),
            fixture.viewModel.routeState.value,
        )
        assertFalse(fixture.factory.ownerJob()!!.isActive)
    }

    @Test
    fun `release before exit keeps owner scope alive until host barrier completes`() = runTest {
        val releaseBarrier = CompletableDeferred<Unit>()
        val fixture = fixture(releaseBarrier = releaseBarrier)
        fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)

        val release = async { fixture.viewModel.releaseBeforeExit() }
        runCurrent()

        assertFalse(release.isCompleted)
        assertTrue(fixture.factory.ownerJob()!!.isActive)
        assertEquals(1, fixture.host.releaseCalls)

        releaseBarrier.complete(Unit)
        release.await()

        assertFalse(fixture.factory.ownerJob()!!.isActive)
        fixture.viewModel.releaseBeforeExit()
        assertEquals(1, fixture.host.releaseCalls)
    }

    @Test
    fun `onCleared uses independent owner scope and waits for the same barrier`() = runTest {
        val releaseBarrier = CompletableDeferred<Unit>()
        val fixture = fixture(releaseBarrier = releaseBarrier)
        fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)
        val viewModelStore = ViewModelStore()
        viewModelStore.put("clean-live", fixture.viewModel)

        viewModelStore.clear()
        runCurrent()

        assertEquals(1, fixture.host.releaseCalls)
        assertTrue(fixture.factory.ownerJob()!!.isActive)
        releaseBarrier.complete(Unit)
        advanceUntilIdle()

        assertFalse(fixture.factory.ownerJob()!!.isActive)
    }

    @Test
    fun `onCleared retries transient release failures with one capped backoff loop`() = runTest {
        val waits = mutableListOf<Long>()
        val fixture = fixture(
            transientReleaseFailures = 2,
            releaseRetryWait = CleanLiveReleaseRetryWait { waits += it },
        )
        fixture.viewModel.initialize("route", fixture.activity, fixture.lifecycle, fixture.owner)
        val viewModelStore = ViewModelStore()
        viewModelStore.put("clean-live", fixture.viewModel)

        viewModelStore.clear()
        advanceUntilIdle()

        assertEquals(3, fixture.host.releaseCalls)
        assertEquals(listOf(100L, 200L), waits)
        assertFalse(fixture.factory.ownerJob()!!.isActive)
    }

    @Test
    fun `host creation and release failures are typed without exception text`() = runTest {
        val creation = fixture(hostCreationFailure = IllegalStateException("provider-secret"))
        creation.viewModel.initialize("route", creation.activity, creation.lifecycle, creation.owner)
        assertEquals(
            CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.HOST_CREATION_FAILED),
            creation.viewModel.routeState.value,
        )

        val release = fixture(releaseFailure = IllegalStateException("provider-secret"))
        release.viewModel.initialize("route", release.activity, release.lifecycle, release.owner)
        val failure = runCatching { release.viewModel.releaseBeforeExit() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(
            CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.RELEASE_FAILED),
            release.viewModel.routeState.value,
        )
        assertTrue(release.factory.ownerJob()!!.isActive)
    }

    @Test
    fun `route state and destination inputs never print token identity fingerprint or labels`() = runTest {
        val fixture = fixture()
        fixture.viewModel.initialize("route-secret", fixture.activity, fixture.lifecycle, fixture.owner)
        val ready = fixture.viewModel.routeState.value as CleanLivePlayerRouteState.Ready
        val input = requireNotNull(fixture.factory.input)
        val texts = listOf(ready.toString(), input.toString())

        texts.forEach { text ->
            assertFalse(text.contains("route-secret"))
            assertFalse(text.contains("provider-secret"))
            assertFalse(text.contains("customer-secret"))
            assertFalse(text.contains("Live News"))
            assertFalse(text.contains(fixture.entry.mediaFingerprint))
            assertFalse(text.contains(fixture.entry.activeProfileId.toString()))
        }
        fixture.viewModel.releaseBeforeExit()
    }

    private fun fixture(
        profileId: Int = 1,
        consumeResult: CleanLiveLaunchConsumeResult? = null,
        profileSource: CleanLiveDestinationProfileSource? = null,
        factoryCreated: (Int) -> Unit = {},
        hostCreationFailure: Exception? = null,
        tuneFailure: Exception? = null,
        tuneCompleted: () -> Unit = {},
        releaseFailure: Exception? = null,
        transientReleaseFailures: Int = 0,
        releaseBarrier: CompletableDeferred<Unit>? = null,
        releaseRetryWait: CleanLiveReleaseRetryWait = CleanLiveReleaseRetryWait {},
    ): Fixture {
        val context = RuntimeEnvironment.getApplication() as Context
        val operations = mutableListOf<String>()
        val entry = entry(profileId)
        val consumer = FakeConsumer(consumeResult ?: CleanLiveLaunchConsumeResult.Ready(entry))
        val host = FakeHost(
            operations,
            tuneFailure,
            tuneCompleted,
            releaseFailure,
            transientReleaseFailures,
            releaseBarrier,
        )
        val recreatedHost = FakeHost(
            operations,
            tuneFailure,
            tuneCompleted,
            releaseFailure,
            0,
            releaseBarrier,
        )
        val factory = FakeHostFactory(
            operations,
            listOf(host, recreatedHost),
            hostCreationFailure,
            factoryCreated,
        )
        val viewModel = CleanLivePlayerViewModel(
            context = context,
            launchConsumer = consumer,
            profileSource = profileSource ?: CleanLiveDestinationProfileSource { profileId },
            hostFactory = factory,
            ownerDispatcher = Dispatchers.Unconfined,
            releaseRetryWait = releaseRetryWait,
        )
        return Fixture(
            viewModel = viewModel,
            consumer = consumer,
            factory = factory,
            host = host,
            recreatedHost = recreatedHost,
            entry = entry,
            operations = operations,
            activity = mockk(relaxed = true),
            lifecycle = mockk(relaxed = true),
            owner = FrameLayout(context),
        )
    }

    private fun entry(profileId: Int): CleanLiveLaunchEntry = CleanLiveLaunchEntry(
        selection = ProviderPlaybackSelection(
            sourceType = ProviderSourceType.XTREAM,
            accountId = ProviderSelectionId("https://provider-secret.test|customer-secret"),
            itemId = ProviderSelectionId("42"),
            contentKey = ProviderSelectionId("xtream:provider-secret:live:42"),
            contentType = ContentType.LIVE,
        ),
        activeProfileId = profileId,
        metadata = CleanLiveLaunchMetadata.sanitized("Live News", "Now", "Station"),
        origin = CleanLiveLaunchOrigin.SEARCH,
        mediaFingerprint = "ab".repeat(32),
    )

    private data class Fixture(
        val viewModel: CleanLivePlayerViewModel,
        val consumer: FakeConsumer,
        val factory: FakeHostFactory,
        val host: FakeHost,
        val recreatedHost: FakeHost,
        val entry: CleanLiveLaunchEntry,
        val operations: List<String>,
        val activity: Activity,
        val lifecycle: Lifecycle,
        val owner: FrameLayout,
    )

    private class FakeConsumer(
        private val result: CleanLiveLaunchConsumeResult,
    ) : CleanLiveDestinationLaunchConsumer {
        var calls: Int = 0

        override fun consume(routeToken: String, currentProfileId: Int): CleanLiveLaunchConsumeResult {
            calls += 1
            return result
        }
    }

    private class FakeHostFactory(
        private val operations: MutableList<String>,
        private val hosts: List<CleanLiveDestinationHost>,
        private val failure: Exception?,
        private val created: (Int) -> Unit,
    ) : CleanLiveDestinationHostFactory {
        var calls: Int = 0
        var input: CleanLiveDestinationHostInput? = null

        override suspend fun create(input: CleanLiveDestinationHostInput): CleanLiveDestinationHost {
            calls += 1
            this.input = input
            operations += "create"
            created(calls)
            failure?.let { throw it }
            return hosts.getOrElse(calls - 1) { hosts.last() }
        }

        fun ownerJob(): Job? = input?.parentScope?.coroutineContext?.get(Job)
    }

    private class FakeHost(
        private val operations: MutableList<String>,
        private val tuneFailure: Exception?,
        private val tuneCompleted: () -> Unit,
        private val releaseFailure: Exception?,
        private val transientReleaseFailures: Int,
        private val releaseBarrier: CompletableDeferred<Unit>?,
    ) : CleanLiveDestinationHost {
        val snapshotFlow = MutableStateFlow(PlaybackSnapshot())
        override val snapshot: StateFlow<PlaybackSnapshot> = snapshotFlow
        var tunedSelection: ProviderPlaybackSelection? = null
        var tunedProfile: SessionProfile? = null
        var tunedMetadata: CleanMediaSessionMetadata? = null
        var releaseCalls: Int = 0

        override suspend fun tune(
            selection: ProviderPlaybackSelection,
            profile: SessionProfile,
            metadata: CleanMediaSessionMetadata,
        ) {
            operations += "tune"
            tunedSelection = selection
            tunedProfile = profile
            tunedMetadata = metadata
            tuneFailure?.let { throw it }
            tuneCompleted()
        }

        override suspend fun pause() {
            operations += "pause"
        }

        override suspend fun resume() {
            operations += "resume"
        }

        override suspend fun retry() {
            operations += "retry"
        }

        override suspend fun release() {
            releaseCalls += 1
            operations += "release"
            releaseBarrier?.await()
            if (releaseCalls <= transientReleaseFailures) {
                throw IllegalStateException("transient synthetic release failure")
            }
            releaseFailure?.let { throw it }
        }
    }

    private fun CleanLiveLaunchConsumeFailure.routeReason(): CleanLivePlayerRejection = when (this) {
        CleanLiveLaunchConsumeFailure.MISSING -> CleanLivePlayerRejection.MISSING
        CleanLiveLaunchConsumeFailure.EXPIRED -> CleanLivePlayerRejection.EXPIRED
        CleanLiveLaunchConsumeFailure.PROFILE_MISMATCH -> CleanLivePlayerRejection.PROFILE_MISMATCH
    }
}
