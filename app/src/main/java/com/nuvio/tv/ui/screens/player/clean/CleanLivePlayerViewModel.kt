package com.nuvio.tv.ui.screens.player.clean

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.playback.android.AndroidPlaybackLifecyclePort
import com.nuvio.tv.playback.android.output.AndroidPlaybackOutputController
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.host.CleanLivePlaybackHost
import com.nuvio.tv.playback.host.CleanLiveSurfaceCoordinator
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import com.nuvio.tv.playback.ui.LivePlaybackUiPresenter
import com.nuvio.tv.playback.ui.LivePlaybackUiState
import com.nuvio.tv.playback.wiring.ProductionPlaybackSessionFactory
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

internal interface CleanLiveDestinationHost {
    val snapshot: StateFlow<PlaybackSnapshot>

    suspend fun tune(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    )

    suspend fun pause()
    suspend fun resume()
    suspend fun retry()
    suspend fun release()
}

internal class CleanLiveDestinationHostInput(
    val context: Context,
    val preferenceProfileId: String,
    val parentScope: CoroutineScope,
    val activity: Activity,
    val lifecycle: Lifecycle,
    val surfaceOwner: FrameLayout,
) {
    override fun toString(): String =
        "CleanLiveDestinationHostInput(profileBound=true, surfaceOwnerBound=true)"
}

internal fun interface CleanLiveDestinationHostFactory {
    suspend fun create(input: CleanLiveDestinationHostInput): CleanLiveDestinationHost
}

internal fun interface CleanLiveReleaseRetryWait {
    suspend fun await(delayMs: Long)
}

/**
 * Destination-scoped clean Live TV owner. It is intentionally detached from Compose/navigation
 * until the atomic Search/Library route switch.
 */
