package com.nuvio.tv.ui.screens.iptv

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackProgressEvidence
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.host.AndroidCleanLiveHostInput
import com.nuvio.tv.playback.host.CleanLiveHost
import com.nuvio.tv.playback.host.CleanLiveHostFactory
import com.nuvio.tv.playback.live.LiveChannelNavigationPort
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import com.nuvio.tv.playback.live.LiveChannelTarget
import com.nuvio.tv.playback.live.LiveInitialRequest
import com.nuvio.tv.playback.live.LiveInitialResult
import com.nuvio.tv.playback.live.LivePlayedHistoryPort
import com.nuvio.tv.playback.live.LivePlayedIdentity
import com.nuvio.tv.playback.live.LiveRelativeRequest
import com.nuvio.tv.playback.live.LiveRelativeResult
import com.nuvio.tv.playback.live.LiveZapDirection
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CleanLiveGuidePlaybackViewModelTest {
    @Test
    fun `attach selects once and tunes one viewport-bound host in guide profile`() = runTest {
        val fixture = fixture()
        val viewport = VideoDimensions(640, 360)

        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
            viewport,
        )

        assertEquals(listOf(fixture.initial.contentId), fixture.selectionRequests.map { it.contentId })
        assertEquals(listOf("create", "tune"), fixture.operations)
        assertEquals(1, fixture.factory.calls)
        assertEquals("1", fixture.factory.inputs.single().preferenceProfileId.value)
        assertEquals(viewport, fixture.factory.inputs.single().previewViewport)
        assertSame(fixture.owner, fixture.factory.inputs.single().surfaceOwner)
        assertEquals(SessionProfile.GUIDE, fixture.firstHost.tuneProfiles.single())
        val ready = fixture.viewModel.state.value as CleanLiveGuidePlaybackState.Ready
        assertSame(fixture.initial, ready.target)
        assertEquals(SessionProfile.GUIDE, ready.sessionProfile)

        fixture.viewModel.requestPause()
        fixture.viewModel.requestResume()
        fixture.viewModel.requestRetry()
        advanceUntilIdle()
        assertEquals(
            listOf("create", "tune", "pause", "resume", "retry"),
            fixture.operations,
        )
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `later tune promote collapse and zap reuse the same host`() = runTest {
        val fixture = fixture()
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )

        fixture.viewModel.requestPromote()
        fixture.viewModel.requestTune(fixture.next.contentId)
        fixture.viewModel.requestCollapse()
        fixture.viewModel.requestZap(LiveZapDirection.NEXT)
        advanceUntilIdle()

        assertEquals(1, fixture.factory.calls)
        assertEquals(listOf(SessionProfile.GUIDE), fixture.firstHost.tuneProfiles)
        assertEquals(listOf(SessionProfile.FULLSCREEN, SessionProfile.GUIDE), fixture.firstHost.profileChanges)
        assertEquals(
            listOf(SessionProfile.FULLSCREEN, SessionProfile.GUIDE),
            fixture.firstHost.zapProfiles,
        )
        assertEquals(listOf(fixture.next.selection, fixture.afterNext.selection), fixture.firstHost.zapSelections)
        val ready = fixture.viewModel.state.value as CleanLiveGuidePlaybackState.Ready
        assertSame(fixture.afterNext, ready.target)
        assertEquals(SessionProfile.GUIDE, ready.sessionProfile)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `history accepts exact acknowledged generation first video only once`() = runTest {
        val fixture = fixture()
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )
        val accepted = fixture.firstHost.snapshotFlow.value.generation

        fixture.firstHost.snapshotFlow.value = fixture.firstHost.snapshotFlow.value.copy(
            generation = accepted,
            state = PlaybackState.PLAYING,
            progress = PlaybackProgressEvidence(renderedVideoFrame = true),
        )
        fixture.firstHost.snapshotFlow.value = fixture.firstHost.snapshotFlow.value.copy(
            isPlaying = true,
        )
        advanceUntilIdle()

        assertEquals(1, fixture.played.size)
        assertSame(fixture.initial, fixture.played.single().target)
        assertEquals(accepted, fixture.played.single().generation)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `superseding generation is never attributed to the prior target`() = runTest {
        val fixture = fixture()
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )
        val accepted = fixture.firstHost.snapshotFlow.value.generation

        fixture.firstHost.snapshotFlow.value = fixture.firstHost.snapshotFlow.value.copy(
            generation = accepted + 1,
            progress = PlaybackProgressEvidence(renderedVideoFrame = true),
        )
        advanceUntilIdle()

        assertTrue(fixture.played.isEmpty())
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `remote zap is serial and conflates repeats to one latest pending direction`() = runTest {
        val firstLookup = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val fixture = fixture(
            relative = { request, call ->
                if (call == 1) {
                    firstLookup.complete(Unit)
                    releaseLookup.await()
                    LiveRelativeResult.Target(fixtureTarget("first-zap", "44"))
                } else {
                    LiveRelativeResult.Target(fixtureTarget("latest-zap", "45"))
                }
            },
        )
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )

        fixture.viewModel.requestZap(LiveZapDirection.NEXT)
        runCurrent()
        firstLookup.await()
        fixture.viewModel.requestZap(LiveZapDirection.PREVIOUS)
        fixture.viewModel.requestZap(LiveZapDirection.NEXT)
        releaseLookup.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, fixture.relativeRequests.size)
        assertEquals(LiveZapDirection.NEXT, fixture.relativeRequests[0].direction)
        assertEquals(LiveZapDirection.NEXT, fixture.relativeRequests[1].direction)
        assertEquals(2, fixture.firstHost.zapSelections.size)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `surface rebind releases before recreating and retunes accepted target`() = runTest {
        val fixture = fixture()
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )
        val replacement = FrameLayout(fixture.owner.context)

        fixture.viewModel.attach(
            ProviderSelectionId("ignored-rebind-id"),
            fixture.activity,
            fixture.lifecycle,
            replacement,
        )

        assertEquals(
            listOf("create", "tune", "release", "create", "tune"),
            fixture.operations,
        )
        assertEquals(2, fixture.factory.calls)
        assertEquals(1, fixture.selectionRequests.size)
        assertSame(fixture.initial.selection, fixture.secondHost.tuneTargets.single())
        assertSame(replacement, fixture.factory.inputs.last().surfaceOwner)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `profile switch after host creation releases without tuning`() = runTest {
        var profileId = 1
        val fixture = fixture(
            profileSource = { profileId },
            factoryCreated = { profileId = 2 },
        )

        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )

        assertTrue(fixture.firstHost.tuneTargets.isEmpty())
        assertEquals(1, fixture.firstHost.releaseCalls)
        assertEquals(
            CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.PROFILE_CHANGED),
            fixture.viewModel.state.value,
        )
    }

    @Test
    fun `profile switch during relative lookup releases without accepting zap`() = runTest {
        var profileId = 1
        val fixture = fixture(
            profileSource = { profileId },
            relative = { _, _ ->
                profileId = 2
                LiveRelativeResult.Target(fixtureTarget("stale-zap", "46"))
            },
        )
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )

        fixture.viewModel.requestZap(LiveZapDirection.NEXT)
        advanceUntilIdle()

        assertTrue(fixture.firstHost.zapSelections.isEmpty())
        assertEquals(1, fixture.firstHost.releaseCalls)
        assertEquals(
            CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.PROFILE_CHANGED),
            fixture.viewModel.state.value,
        )
    }

    @Test
    fun `release is not blocked by a direct channel lookup`() = runTest {
        val lookupStarted = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val fixture = fixture(
            selectionBeforeResult = { request ->
                if (request.contentId == fixtureTarget("next", "43").contentId) {
                    lookupStarted.complete(Unit)
                    neverComplete.await()
                }
            },
        )
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )

        fixture.viewModel.requestTune(fixture.next.contentId)
        lookupStarted.await()
        val release = async { fixture.viewModel.releaseBeforeExit() }
        runCurrent()

        release.await()
        assertEquals(1, fixture.firstHost.releaseCalls)
        assertTrue(fixture.firstHost.zapSelections.isEmpty())
        assertEquals(CleanLiveGuidePlaybackState.Released, fixture.viewModel.state.value)
    }

    @Test
    fun `release is not blocked by initial selection lookup`() = runTest {
        val lookupStarted = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val fixture = fixture(
            selectionBeforeResult = { request ->
                if (request.contentId == ProviderSelectionId("channel-initial")) {
                    lookupStarted.complete(Unit)
                    neverComplete.await()
                }
            },
        )

        fixture.viewModel.attachGuide(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )
        lookupStarted.await()
        val release = async { fixture.viewModel.releaseBeforeExit() }
        runCurrent()

        release.await()
        assertEquals(0, fixture.factory.calls)
        assertEquals(CleanLiveGuidePlaybackState.Released, fixture.viewModel.state.value)
    }

    @Test
    fun `latest channel request supersedes an in-flight initial selection`() = runTest {
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val fixture = fixture(
            selectionBeforeResult = { request ->
                if (request.contentId == ProviderSelectionId("channel-initial")) {
                    lookupStarted.complete(Unit)
                    releaseLookup.await()
                }
            },
        )

        fixture.viewModel.attachGuide(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )
        lookupStarted.await()
        fixture.viewModel.requestTune(fixture.next.contentId)
        runCurrent()
        releaseLookup.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(fixture.initial.contentId, fixture.next.contentId), fixture.selectionRequests.map { it.contentId })
        assertEquals(listOf(fixture.next.selection), fixture.firstHost.tuneTargets)
        assertTrue(fixture.firstHost.zapSelections.isEmpty())
        val ready = fixture.viewModel.state.value as CleanLiveGuidePlaybackState.Ready
        assertSame(fixture.next, ready.target)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `detach releases and empties surface before reattach`() = runTest {
        val fixture = fixture()
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )
        assertEquals(1, fixture.owner.childCount)

        fixture.viewModel.detach()

        assertEquals(0, fixture.owner.childCount)
        assertEquals(CleanLiveGuidePlaybackState.Detached, fixture.viewModel.state.value)
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )
        assertEquals(2, fixture.factory.calls)
        assertEquals(1, fixture.owner.childCount)
        fixture.viewModel.releaseBeforeExit()
        assertEquals(0, fixture.owner.childCount)
    }

    @Test
    fun `public command failure is contained and published`() = runTest {
        val fixture = fixture(commandFailures = 1)
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )

        fixture.viewModel.requestPause()
        advanceUntilIdle()

        assertEquals(
            CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.COMMAND_FAILED),
            fixture.viewModel.state.value,
        )
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `conflated tune worker commits the latest request after a delayed first lookup`() = runTest {
        val firstLookupStarted = CompletableDeferred<Unit>()
        val releaseFirstLookup = CompletableDeferred<Unit>()
        val nextId = fixtureTarget("next", "43").contentId
        val fixture = fixture(
            selectionBeforeResult = { request ->
                if (request.contentId == nextId) {
                    firstLookupStarted.complete(Unit)
                    releaseFirstLookup.await()
                }
            },
        )
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )

        fixture.viewModel.requestTune(fixture.next.contentId)
        firstLookupStarted.await()
        fixture.viewModel.requestTune(fixture.initial.contentId)
        releaseFirstLookup.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(fixture.next.selection, fixture.initial.selection),
            fixture.firstHost.zapSelections,
        )
        val ready = fixture.viewModel.state.value as CleanLiveGuidePlaybackState.Ready
        assertSame(fixture.initial, ready.target)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `caller cancellation cannot split requested profile and accepted channel state`() = runTest {
        val fixture = fixture(ownerDispatcher = StandardTestDispatcher(testScheduler))
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )

        val caller = launch {
            fixture.viewModel.requestPromote()
            fixture.viewModel.requestTune(fixture.next.contentId)
            cancel()
        }
        caller.join()
        advanceUntilIdle()

        assertEquals(listOf(SessionProfile.FULLSCREEN), fixture.firstHost.profileChanges)
        assertEquals(listOf(fixture.next.selection), fixture.firstHost.zapSelections)
        val ready = fixture.viewModel.state.value as CleanLiveGuidePlaybackState.Ready
        assertSame(fixture.next, ready.target)
        assertEquals(SessionProfile.FULLSCREEN, ready.sessionProfile)
        fixture.viewModel.releaseBeforeExit()
    }

    @Test
    fun `onCleared retries release with capped exponential cadence`() = runTest {
        val waits = mutableListOf<Long>()
        val fixture = fixture(
            transientReleaseFailures = 2,
            releaseWait = CleanLiveGuideReleaseRetryWait { waits += it },
        )
        fixture.viewModel.attach(
            fixture.initial.contentId,
            fixture.activity,
            fixture.lifecycle,
            fixture.owner,
        )
        val store = ViewModelStore()
        store.put("clean-guide", fixture.viewModel)

        store.clear()
        advanceUntilIdle()

        assertEquals(3, fixture.firstHost.releaseCalls)
        assertEquals(listOf(100L, 200L), waits)
        assertFalse(fixture.factory.ownerJob()!!.isActive)
        assertEquals(CleanLiveGuidePlaybackState.Released, fixture.viewModel.state.value)
    }

    private fun fixture(
        profileSource: () -> Int = { 1 },
        relative: (suspend (LiveRelativeRequest, Int) -> LiveRelativeResult)? = null,
        factoryCreated: (Int) -> Unit = {},
        selectionBeforeResult: suspend (LiveInitialRequest) -> Unit = {},
        transientReleaseFailures: Int = 0,
        commandFailures: Int = 0,
        releaseWait: CleanLiveGuideReleaseRetryWait = CleanLiveGuideReleaseRetryWait {},
        ownerDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): Fixture {
        val context = RuntimeEnvironment.getApplication() as Context
        val operations = mutableListOf<String>()
        val initial = fixtureTarget("initial", "42")
        val next = fixtureTarget("next", "43")
        val afterNext = fixtureTarget("after-next", "43")
        val relativeResult: suspend (LiveRelativeRequest, Int) -> LiveRelativeResult =
            relative ?: { _, _ -> LiveRelativeResult.Target(afterNext) }
        val selections = mapOf(
            initial.contentId to initial,
            next.contentId to next,
        )
        val selectionRequests = mutableListOf<LiveInitialRequest>()
        val relativeRequests = mutableListOf<LiveRelativeRequest>()
        val played = mutableListOf<LivePlayedIdentity>()
        val firstHost = FakeHost(operations, transientReleaseFailures, commandFailures)
        val secondHost = FakeHost(operations, 0)
        val factory = FakeFactory(
            operations = operations,
            hosts = listOf(firstHost, secondHost),
            created = factoryCreated,
        )
        var relativeCalls = 0
        val viewModel = CleanLiveGuidePlaybackViewModel(
            context = context,
            profileSource = CleanLiveGuideProfileSource(profileSource),
            hostFactory = factory,
            liveSelection = LiveChannelSelectionPort { request ->
                selectionRequests += request
                selectionBeforeResult(request)
                selections[request.contentId]
                    ?.let { LiveInitialResult.Target(it) }
                    ?: LiveInitialResult.Rejected(com.nuvio.tv.playback.live.LiveInitialFailure.UNAVAILABLE)
            },
            liveNavigation = LiveChannelNavigationPort { request ->
                relativeRequests += request
                relativeCalls += 1
                relativeResult(request, relativeCalls)
            },
            playedHistory = LivePlayedHistoryPort { played += it },
            ownerDispatcher = ownerDispatcher,
            releaseRetryWait = releaseWait,
        )
        return Fixture(
            viewModel = viewModel,
            factory = factory,
            firstHost = firstHost,
            secondHost = secondHost,
            initial = initial,
            next = next,
            afterNext = afterNext,
            selectionRequests = selectionRequests,
            relativeRequests = relativeRequests,
            played = played,
            operations = operations,
            activity = mockk(relaxed = true),
            lifecycle = mockk(relaxed = true),
            owner = FrameLayout(context),
        )
    }

    private data class Fixture(
        val viewModel: CleanLiveGuidePlaybackViewModel,
        val factory: FakeFactory,
        val firstHost: FakeHost,
        val secondHost: FakeHost,
        val initial: LiveChannelTarget,
        val next: LiveChannelTarget,
        val afterNext: LiveChannelTarget,
        val selectionRequests: List<LiveInitialRequest>,
        val relativeRequests: List<LiveRelativeRequest>,
        val played: List<LivePlayedIdentity>,
        val operations: List<String>,
        val activity: Activity,
        val lifecycle: Lifecycle,
        val owner: FrameLayout,
    )

    private class FakeFactory(
        private val operations: MutableList<String>,
        private val hosts: List<CleanLiveHost>,
        private val created: (Int) -> Unit,
    ) : CleanLiveHostFactory {
            val inputs = mutableListOf<AndroidCleanLiveHostInput>()
        val calls: Int get() = inputs.size

        override suspend fun create(input: AndroidCleanLiveHostInput): CleanLiveHost {
            inputs += input
            operations += "create"
            created(inputs.size)
            return hosts.getOrElse(inputs.lastIndex) { hosts.last() }.also { createdHost ->
                (createdHost as? FakeHost)?.attach(input.surfaceOwner)
            }
        }

        fun ownerJob(): Job? = inputs.firstOrNull()?.parentScope?.coroutineContext?.get(Job)
    }

    private class FakeHost(
        private val operations: MutableList<String>,
        private val transientReleaseFailures: Int,
        private val commandFailures: Int = 0,
    ) : CleanLiveHost {
        val snapshotFlow = MutableStateFlow(PlaybackSnapshot())
        override val snapshot: StateFlow<PlaybackSnapshot> = snapshotFlow
        val tuneTargets = mutableListOf<ProviderPlaybackSelection>()
        val tuneProfiles = mutableListOf<SessionProfile>()
        val zapSelections = mutableListOf<ProviderPlaybackSelection>()
        val zapProfiles = mutableListOf<SessionProfile>()
        val profileChanges = mutableListOf<SessionProfile>()
        var releaseCalls = 0
        private var commandCalls = 0
        private var surfaceOwner: FrameLayout? = null

        fun attach(owner: FrameLayout) {
            surfaceOwner = owner
            owner.addView(View(owner.context))
        }

        override suspend fun tune(
            selection: ProviderPlaybackSelection,
            profile: SessionProfile,
            metadata: CleanMediaSessionMetadata,
        ): Long {
            operations += "tune"
            tuneTargets += selection
            tuneProfiles += profile
            return advance(profile)
        }

        override suspend fun zap(
            selection: ProviderPlaybackSelection,
            profile: SessionProfile,
            metadata: CleanMediaSessionMetadata,
        ): Long {
            operations += "zap"
            zapSelections += selection
            zapProfiles += profile
            return advance(profile)
        }

        private fun advance(profile: SessionProfile): Long {
            val generation = snapshotFlow.value.generation + 1
            snapshotFlow.value = snapshotFlow.value.copy(
                generation = generation,
                profile = profile,
                progress = PlaybackProgressEvidence(),
            )
            return generation
        }

        override suspend fun pause() {
            operations += "pause"
            commandCalls += 1
            if (commandCalls <= commandFailures) error("synthetic command failure")
        }

        override suspend fun resume() {
            operations += "resume"
        }

        override suspend fun retry() {
            operations += "retry"
        }

        override suspend fun changeProfile(profile: SessionProfile) {
            operations += "changeProfile"
            profileChanges += profile
            snapshotFlow.value = snapshotFlow.value.copy(profile = profile)
        }

        override suspend fun stop() {
            operations += "stop"
        }

        override suspend fun release() {
            operations += "release"
            releaseCalls += 1
            if (releaseCalls <= transientReleaseFailures) {
                error("synthetic transient release failure")
            }
            surfaceOwner?.removeAllViews()
            surfaceOwner = null
        }
    }

    private companion object {
        fun fixtureTarget(name: String, itemId: String): LiveChannelTarget {
            val selection = ProviderPlaybackSelection(
                sourceType = ProviderSourceType.XTREAM,
                accountId = ProviderSelectionId("account-private"),
                itemId = ProviderSelectionId(itemId),
                contentKey = ProviderSelectionId("channel-$name"),
                contentType = ContentType.LIVE,
            )
            return LiveChannelTarget.sanitized(
                selection = selection,
                contentId = selection.contentKey,
                title = "Channel $name",
                logo = null,
                playlistVersion = 1,
                boundProfileId = PlaybackProfileId("1"),
            )
        }
    }
}
