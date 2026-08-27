package com.nuvio.tv.ui.screens.player.clean

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.host.AndroidCleanLiveHostFactory
import com.nuvio.tv.playback.host.AndroidCleanLiveHostInput
import com.nuvio.tv.playback.host.CleanLiveHost
import com.nuvio.tv.playback.host.CleanLiveHostFactory
import com.nuvio.tv.playback.live.LiveChannelNavigationPort
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
import com.nuvio.tv.ui.navigation.CleanLiveLaunchConsumeFailure
import com.nuvio.tv.ui.navigation.CleanLiveLaunchConsumeResult
import com.nuvio.tv.ui.navigation.CleanLiveLaunchEntry
import com.nuvio.tv.ui.navigation.CleanLiveLaunchMetadata
import com.nuvio.tv.ui.navigation.CleanLiveLaunchOrigin
import com.nuvio.tv.ui.navigation.CleanLiveLaunchStore
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

enum class CleanLivePlayerRejection {
    MISSING,
    EXPIRED,
    PROFILE_MISMATCH,
    HOST_CREATION_FAILED,
    TUNE_FAILED,
    RELEASE_FAILED,
}

sealed interface CleanLivePlayerRouteState {
    data object Initializing : CleanLivePlayerRouteState

    data class Ready(
        val metadata: CleanLiveLaunchMetadata,
        val origin: CleanLiveLaunchOrigin,
        val snapshot: PlaybackSnapshot,
        val presentation: LivePlaybackUiState,
    ) : CleanLivePlayerRouteState {
        override fun toString(): String =
            "CleanLivePlayerRouteState.Ready(origin=$origin, state=${snapshot.state}, " +
                "hasStatus=${presentation.bottomStatusCode != null}, " +
                "hasError=${presentation.bottomErrorCode != null})"
    }

    data class Rejected(
        val reason: CleanLivePlayerRejection,
    ) : CleanLivePlayerRouteState
}

internal fun interface CleanLiveDestinationLaunchConsumer {
    fun consume(routeToken: String, currentProfileId: Int): CleanLiveLaunchConsumeResult
}

internal fun interface CleanLiveDestinationProfileSource {
    fun activeProfileId(): Int
}

internal fun interface CleanLiveReleaseRetryWait {
    suspend fun await(delayMs: Long)
}

/**
 * Destination-scoped clean Live TV owner. Its route is registered in isolation while production
 * live ingresses remain detached until their atomic switch.
 */
