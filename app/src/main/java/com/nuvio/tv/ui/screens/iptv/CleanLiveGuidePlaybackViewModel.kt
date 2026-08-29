package com.nuvio.tv.ui.screens.iptv

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.host.AndroidCleanLiveHostFactory
import com.nuvio.tv.playback.host.AndroidCleanLiveHostInput
import com.nuvio.tv.playback.host.CleanLiveHost
import com.nuvio.tv.playback.host.CleanLiveHostFactory
import com.nuvio.tv.playback.live.LiveChannelNavigationPort
import com.nuvio.tv.playback.live.LiveZapSettlePolicy
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import com.nuvio.tv.playback.live.LiveChannelTarget
import com.nuvio.tv.playback.live.LiveInitialFailure
import com.nuvio.tv.playback.live.LiveInitialRequest
import com.nuvio.tv.playback.live.LiveInitialResult
import com.nuvio.tv.playback.live.LiveMediaFingerprint
import com.nuvio.tv.playback.live.LivePlayedHistoryPort
import com.nuvio.tv.playback.live.LivePlayedIdentity
import com.nuvio.tv.playback.live.LiveRelativeFailure
import com.nuvio.tv.playback.live.LiveRelativeRequest
import com.nuvio.tv.playback.live.LiveRelativeResult
import com.nuvio.tv.playback.live.LiveZapDirection
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import com.nuvio.tv.playback.ui.LivePlaybackUiPresenter
import com.nuvio.tv.playback.ui.LivePlaybackUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CleanLiveGuideFailure {
    SELECTION_UNAVAILABLE,
    PROFILE_CHANGED,
    INVALID_TARGET,
    HOST_CREATION_FAILED,
    TUNE_FAILED,
    COMMAND_FAILED,
    RELEASE_FAILED,
}

/** URL-, engine-, and provider-neutral state exposed to the future guide UI. */
sealed interface CleanLiveGuidePlaybackState {
    data object Detached : CleanLiveGuidePlaybackState
    data object Initializing : CleanLiveGuidePlaybackState

    data class Ready(
        val target: LiveChannelTarget,
        val snapshot: PlaybackSnapshot,
        val presentation: LivePlaybackUiState,
        val sessionProfile: SessionProfile,
    ) : CleanLiveGuidePlaybackState {
        override fun toString(): String =
            "CleanLiveGuidePlaybackState.Ready(state=${snapshot.state}, " +
                "sessionProfile=$sessionProfile, target=[REDACTED])"
    }

    data class Rejected(val reason: CleanLiveGuideFailure) : CleanLiveGuidePlaybackState
    data object Released : CleanLiveGuidePlaybackState
}

internal fun interface CleanLiveGuideProfileSource {
    fun activeProfileId(): Int
}

internal fun interface CleanLiveGuideReleaseRetryWait {
    suspend fun await(delayMs: Long)
}

/**
 * Guide-scoped owner for one clean live host. Catalogue loading remains in the legacy guide
 * ViewModel; this owner accepts only stable channel identity and never observes playback URLs or
 * engine objects.
 */