@HiltViewModel
internal class CleanLivePlayerViewModel private constructor(
    private val appContext: Context,
    private val launchConsumer: CleanLiveDestinationLaunchConsumer,
    private val profileSource: CleanLiveDestinationProfileSource,
    private val hostFactory: CleanLiveDestinationHostFactory,
    ownerDispatcher: CoroutineDispatcher,
    private val releaseRetryWait: CleanLiveReleaseRetryWait,
) : ViewModel() {
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
        launchStore: CleanLiveLaunchStore,
        profileManager: ProfileManager,
        sessionFactory: ProductionPlaybackSessionFactory,
    ) : this(
        appContext = context.applicationContext,
        launchConsumer = CleanLiveDestinationLaunchConsumer(launchStore::consume),
        profileSource = CleanLiveDestinationProfileSource { profileManager.activeProfileId.value },
        hostFactory = AndroidCleanLiveDestinationHostFactory(sessionFactory),
        ownerDispatcher = Dispatchers.Main.immediate,
        releaseRetryWait = CleanLiveReleaseRetryWait { delay(it) },
    )

    internal constructor(
        context: Context,
        launchConsumer: CleanLiveDestinationLaunchConsumer,
        profileSource: CleanLiveDestinationProfileSource,
        hostFactory: CleanLiveDestinationHostFactory,
        ownerDispatcher: CoroutineDispatcher,
        releaseRetryWait: CleanLiveReleaseRetryWait = CleanLiveReleaseRetryWait {},
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(
        appContext = context.applicationContext,
        launchConsumer = launchConsumer,
        profileSource = profileSource,
        hostFactory = hostFactory,
        ownerDispatcher = ownerDispatcher,
        releaseRetryWait = releaseRetryWait,
    )

    private val ownerJob = SupervisorJob()
    private val ownerScope = CoroutineScope(ownerJob + ownerDispatcher)
    private val ownershipMutex = Mutex()
    private val mutableRouteState =
        MutableStateFlow<CleanLivePlayerRouteState>(CleanLivePlayerRouteState.Initializing)

    val routeState: StateFlow<CleanLivePlayerRouteState> = mutableRouteState.asStateFlow()

    private var initialized = false
    private var releaseCompleted = false
    private var host: CleanLiveDestinationHost? = null
    private var activeLaunch: CleanLiveLaunchEntry? = null
    private var activeMetadata: CleanMediaSessionMetadata? = null
    private var attachedSurfaceOwner: FrameLayout? = null
    private var presentationJob: Job? = null
    private var clearedReleaseLoopStarted = false

    suspend fun initialize(
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
        val metadata = CleanMediaSessionMetadata.fromIngress(
            redactedContentFingerprint = launch.mediaFingerprint,
            title = launch.metadata.title,
            subtitle = launch.metadata.subtitle,
            station = launch.metadata.station,
        )
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
                CleanLiveDestinationHostInput(
                    context = appContext,
                    preferenceProfileId = launch.activeProfileId.toString(),
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

        try {
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

        publishReady(created, launch.metadata, launch.origin)
    }

    suspend fun pause() = command { it.pause() }
    suspend fun resume() = command { it.resume() }
    suspend fun retry() = command { it.retry() }

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

    private suspend fun command(action: suspend (CleanLiveDestinationHost) -> Unit) =
        ownershipMutex.withLock {
            if (!releaseCompleted) host?.let { action(it) }
        }

    private fun publishReady(
        current: CleanLiveDestinationHost,
        metadata: CleanLiveLaunchMetadata,
        origin: CleanLiveLaunchOrigin,
    ) {
        fun ready(snapshot: PlaybackSnapshot, presentation: LivePlaybackUiState) =
            CleanLivePlayerRouteState.Ready(metadata, origin, snapshot, presentation)

        val initialSnapshot = current.snapshot.value
        mutableRouteState.value = ready(initialSnapshot, LivePlaybackUiPresenter.present(initialSnapshot))
        presentationJob = ownerScope.launch {
            current.snapshot.collect { snapshot ->
                mutableRouteState.value = ready(snapshot, LivePlaybackUiPresenter.present(snapshot))
            }
        }
    }

    private suspend fun releaseHostForSurfaceRebind(): Boolean {
        val current = host ?: return true
        presentationJob?.cancelAndJoin()
        presentationJob = null
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
        created: CleanLiveDestinationHost,
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

    private suspend fun releaseCreatedHost(created: CleanLiveDestinationHost) {
        withContext(NonCancellable) { created.release() }
        if (host === created) host = null
        attachedSurfaceOwner = null
        completeRelease()
    }

    private fun completeRelease() {
        releaseCompleted = true
        presentationJob?.cancel()
        presentationJob = null
        host = null
        activeLaunch = null
        activeMetadata = null
        attachedSurfaceOwner = null
        ownerJob.cancel()
    }

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

private class AndroidCleanLiveDestinationHostFactory(
    private val sessionFactory: ProductionPlaybackSessionFactory,
) : CleanLiveDestinationHostFactory {
    override suspend fun create(
        input: CleanLiveDestinationHostInput,
    ): CleanLiveDestinationHost {
        val surfaces = withContext(Dispatchers.Main.immediate) {
            CleanLiveSurfaceCoordinator(
                owner = input.surfaceOwner,
                callbackScope = input.parentScope,
                constructibleModes = PRODUCTION_CONSTRUCTIBLE_SURFACE_MODES,
                secureMedia3SurfaceViewSupported = true,
            )
        }
        val host = CleanLivePlaybackHost.create(
            context = input.context,
            preferenceProfileId = input.preferenceProfileId,
            parentScope = input.parentScope,
            sessionFactory = sessionFactory,
            surfaces = surfaces,
            outputController = AndroidPlaybackOutputController(input.activity),
            lifecycle = AndroidPlaybackLifecyclePort(input.lifecycle),
        )
        return AndroidCleanLiveDestinationHost(host)
    }

    private companion object {
        val PRODUCTION_CONSTRUCTIBLE_SURFACE_MODES = setOf(
            SurfaceMode.SURFACE_VIEW,
            SurfaceMode.TEXTURE_VIEW,
            SurfaceMode.NATIVE_EMBED,
            SurfaceMode.GPU_RENDER,
        )
    }
}

private class AndroidCleanLiveDestinationHost(
    private val host: CleanLivePlaybackHost,
) : CleanLiveDestinationHost {
    override val snapshot: StateFlow<PlaybackSnapshot> = host.snapshot

    override suspend fun tune(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    ) = host.tune(selection, profile, metadata)

    override suspend fun pause() = host.pause()
    override suspend fun resume() = host.resume()
    override suspend fun retry() = host.retry()
    override suspend fun release() = host.release()
}
