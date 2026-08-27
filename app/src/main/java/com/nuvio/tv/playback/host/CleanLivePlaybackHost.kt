package com.nuvio.tv.playback.host

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Looper
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackLifecyclePort
import com.nuvio.tv.playback.core.PlaybackOutputController
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import com.nuvio.tv.playback.mediasession.CleanMediaSessionOwner
import com.nuvio.tv.playback.ui.LivePlaybackUiPresenter
import com.nuvio.tv.playback.ui.LivePlaybackUiState
import com.nuvio.tv.playback.ui.PlaybackSessionController
import com.nuvio.tv.playback.wiring.ProductionPlaybackHost
import com.nuvio.tv.playback.wiring.ProductionPlaybackSessionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun interface CleanMediaSessionOwnerFactory {
    fun create(
        context: Context,
        applicationLooper: Looper,
        parentScope: CoroutineScope,
        controller: PlaybackSessionController,
        metadata: CleanMediaSessionMetadata,
    ): CleanMediaSessionOwner
}

/**
 * One TV live-player host: one child scope, one controller, one surface coordinator, and one
 * release authority. It is intentionally not referenced by production navigation yet.
 */
internal class CleanLivePlaybackHost private constructor(
    private val hostJob: Job,
    private val controller: PlaybackSessionController,
    private val surfaces: CleanLiveSurfaceCoordinator,
    private val releaseAuthority: ReleaseAuthority,
    presentationScope: CoroutineScope,
) {
    private val commandMutex = Mutex()

    @Volatile
    private var released = false

    @Volatile
    private var releaseStarted = false

    val snapshot: StateFlow<PlaybackSnapshot> = controller.snapshot
    val presentation: StateFlow<LivePlaybackUiState> = snapshot
        .map(LivePlaybackUiPresenter::present)
        .stateIn(
            scope = presentationScope,
            started = SharingStarted.Eagerly,
            initialValue = LivePlaybackUiPresenter.present(snapshot.value),
        )

    suspend fun tune(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    ) = withActiveHost {
        requireLive(selection)
        controller.tune(selection, profile)
        releaseAuthority.updateMetadata(metadata)
    }

    suspend fun zap(
        selection: ProviderPlaybackSelection,
        profile: SessionProfile,
        metadata: CleanMediaSessionMetadata,
    ) = withActiveHost {
        requireLive(selection)
        controller.zap(selection, profile)
        releaseAuthority.updateMetadata(metadata)
    }

    suspend fun pause() = withActiveHost(controller::pause)
    suspend fun resume() = withActiveHost(controller::resume)
    suspend fun retry() = withActiveHost(controller::retry)
    suspend fun changeProfile(profile: SessionProfile) = withActiveHost {
        controller.changeProfile(profile)
    }
    suspend fun stop() = withActiveHost(controller::stop)

    /**
     * The selected authority first waits for the affirmative session/provider barrier. Only then
     * may the coordinator remove its child and the per-host scope be cancelled.
     */
    suspend fun release() = withContext(NonCancellable) {
        commandMutex.withLock {
            if (released) return@withLock
            releaseStarted = true
            releaseAuthority.release()
            check(surfaces.disposeAfterSessionRelease()) {
                "Clean playback surfaces still have an attached lease after session release"
            }
            released = true
            hostJob.cancel()
        }
    }

    private suspend fun <T> withActiveHost(block: suspend () -> T): T = commandMutex.withLock {
        check(!releaseStarted) { "Clean live playback host is releasing or released" }
        block()
    }

    private fun requireLive(selection: ProviderPlaybackSelection) {
        require(selection.contentType == ContentType.LIVE) {
            "Clean live playback host accepts live selections only"
        }
    }

    private sealed interface ReleaseAuthority {
        fun updateMetadata(metadata: CleanMediaSessionMetadata)
        suspend fun release()

        class MediaSession(
            private val owner: CleanMediaSessionOwner,
        ) : ReleaseAuthority {
            override fun updateMetadata(metadata: CleanMediaSessionMetadata) {
                // System metadata is optional; a platform facade failure must not stop Live TV.
                runCatching { owner.updateMetadata(metadata) }
            }

            override suspend fun release() = owner.release()
        }

        class ControllerFallback(
            private val controller: PlaybackSessionController,
        ) : ReleaseAuthority {
            override fun updateMetadata(metadata: CleanMediaSessionMetadata) = Unit
            override suspend fun release() = controller.release()
        }
    }

    companion object {
        private val neutralMetadata = CleanMediaSessionMetadata.fromIngress(
            redactedContentFingerprint = "",
            title = "Tuvora",
        )

        private val productionMediaSessionFactory = CleanMediaSessionOwnerFactory {
                context,
                applicationLooper,
                parentScope,
                controller,
                metadata,
            ->
            CleanMediaSessionOwner.create(
                context = context,
                applicationLooper = applicationLooper,
                parentScope = parentScope,
                controller = controller,
                metadata = metadata,
            )
        }

        suspend fun create(
            context: Context,
            preferenceProfileId: PlaybackProfileId,
            parentScope: CoroutineScope,
            sessionFactory: ProductionPlaybackSessionFactory,
            surfaces: CleanLiveSurfaceCoordinator,
            outputController: PlaybackOutputController,
            lifecycle: PlaybackLifecyclePort,
            previewViewport: VideoDimensions? = null,
            resourceBudget: ResourceBudget = ResourceBudget(),
            routedAudioDevice: () -> AudioDeviceInfo? = { null },
            applicationLooper: Looper = Looper.getMainLooper(),
            applicationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
            mediaSessionFactory: CleanMediaSessionOwnerFactory = productionMediaSessionFactory,
        ): CleanLivePlaybackHost {
            val parentJob = parentScope.coroutineContext[Job]
            val hostJob = SupervisorJob(parentJob)
            val hostScope = CoroutineScope(parentScope.coroutineContext + hostJob)
            val productionHost = ProductionPlaybackHost(
                parentScope = hostScope,
                media3SurfaceHost = surfaces.media3SurfaceHost,
                mpvSurfaceHost = surfaces.mpvSurfaceHost,
                surfaceCapabilities = surfaces.capabilities,
                outputController = outputController,
                lifecycle = lifecycle,
                previewViewport = previewViewport,
                resourceBudget = resourceBudget,
                routedAudioDevice = routedAudioDevice,
            )
            val controller = try {
                sessionFactory.create(preferenceProfileId, productionHost)
            } catch (cancelled: CancellationException) {
                hostScope.cancel()
                throw cancelled
            } catch (error: Exception) {
                hostScope.cancel()
                throw error
            }
            if (!surfaces.bindController(controller)) {
                cleanupFailedCreation(controller, surfaces, hostScope, disposeSurfaces = false)
                error("Clean playback surface coordinator rejected its controller")
            }
            val hostingStarted = try {
                surfaces.startHosting()
            } catch (cancelled: CancellationException) {
                cleanupFailedCreation(controller, surfaces, hostScope)
                throw cancelled
            } catch (error: Exception) {
                cleanupFailedCreation(controller, surfaces, hostScope)
                throw error
            }
            if (!hostingStarted) {
                cleanupFailedCreation(controller, surfaces, hostScope)
                error("Clean playback surface coordinator could not start")
            }

            val mediaSessionOwner = try {
                withContext(applicationDispatcher) {
                    mediaSessionFactory.create(
                        context = context.applicationContext,
                        applicationLooper = applicationLooper,
                        parentScope = hostScope,
                        controller = controller,
                        metadata = neutralMetadata,
                    )
                }
            } catch (cancelled: CancellationException) {
                cleanupFailedCreation(controller, surfaces, hostScope)
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val authority = mediaSessionOwner
                ?.let { ReleaseAuthority.MediaSession(it) }
                ?: ReleaseAuthority.ControllerFallback(controller)
            return CleanLivePlaybackHost(
                hostJob = hostJob,
                controller = controller,
                surfaces = surfaces,
                releaseAuthority = authority,
                presentationScope = hostScope,
            )
        }

        private suspend fun cleanupFailedCreation(
            controller: PlaybackSessionController,
            surfaces: CleanLiveSurfaceCoordinator,
            hostScope: CoroutineScope,
            disposeSurfaces: Boolean = true,
        ) = withContext(NonCancellable) {
            // Fail closed: never detach surfaces or cancel ownership before the barrier is proven.
            controller.release()
            if (disposeSurfaces) {
                check(surfaces.disposeAfterSessionRelease()) {
                    "Clean playback surfaces still have an attached lease after session release"
                }
            }
            hostScope.cancel()
        }
    }
}