@HiltViewModel
internal class CleanLiveGuidePlaybackViewModel private constructor(
    private val appContext: Context,
    private val profileSource: CleanLiveGuideProfileSource,
    private val hostFactory: CleanLiveHostFactory,
    private val liveSelection: LiveChannelSelectionPort,
    private val liveNavigation: LiveChannelNavigationPort,
    private val playedHistory: LivePlayedHistoryPort,
    ownerDispatcher: CoroutineDispatcher,
    private val releaseRetryWait: CleanLiveGuideReleaseRetryWait,
) : ViewModel() {
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager,
        hostFactory: AndroidCleanLiveHostFactory,
        liveSelection: LiveChannelSelectionPort,
        liveNavigation: LiveChannelNavigationPort,
        playedHistory: LivePlayedHistoryPort,
    ) : this(
        appContext = context.applicationContext,
        profileSource = CleanLiveGuideProfileSource { profileManager.activeProfileId.value },
        hostFactory = hostFactory,
        liveSelection = liveSelection,
        liveNavigation = liveNavigation,
        playedHistory = playedHistory,
        ownerDispatcher = Dispatchers.Main.immediate,
        releaseRetryWait = CleanLiveGuideReleaseRetryWait { delay(it) },
    )

    internal constructor(
        context: Context,
        profileSource: CleanLiveGuideProfileSource,
        hostFactory: CleanLiveHostFactory,
        liveSelection: LiveChannelSelectionPort,
        liveNavigation: LiveChannelNavigationPort,
        playedHistory: LivePlayedHistoryPort = LivePlayedHistoryPort {},
        ownerDispatcher: CoroutineDispatcher,
        releaseRetryWait: CleanLiveGuideReleaseRetryWait = CleanLiveGuideReleaseRetryWait {},
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(
        appContext = context.applicationContext,
        profileSource = profileSource,
        hostFactory = hostFactory,
        liveSelection = liveSelection,
        liveNavigation = liveNavigation,
        playedHistory = playedHistory,
        ownerDispatcher = ownerDispatcher,
        releaseRetryWait = releaseRetryWait,
    )

    private val ownerJob = SupervisorJob()
    private val ownerScope = CoroutineScope(ownerJob + ownerDispatcher)
    private val ownershipMutex = Mutex()
    private val tuneRequests = Channel<ProviderSelectionId>(Channel.CONFLATED)
    private val settledTuneRequests = Channel<ProviderSelectionId>(Channel.CONFLATED)
    private val zapRequests = Channel<LiveZapDirection>(Channel.CONFLATED)
    private val mutableState =
        MutableStateFlow<CleanLiveGuidePlaybackState>(CleanLiveGuidePlaybackState.Detached)

    val state: StateFlow<CleanLiveGuidePlaybackState> = mutableState.asStateFlow()

    private var host: CleanLiveHost? = null
    private var attachedSurfaceOwner: FrameLayout? = null
    private var boundProfileId: PlaybackProfileId? = null
    private var activeTarget: LiveChannelTarget? = null
    private var sessionProfile = SessionProfile.GUIDE
    private var presentationJob: Job? = null
    private var historyRecordTail: Job? = null
    private var pendingPlayed: LivePlayedIdentity? = null
    private var releaseCompleted = false
    private var clearedReleaseLoopStarted = false
    private var attachGeneration = 0L
    private var pendingTuneContentId: ProviderSelectionId? = null
    private var lastAttachInput: GuideAttachInput? = null

    private val tuneWorker = ownerScope.launch {
        for (contentId in tuneRequests) performTune(contentId)
    }

    private val settledTuneWorker = ownerScope.launch {
        for (initial in settledTuneRequests) {
            var destination = initial
            delay(LiveZapSettlePolicy.SETTLE_MS)
            while (true) {
                destination = settledTuneRequests.tryReceive().getOrNull() ?: break
            }
            performTune(destination)
        }
    }

    private val zapWorker = ownerScope.launch {
        for (direction in zapRequests) performZap(direction)
    }

    /** Compose-safe entry point; accepted work belongs to the ViewModel scope. */
    fun attachGuide(
        initialContentId: ProviderSelectionId,
        activity: Activity,
        lifecycle: Lifecycle,
        surfaceOwner: FrameLayout,
        previewViewport: VideoDimensions? = null,
    ) {
        if (releaseCompleted || clearedReleaseLoopStarted) return
        launchContained(CleanLiveGuideFailure.HOST_CREATION_FAILED) {
            attach(initialContentId, activity, lifecycle, surfaceOwner, previewViewport)
        }
    }

    internal suspend fun attach(
        initialContentId: ProviderSelectionId,
        activity: Activity,
        lifecycle: Lifecycle,
        surfaceOwner: FrameLayout,
        previewViewport: VideoDimensions? = null,
    ) {
        val basis = ownershipMutex.withLock {
            if (releaseCompleted) return
            if (host != null && attachedSurfaceOwner === surfaceOwner) return
            require(surfaceOwner.childCount == 0) {
                "Clean guide playback surface owner must start empty"
            }

            val retainedTarget = activeTarget
            if (host != null && !releaseHostForSurfaceRebind()) return

            val profile = boundProfileId ?: activeProfileIdOrReject() ?: return
            lastAttachInput = GuideAttachInput(
                activity = activity,
                lifecycle = lifecycle,
                surfaceOwner = surfaceOwner,
                previewViewport = previewViewport,
            )
            attachGeneration += 1
            mutableState.value = CleanLiveGuidePlaybackState.Initializing
            AttachBasis(
                generation = attachGeneration,
                profile = profile,
                retainedTarget = retainedTarget,
                activity = activity,
                lifecycle = lifecycle,
                surfaceOwner = surfaceOwner,
                previewViewport = previewViewport,
            )
        }

        // Provider selection may block on storage/provider work. Never hold the ownership lock here:
        // detach/release must remain an affirmative barrier even when this lookup hangs.
        val selected = basis.retainedTarget?.let { LiveInitialResult.Target(it) }
            ?: selectResult(initialContentId, basis.profile)

        var supersedingContentId: ProviderSelectionId? = null
        ownershipMutex.withLock {
            if (!basis.isStillCurrent()) return@withLock
            val pending = pendingTuneContentId
            if (pending != null && pending != initialContentId) {
                pendingTuneContentId = null
                attachGeneration += 1
                supersedingContentId = pending
                return@withLock
            }
            val target = when (selected) {
                is LiveInitialResult.Target -> selected.target
                is LiveInitialResult.Rejected -> {
                    mutableState.value =
                        CleanLiveGuidePlaybackState.Rejected(selected.reason.toGuideFailure())
                    return@withLock
                }
            }
            if (!profileMatches(basis.profile)) {
                mutableState.value =
                    CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.PROFILE_CHANGED)
                return@withLock
            }
            if (!validTarget(target, basis.profile)) {
                mutableState.value =
                    CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.INVALID_TARGET)
                return@withLock
            }
            createAndTune(
                target = target,
                profile = basis.profile,
                activity = basis.activity,
                lifecycle = basis.lifecycle,
                surfaceOwner = basis.surfaceOwner,
                previewViewport = basis.previewViewport,
            )
            val pendingAfterTune = pendingTuneContentId
            pendingTuneContentId = null
            if (host != null && pendingAfterTune != null && pendingAfterTune != target.contentId) {
                tuneRequests.trySend(pendingAfterTune)
            }
        }
        supersedingContentId?.let { latest ->
            attach(
                initialContentId = latest,
                activity = basis.activity,
                lifecycle = basis.lifecycle,
                surfaceOwner = basis.surfaceOwner,
                previewViewport = basis.previewViewport,
            )
        }
    }

    /** Accepted direct-channel work is independent of a Compose caller's coroutine lifetime. */
    fun requestTune(contentId: ProviderSelectionId) {
        if (!releaseCompleted && !clearedReleaseLoopStarted) tuneRequests.trySend(contentId)
    }

    /** Exact highlighted destinations are debounced; relative D-pad deltas never enter playback. */
    fun requestSettledTune(contentId: ProviderSelectionId) {
        if (!releaseCompleted && !clearedReleaseLoopStarted) settledTuneRequests.trySend(contentId)
    }

    /**
     * Invalidates playback ownership as soon as the guide selects a different provider/account.
     * Catalogue success is deliberately not a prerequisite: a broken or empty replacement can
     * never leave the previous provider audible behind its error UI.
     */
    fun requestProviderOwnershipChange() {
        if (releaseCompleted || clearedReleaseLoopStarted) return
        launchContained(CleanLiveGuideFailure.RELEASE_FAILED) { invalidateProviderOwnership() }
    }

    internal suspend fun invalidateProviderOwnership() = ownershipMutex.withLock {
        if (releaseCompleted) return@withLock
        attachGeneration += 1
        pendingTuneContentId = null
        activeTarget = null
        pendingPlayed = null
        val current = host
        if (current != null && !releaseHostForSurfaceRebind()) return@withLock
        mutableState.value = CleanLiveGuidePlaybackState.Detached
    }

    private suspend fun performTune(contentId: ProviderSelectionId) {
        var restart: GuideAttachInput? = null
        val basis: ChannelBasis? = ownershipMutex.withLock {
            if (releaseCompleted) return
            val currentHost = host
            if (currentHost == null) {
                // An account/category update can arrive while initial selection is in flight.
                // Retain the latest request and apply it after the one host has tuned.
                pendingTuneContentId = contentId
                if (mutableState.value !is CleanLiveGuidePlaybackState.Initializing) {
                    restart = lastAttachInput
                }
                return@withLock null
            }
            val currentTarget = activeTarget ?: return
            val profile = boundProfileId ?: return
            if (!profileMatches(profile)) {
                rejectAfterRelease(currentHost, CleanLiveGuideFailure.PROFILE_CHANGED)
                return
            }
            ChannelBasis(currentHost, currentTarget, profile)
        }
        if (basis == null) {
            restart?.let { input ->
                attach(
                    initialContentId = contentId,
                    activity = input.activity,
                    lifecycle = input.lifecycle,
                    surfaceOwner = input.surfaceOwner,
                    previewViewport = input.previewViewport,
                )
            }
            return
        }

        val selected = selectResult(contentId, basis.profile)

        ownershipMutex.withLock {
            if (!basis.isStillCurrent()) return@withLock
            val target = when (selected) {
                is LiveInitialResult.Target -> selected.target
                is LiveInitialResult.Rejected -> {
                    if (selected.reason == LiveInitialFailure.PROFILE_CHANGED) {
                        rejectAfterRelease(basis.host, CleanLiveGuideFailure.PROFILE_CHANGED)
                    }
                    return@withLock
                }
            }
            if (!profileMatches(basis.profile)) {
                rejectAfterRelease(basis.host, CleanLiveGuideFailure.PROFILE_CHANGED)
                return@withLock
            }
            if (!validTarget(target, basis.profile)) return@withLock
            // Every channel change after the initial tune uses the session's zap release barrier.
            acceptTarget(basis.host, target, basis.profile)
        }
    }

    /** Remote repeats are serialized, with only the newest pending repeat retained. */
    fun requestZap(direction: LiveZapDirection) {
        if (!releaseCompleted && !clearedReleaseLoopStarted) zapRequests.trySend(direction)
    }

    private suspend fun performZap(direction: LiveZapDirection) {
        val basis = ownershipMutex.withLock {
            if (releaseCompleted) return
            val currentHost = host ?: return
            val target = activeTarget ?: return
            val profile = boundProfileId ?: return
            if (!profileMatches(profile)) {
                rejectAfterRelease(currentHost, CleanLiveGuideFailure.PROFILE_CHANGED)
                return
            }
            ChannelBasis(currentHost, target, profile)
        }

        val relative = try {
            liveNavigation.relative(
                LiveRelativeRequest(
                    currentContentId = basis.target.contentId,
                    direction = direction,
                    boundProfileId = basis.profile,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }

        ownershipMutex.withLock {
            if (!basis.isStillCurrent()) return@withLock
            val next = when (relative) {
                is LiveRelativeResult.Target -> relative.target
                is LiveRelativeResult.Rejected -> {
                    if (relative.reason == LiveRelativeFailure.PROFILE_CHANGED) {
                        rejectAfterRelease(basis.host, CleanLiveGuideFailure.PROFILE_CHANGED)
                    }
                    return@withLock
                }
            }
            if (!profileMatches(basis.profile)) {
                rejectAfterRelease(basis.host, CleanLiveGuideFailure.PROFILE_CHANGED)
                return@withLock
            }
            if (!validTarget(next, basis.profile)) return@withLock
            acceptTarget(basis.host, next, basis.profile)
        }
    }

    fun requestPromote() {
        if (!releaseCompleted && !clearedReleaseLoopStarted) {
            launchContained(CleanLiveGuideFailure.COMMAND_FAILED) {
                changeSessionProfile(SessionProfile.FULLSCREEN)
            }
        }
    }

    fun requestCollapse() {
        if (!releaseCompleted && !clearedReleaseLoopStarted) {
            launchContained(CleanLiveGuideFailure.COMMAND_FAILED) {
                changeSessionProfile(SessionProfile.GUIDE)
            }
        }
    }

    private suspend fun changeSessionProfile(next: SessionProfile) = ownershipMutex.withLock {
        if (releaseCompleted || sessionProfile == next) return@withLock
        val currentHost = host ?: return@withLock
        val profile = boundProfileId ?: return@withLock
        if (!profileMatches(profile)) {
            rejectAfterRelease(currentHost, CleanLiveGuideFailure.PROFILE_CHANGED)
            return@withLock
        }
        try {
            currentHost.changeProfile(next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value =
                CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.COMMAND_FAILED)
            return@withLock
        }
        if (!profileMatches(profile)) {
            rejectAfterRelease(currentHost, CleanLiveGuideFailure.PROFILE_CHANGED)
            return@withLock
        }
        sessionProfile = next
        publishSnapshot(currentHost.snapshot.value)
    }

    fun requestPause() = requestCommand { it.pause() }
    fun requestResume() = requestCommand { it.resume() }
    fun requestRetry() = requestCommand { it.retry() }

    private fun requestCommand(action: suspend (CleanLiveHost) -> Unit) {
        if (!releaseCompleted && !clearedReleaseLoopStarted) {
            launchContained(CleanLiveGuideFailure.COMMAND_FAILED) { command(action) }
        }
    }

    private suspend fun command(action: suspend (CleanLiveHost) -> Unit) =
        ownershipMutex.withLock {
            if (releaseCompleted) return@withLock
            val currentHost = host ?: return@withLock
            val profile = boundProfileId ?: return@withLock
            if (!profileMatches(profile)) {
                rejectAfterRelease(currentHost, CleanLiveGuideFailure.PROFILE_CHANGED)
                return@withLock
            }
            try {
                action(currentHost)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value =
                    CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.COMMAND_FAILED)
                return@withLock
            }
            if (!profileMatches(profile)) {
                rejectAfterRelease(currentHost, CleanLiveGuideFailure.PROFILE_CHANGED)
            }
        }

    private suspend fun createAndTune(
        target: LiveChannelTarget,
        profile: PlaybackProfileId,
        activity: Activity,
        lifecycle: Lifecycle,
        surfaceOwner: FrameLayout,
        previewViewport: VideoDimensions?,
    ) {
        mutableState.value = CleanLiveGuidePlaybackState.Initializing
        val created = try {
            hostFactory.create(
                AndroidCleanLiveHostInput(
                    context = appContext,
                    preferenceProfileId = profile,
                    parentScope = ownerScope,
                    activity = activity,
                    lifecycle = lifecycle,
                    surfaceOwner = surfaceOwner,
                    previewViewport = previewViewport,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logBoundaryFailure("host-create", error)
            mutableState.value =
                CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.HOST_CREATION_FAILED)
            return
        }
        host = created
        attachedSurfaceOwner = surfaceOwner

        if (!profileMatches(profile)) {
            rejectAfterRelease(created, CleanLiveGuideFailure.PROFILE_CHANGED)
            return
        }
        val acceptedGeneration = try {
            created.tune(target.selection, sessionProfile, mediaMetadata(target))
        } catch (cancelled: CancellationException) {
            releaseCreatedHost(created)
            throw cancelled
        } catch (_: Exception) {
            rejectAfterRelease(created, CleanLiveGuideFailure.TUNE_FAILED)
            return
        }
        if (!profileMatches(profile)) {
            rejectAfterRelease(created, CleanLiveGuideFailure.PROFILE_CHANGED)
            return
        }

        boundProfileId = profile
        activeTarget = target
        pendingPlayed = LivePlayedIdentity(target, profile, acceptedGeneration)
        publishReady(created)
    }

    private suspend fun acceptTarget(
        currentHost: CleanLiveHost,
        target: LiveChannelTarget,
        profile: PlaybackProfileId,
    ) {
        val previous = activeTarget ?: return
        presentationJob?.cancelAndJoin()
        presentationJob = null
        val acceptedGeneration = try {
            currentHost.zap(target.selection, sessionProfile, mediaMetadata(target))
        } catch (cancelled: CancellationException) {
            publishReady(currentHost)
            throw cancelled
        } catch (_: Exception) {
            activeTarget = previous
            publishReady(currentHost)
            return
        }
        if (!profileMatches(profile)) {
            rejectAfterRelease(currentHost, CleanLiveGuideFailure.PROFILE_CHANGED)
            return
        }

        activeTarget = target
        pendingPlayed = LivePlayedIdentity(target, profile, acceptedGeneration)
        publishReady(currentHost)
    }

    private suspend fun selectResult(
        contentId: ProviderSelectionId,
        profile: PlaybackProfileId,
    ): LiveInitialResult = try {
        liveSelection.select(LiveInitialRequest(contentId, profile))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LiveInitialResult.Rejected(LiveInitialFailure.UNAVAILABLE)
    }

    private fun validTarget(target: LiveChannelTarget, profile: PlaybackProfileId): Boolean =
        target.mediaFingerprint == LiveMediaFingerprint.create(target.selection, profile)

    private fun activeProfileIdOrReject(): PlaybackProfileId? {
        val value = profileSource.activeProfileId().takeIf { it > 0 }
        if (value == null) {
            mutableState.value =
                CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.INVALID_TARGET)
        }
        return value?.let { PlaybackProfileId(it.toString()) }
    }

    private fun profileMatches(profile: PlaybackProfileId): Boolean {
        val expected = profile.value.toIntOrNull()?.takeIf { it > 0 } ?: return false
        return profileSource.activeProfileId() == expected
    }

    private fun publishReady(current: CleanLiveHost) {
        publishSnapshot(current.snapshot.value)
        presentationJob = ownerScope.launch {
            current.snapshot.collect(::publishSnapshot)
        }
    }

    private fun publishSnapshot(snapshot: PlaybackSnapshot) {
        val target = activeTarget ?: return
        mutableState.value = CleanLiveGuidePlaybackState.Ready(
            target = target,
            snapshot = snapshot,
            presentation = LivePlaybackUiPresenter.present(snapshot),
            sessionProfile = sessionProfile,
        )
        acceptRenderedVideoEvidence(snapshot)
    }

    private fun acceptRenderedVideoEvidence(snapshot: PlaybackSnapshot) {
        val pending = pendingPlayed ?: return
        when {
            snapshot.generation == pending.generation && snapshot.progress.renderedVideoFrame -> {
                pendingPlayed = null
                val predecessor = historyRecordTail
                historyRecordTail = ownerScope.launch {
                    predecessor?.join()
                    if (!profileMatches(pending.boundProfileId)) return@launch
                    try {
                        playedHistory.record(pending)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Recent-channel persistence is best effort and never fails playback.
                    }
                }
            }
            snapshot.generation > pending.generation -> pendingPlayed = null
        }
    }

    private suspend fun releaseHostForSurfaceRebind(): Boolean {
        val current = host ?: return true
        presentationJob?.cancelAndJoin()
        presentationJob = null
        pendingPlayed = null
        mutableState.value = CleanLiveGuidePlaybackState.Initializing
        return try {
            withContext(NonCancellable) { current.release() }
            if (host === current) host = null
            attachedSurfaceOwner = null
            true
        } catch (_: Exception) {
            mutableState.value =
                CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.RELEASE_FAILED)
            false
        }
    }

    private suspend fun rejectAfterRelease(
        current: CleanLiveHost,
        reason: CleanLiveGuideFailure,
    ) {
        try {
            releaseCreatedHost(current)
            mutableState.value = CleanLiveGuidePlaybackState.Rejected(reason)
        } catch (_: Exception) {
            mutableState.value =
                CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.RELEASE_FAILED)
        }
    }

    private suspend fun releaseCreatedHost(current: CleanLiveHost) {
        presentationJob?.cancelAndJoin()
        presentationJob = null
        withContext(NonCancellable) { current.release() }
        if (host === current) host = null
        attachedSurfaceOwner = null
        pendingPlayed = null
    }

    /**
     * Nonterminal guide disposal. The old host's affirmative barrier completes before a later
     * attach can recreate it; target/profile identity remains available for that reattachment.
     */
    fun detachGuide() {
        if (!releaseCompleted && !clearedReleaseLoopStarted) {
            launchContained(CleanLiveGuideFailure.RELEASE_FAILED) { detach() }
        }
    }

    internal suspend fun detach() = withContext(NonCancellable) {
        ownershipMutex.withLock {
            if (releaseCompleted) return@withLock
            attachGeneration += 1
            pendingTuneContentId = null
            presentationJob?.cancelAndJoin()
            presentationJob = null
            val current = host
            if (current != null) {
                try {
                    current.release()
                } catch (error: Exception) {
                    mutableState.value =
                        CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.RELEASE_FAILED)
                    throw error
                }
                host = null
            }
            attachedSurfaceOwner = null
            pendingPlayed = null
            sessionProfile = SessionProfile.GUIDE
            // The attach input retains an Activity/view tree/lifecycle; keeping it across a
            // detach leaks a destroyed Activity through configuration changes and lets a late
            // tune rebuild against a dead lifecycle. A detached guide waits for the next real
            // attach instead of restarting from stale inputs.
            lastAttachInput = null
            mutableState.value = CleanLiveGuidePlaybackState.Detached
        }
    }

    /** Returns only after the host's provider/engine release barrier has completed. */
    suspend fun releaseBeforeExit() = withContext(NonCancellable) {
        ownershipMutex.withLock {
            if (releaseCompleted) return@withLock
            attachGeneration += 1
            pendingTuneContentId = null
            presentationJob?.cancelAndJoin()
            presentationJob = null
            val current = host
            if (current != null) {
                try {
                    current.release()
                } catch (cancelled: CancellationException) {
                    mutableState.value =
                        CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.RELEASE_FAILED)
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.value =
                        CleanLiveGuidePlaybackState.Rejected(CleanLiveGuideFailure.RELEASE_FAILED)
                    throw error
                }
                host = null
            }
            completeRelease()
        }
    }

    override fun onCleared() {
        if (!ownerJob.isActive || clearedReleaseLoopStarted) return
        clearedReleaseLoopStarted = true
        ownerScope.launch {
            var backoffMs = INITIAL_CLEAR_RELEASE_BACKOFF_MS
            while (ownerJob.isActive && !releaseCompleted) {
                if (runCatching { releaseBeforeExit() }.isSuccess) return@launch
                releaseRetryWait.await(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_CLEAR_RELEASE_BACKOFF_MS)
            }
        }
    }

    private fun completeRelease() {
        releaseCompleted = true
        tuneRequests.close()
        settledTuneRequests.close()
        settledTuneWorker.cancel()
        tuneWorker.cancel()
        zapRequests.close()
        zapWorker.cancel()
        pendingPlayed = null
        pendingTuneContentId = null
        historyRecordTail = null
        presentationJob?.cancel()
        presentationJob = null
        host = null
        attachedSurfaceOwner = null
        boundProfileId = null
        activeTarget = null
        lastAttachInput = null
        mutableState.value = CleanLiveGuidePlaybackState.Released
        ownerJob.cancel()
    }

    private fun mediaMetadata(target: LiveChannelTarget): CleanMediaSessionMetadata =
        CleanMediaSessionMetadata.fromIngress(
            redactedContentFingerprint = target.mediaFingerprint,
            title = target.title,
        )

    private fun ChannelBasis.isStillCurrent(): Boolean =
        !releaseCompleted &&
            host === this.host &&
            activeTarget === target &&
            boundProfileId == profile

    private fun AttachBasis.isStillCurrent(): Boolean =
        !releaseCompleted &&
            attachGeneration == generation &&
            host == null &&
            attachedSurfaceOwner == null

    private fun launchContained(
        failure: CleanLiveGuideFailure,
        block: suspend () -> Unit,
    ) {
        ownerScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logBoundaryFailure("contained-${failure.name.lowercase()}", error)
                mutableState.value = CleanLiveGuidePlaybackState.Rejected(failure)
            }
        }
    }

    /** Failure class and throw site only: useful on TV hardware without logging provider data. */
    private fun logBoundaryFailure(boundary: String, error: Exception) {
        val throwSite = error.stackTrace.firstOrNull()?.toString().orEmpty()
        Log.e(LOG_TAG, "$boundary failed: ${error::class.java.name} at $throwSite")
    }

    private data class ChannelBasis(
        val host: CleanLiveHost,
        val target: LiveChannelTarget,
        val profile: PlaybackProfileId,
    )

    private data class AttachBasis(
        val generation: Long,
        val profile: PlaybackProfileId,
        val retainedTarget: LiveChannelTarget?,
        val activity: Activity,
        val lifecycle: Lifecycle,
        val surfaceOwner: FrameLayout,
        val previewViewport: VideoDimensions?,
    )

    private data class GuideAttachInput(
        val activity: Activity,
        val lifecycle: Lifecycle,
        val surfaceOwner: FrameLayout,
        val previewViewport: VideoDimensions?,
    )

    private fun LiveInitialFailure.toGuideFailure(): CleanLiveGuideFailure = when (this) {
        LiveInitialFailure.UNAVAILABLE -> CleanLiveGuideFailure.SELECTION_UNAVAILABLE
        LiveInitialFailure.PROFILE_CHANGED -> CleanLiveGuideFailure.PROFILE_CHANGED
        LiveInitialFailure.INVALID_TARGET -> CleanLiveGuideFailure.INVALID_TARGET
    }

    private companion object {
        const val LOG_TAG = "CleanLiveGuide"
        const val INITIAL_CLEAR_RELEASE_BACKOFF_MS = 100L
        const val MAX_CLEAR_RELEASE_BACKOFF_MS = 5_000L
    }
}