@HiltViewModel
internal class CleanLivePlayerViewModel private constructor(
    private val appContext: Context,
    private val launchConsumer: CleanLiveDestinationLaunchConsumer,
    private val profileSource: CleanLiveDestinationProfileSource,
    private val hostFactory: CleanLiveHostFactory,
    private val liveNavigation: LiveChannelNavigationPort,
    private val playedHistory: LivePlayedHistoryPort,
    ownerDispatcher: CoroutineDispatcher,
    private val releaseRetryWait: CleanLiveReleaseRetryWait,
) : ViewModel() {
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        launchStore: CleanLiveLaunchStore,
        profileManager: ProfileManager,
        hostFactory: AndroidCleanLiveHostFactory,
        liveNavigation: LiveChannelNavigationPort,
        playedHistory: LivePlayedHistoryPort,
    ) : this(
        appContext = context.applicationContext,
        launchConsumer = CleanLiveDestinationLaunchConsumer(launchStore::consume),
        profileSource = CleanLiveDestinationProfileSource { profileManager.activeProfileId.value },
        hostFactory = hostFactory,
        liveNavigation = liveNavigation,
        playedHistory = playedHistory,
        ownerDispatcher = Dispatchers.Main.immediate,
        releaseRetryWait = CleanLiveReleaseRetryWait { delay(it) },
    )

    internal constructor(
        context: Context,
        launchConsumer: CleanLiveDestinationLaunchConsumer,
        profileSource: CleanLiveDestinationProfileSource,
        hostFactory: CleanLiveHostFactory,
        liveNavigation: LiveChannelNavigationPort = LiveChannelNavigationPort {
            LiveRelativeResult.Rejected(LiveRelativeFailure.UNAVAILABLE)
        },
        playedHistory: LivePlayedHistoryPort = LivePlayedHistoryPort {},
        ownerDispatcher: CoroutineDispatcher,
        releaseRetryWait: CleanLiveReleaseRetryWait = CleanLiveReleaseRetryWait {},
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(
        appContext = context.applicationContext,
        launchConsumer = launchConsumer,
        profileSource = profileSource,
        hostFactory = hostFactory,
        liveNavigation = liveNavigation,
        playedHistory = playedHistory,
        ownerDispatcher = ownerDispatcher,
        releaseRetryWait = releaseRetryWait,
    )

    private val ownerJob = SupervisorJob()
    private val ownerScope = CoroutineScope(ownerJob + ownerDispatcher)
    private val ownershipMutex = Mutex()
    private val zapRequests = Channel<LiveZapDirection>(Channel.CONFLATED)
    private val mutableRouteState =
        MutableStateFlow<CleanLivePlayerRouteState>(CleanLivePlayerRouteState.Initializing)

    val routeState: StateFlow<CleanLivePlayerRouteState> = mutableRouteState.asStateFlow()

    private var initialized = false
    private var releaseCompleted = false
    private var host: CleanLiveHost? = null
    private var activeLaunch: CleanLiveLaunchEntry? = null
    private var activeMetadata: CleanMediaSessionMetadata? = null
    private var attachedSurfaceOwner: FrameLayout? = null
    private var presentationJob: Job? = null
    private var historyRecordTail: Job? = null
    private var pendingPlayed: LivePlayedIdentity? = null
    private var clearedReleaseLoopStarted = false

    private val zapWorker = ownerScope.launch {
        for (direction in zapRequests) performZap(direction)
    }

    /**
     * Enqueues destination attachment on the ViewModel-owned scope. Compose disposal must never
     * cancel initial tune or turn a configuration change into a terminal released destination.
     */
    fun attachDestination(
        routeToken: String,
        activity: Activity,
        lifecycle: Lifecycle,
        surfaceOwner: FrameLayout,
    ) {
        if (clearedReleaseLoopStarted || releaseCompleted) return
        ownerScope.launch {
            initialize(routeToken, activity, lifecycle, surfaceOwner)
        }
    }

    internal suspend fun initialize(
        routeToken: String,
        activity: Activity,
        lifecycle: Lifecycle,
        surfaceOwner: FrameLayout,
    ) = ownershipMutex.withLock {
        if (releaseCompleted) return@withLock
        if (initialized) {
            if (attachedSurfaceOwner === surfaceOwner) return@withLock
            require(surfaceOwner.childCount == 0) {
                "Clean live destination surface owner must start empty"
            }
            val retainedLaunch = activeLaunch ?: return@withLock
            val retainedMetadata = activeMetadata ?: return@withLock
            if (!releaseHostForSurfaceRebind()) return@withLock
            createAndTune(retainedLaunch, retainedMetadata, activity, lifecycle, surfaceOwner)
            return@withLock
        }
        require(surfaceOwner.childCount == 0) {
            "Clean live destination surface owner must start empty"
        }
        initialized = true

        val capturedProfileId = profileSource.activeProfileId()
        val launch = when (
            val consumed = launchConsumer.consume(routeToken, capturedProfileId)
        ) {
            is CleanLiveLaunchConsumeResult.Ready -> consumed.entry
            is CleanLiveLaunchConsumeResult.Rejected -> {
                mutableRouteState.value = CleanLivePlayerRouteState.Rejected(consumed.reason.toRouteReason())
                completeRelease()
                return@withLock
            }
        }
        val metadata = mediaSessionMetadata(launch)
        activeLaunch = launch
        activeMetadata = metadata
        createAndTune(launch, metadata, activity, lifecycle, surfaceOwner)
    }

    private suspend fun createAndTune(
        launch: CleanLiveLaunchEntry,
        metadata: CleanMediaSessionMetadata,
        activity: Activity,
        lifecycle: Lifecycle,
        surfaceOwner: FrameLayout,
    ) {
        val created = try {
            hostFactory.create(
                AndroidCleanLiveHostInput(
                    context = appContext,
                    preferenceProfileId = PlaybackProfileId(launch.activeProfileId.toString()),
                    parentScope = ownerScope,
                    activity = activity,
                    lifecycle = lifecycle,
                    surfaceOwner = surfaceOwner,
                ),
            )
        } catch (cancelled: CancellationException) {
            completeRelease()
            throw cancelled
        } catch (_: Exception) {
            mutableRouteState.value =
                CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.HOST_CREATION_FAILED)
            completeRelease()
            return
        }
        host = created
        attachedSurfaceOwner = surfaceOwner

        if (profileSource.activeProfileId() != launch.activeProfileId) {
            rejectAfterRelease(created, CleanLivePlayerRejection.PROFILE_MISMATCH)
            return
        }

        val acceptedGeneration = try {
            created.tune(launch.selection, SessionProfile.FULLSCREEN, metadata)
        } catch (cancelled: CancellationException) {
            releaseCreatedHost(created)
            throw cancelled
        } catch (_: Exception) {
            rejectAfterRelease(created, CleanLivePlayerRejection.TUNE_FAILED)
            return
        }
        if (profileSource.activeProfileId() != launch.activeProfileId) {
            rejectAfterRelease(created, CleanLivePlayerRejection.PROFILE_MISMATCH)
            return
        }

        pendingPlayed = LivePlayedIdentity(
            target = launch.target,
            boundProfileId = launch.playbackProfileId(),
            generation = acceptedGeneration,
        )
        publishReady(created, launch.metadata, launch.origin)
    }

    suspend fun pause() = command { it.pause() }
    suspend fun resume() = command { it.resume() }
    suspend fun retry() = command { it.retry() }

    /**
     * Remote repeats are conflated behind one ViewModel-owned worker. At most one provider-neutral
     * relative lookup and one latest pending direction survive; Compose lifetime never owns an
     * accepted channel command.
     */
    fun requestZap(direction: LiveZapDirection) {
        if (!releaseCompleted && !clearedReleaseLoopStarted) zapRequests.trySend(direction)
    }

    private suspend fun performZap(direction: LiveZapDirection) {
        val basis = ownershipMutex.withLock {
            if (releaseCompleted) return
            val currentHost = host ?: return
            val currentLaunch = activeLaunch ?: return
            if (profileSource.activeProfileId() != currentLaunch.activeProfileId) {
                rejectAfterRelease(currentHost, CleanLivePlayerRejection.PROFILE_MISMATCH)
                return
            }
            ZapBasis(currentHost, currentLaunch)
        }

        val relative = try {
            liveNavigation.relative(
                LiveRelativeRequest(
                    currentContentId = basis.launch.target.contentId,
                    direction = direction,
                    boundProfileId = basis.launch.playbackProfileId(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }

        ownershipMutex.withLock {
            if (
                releaseCompleted ||
                host !== basis.host ||
                activeLaunch !== basis.launch
            ) {
                return@withLock
            }
            val currentHost = basis.host
            val target = when (relative) {
                is LiveRelativeResult.Target -> relative.target
                is LiveRelativeResult.Rejected -> {
                    if (relative.reason == LiveRelativeFailure.PROFILE_CHANGED) {
                        rejectAfterRelease(currentHost, CleanLivePlayerRejection.PROFILE_MISMATCH)
                    }
                    return@withLock
                }
            }
            if (profileSource.activeProfileId() != basis.launch.activeProfileId) {
                rejectAfterRelease(currentHost, CleanLivePlayerRejection.PROFILE_MISMATCH)
                return@withLock
            }
            if (
                target.mediaFingerprint != LiveMediaFingerprint.create(
                    target.selection,
                    basis.launch.playbackProfileId(),
                )
            ) {
                return@withLock
            }

            presentationJob?.cancelAndJoin()
            presentationJob = null
            val nextDisplay = CleanLiveLaunchMetadata.sanitized(target.title)
            val nextLaunch = CleanLiveLaunchEntry(
                target = target,
                activeProfileId = basis.launch.activeProfileId,
                metadata = nextDisplay,
                origin = basis.launch.origin,
            )
            val nextMediaMetadata = mediaSessionMetadata(nextLaunch)
            val acceptedGeneration = try {
                currentHost.zap(
                    selection = target.selection,
                    profile = SessionProfile.FULLSCREEN,
                    metadata = nextMediaMetadata,
                )
            } catch (cancelled: CancellationException) {
                publishReady(currentHost, basis.launch.metadata, basis.launch.origin)
                throw cancelled
            } catch (_: Exception) {
                publishReady(currentHost, basis.launch.metadata, basis.launch.origin)
                return@withLock
            }
            if (profileSource.activeProfileId() != basis.launch.activeProfileId) {
                rejectAfterRelease(currentHost, CleanLivePlayerRejection.PROFILE_MISMATCH)
                return@withLock
            }

            activeLaunch = nextLaunch
            activeMetadata = nextMediaMetadata
            pendingPlayed = LivePlayedIdentity(
                target = target,
                boundProfileId = nextLaunch.playbackProfileId(),
                generation = acceptedGeneration,
            )
            publishReady(currentHost, nextDisplay, nextLaunch.origin)
        }
    }

    /** Returns only after the host's affirmative provider/engine release barrier completes. */
    suspend fun releaseBeforeExit() = withContext(NonCancellable) {
        ownershipMutex.withLock {
            if (releaseCompleted) return@withLock
            val current = host
            if (current != null) {
                try {
                    current.release()
                } catch (cancelled: CancellationException) {
                    mutableRouteState.value =
                        CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.RELEASE_FAILED)
                    throw cancelled
                } catch (error: Exception) {
                    mutableRouteState.value =
                        CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.RELEASE_FAILED)
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

    private suspend fun command(action: suspend (CleanLiveHost) -> Unit) =
        ownershipMutex.withLock {
            if (!releaseCompleted) host?.let { action(it) }
        }

    private fun publishReady(
        current: CleanLiveHost,
        metadata: CleanLiveLaunchMetadata,
        origin: CleanLiveLaunchOrigin,
    ) {
        fun publish(snapshot: PlaybackSnapshot) {
            mutableRouteState.value = CleanLivePlayerRouteState.Ready(
                metadata = metadata,
                origin = origin,
                snapshot = snapshot,
                presentation = LivePlaybackUiPresenter.present(snapshot),
            )
            acceptRenderedVideoEvidence(snapshot)
        }

        val initialSnapshot = current.snapshot.value
        publish(initialSnapshot)
        presentationJob = ownerScope.launch {
            current.snapshot.collect { snapshot -> publish(snapshot) }
        }
    }

    private fun acceptRenderedVideoEvidence(snapshot: PlaybackSnapshot) {
        val pending = pendingPlayed ?: return
        when {
            snapshot.generation == pending.generation && snapshot.progress.renderedVideoFrame -> {
                // Clear before launching persistence: duplicate adapter facts can never enqueue twice.
                pendingPlayed = null
                val predecessor = historyRecordTail
                historyRecordTail = ownerScope.launch {
                    predecessor?.join()
                    try {
                        playedHistory.record(pending)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Recent-channel persistence is best effort and never fails playback.
                    }
                }
            }
            snapshot.generation > pending.generation -> {
                // Retry currently has no accepted-generation acknowledgement. Never infer that a
                // later generation still belongs to this identity.
                pendingPlayed = null
            }
        }
    }

    private suspend fun releaseHostForSurfaceRebind(): Boolean {
        val current = host ?: return true
        presentationJob?.cancelAndJoin()
        presentationJob = null
        pendingPlayed = null
        mutableRouteState.value = CleanLivePlayerRouteState.Initializing
        return try {
            withContext(NonCancellable) { current.release() }
            if (host === current) host = null
            attachedSurfaceOwner = null
            true
        } catch (_: Exception) {
            mutableRouteState.value =
                CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.RELEASE_FAILED)
            false
        }
    }

    private suspend fun rejectAfterRelease(
        created: CleanLiveHost,
        reason: CleanLivePlayerRejection,
    ) {
        try {
            releaseCreatedHost(created)
            mutableRouteState.value = CleanLivePlayerRouteState.Rejected(reason)
        } catch (error: Exception) {
            mutableRouteState.value =
                CleanLivePlayerRouteState.Rejected(CleanLivePlayerRejection.RELEASE_FAILED)
        }
    }

    private suspend fun releaseCreatedHost(created: CleanLiveHost) {
        withContext(NonCancellable) { created.release() }
        if (host === created) host = null
        attachedSurfaceOwner = null
        completeRelease()
    }

    private fun completeRelease() {
        releaseCompleted = true
        zapRequests.close()
        zapWorker.cancel()
        pendingPlayed = null
        presentationJob?.cancel()
        presentationJob = null
        historyRecordTail = null
        host = null
        activeLaunch = null
        activeMetadata = null
        attachedSurfaceOwner = null
        ownerJob.cancel()
    }

    private fun mediaSessionMetadata(launch: CleanLiveLaunchEntry): CleanMediaSessionMetadata =
        CleanMediaSessionMetadata.fromIngress(
            redactedContentFingerprint = launch.mediaFingerprint,
            title = launch.metadata.title,
            subtitle = launch.metadata.subtitle,
            station = launch.metadata.station,
        )

    private fun CleanLiveLaunchEntry.playbackProfileId(): PlaybackProfileId =
        PlaybackProfileId(activeProfileId.toString())

    private data class ZapBasis(
        val host: CleanLiveHost,
        val launch: CleanLiveLaunchEntry,
    )

    private fun CleanLiveLaunchConsumeFailure.toRouteReason(): CleanLivePlayerRejection = when (this) {
        CleanLiveLaunchConsumeFailure.MISSING -> CleanLivePlayerRejection.MISSING
        CleanLiveLaunchConsumeFailure.EXPIRED -> CleanLivePlayerRejection.EXPIRED
        CleanLiveLaunchConsumeFailure.PROFILE_MISMATCH -> CleanLivePlayerRejection.PROFILE_MISMATCH
    }

    private companion object {
        const val INITIAL_CLEAR_RELEASE_BACKOFF_MS = 100L
        const val MAX_CLEAR_RELEASE_BACKOFF_MS = 5_000L
    }
}
