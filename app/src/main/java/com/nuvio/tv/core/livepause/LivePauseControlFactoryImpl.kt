package com.nuvio.tv.core.livepause

import android.content.Context
import android.os.SystemClock
import com.nuvio.tv.core.contracts.LivePauseControl
import com.nuvio.tv.core.contracts.LivePauseControlFactory
import com.nuvio.tv.core.contracts.LivePauseControlRequest
import com.nuvio.tv.core.contracts.LivePausePhase
import com.nuvio.tv.core.contracts.LivePauseProblem
import com.nuvio.tv.core.contracts.LivePauseView
import com.nuvio.tv.core.contracts.PlayerMemoryBudget
import com.nuvio.tv.core.iptv.playback.ProviderLinkReusePolicy
import com.nuvio.tv.playback.core.DEFAULT_STREAM_USER_AGENT
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.ProviderPlaybackResolver
import com.nuvio.tv.playback.core.ProviderPlaybackResolverFactory
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderResolutionContext
import com.nuvio.tv.playback.core.ProviderResolutionTrigger
import com.nuvio.tv.playback.core.assembledHttpHeaders
import com.nuvio.tv.playback.core.putReplacingCaseInsensitive
import com.nuvio.tv.playback.media3.ApplicationDnsResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Builds one [LivePauseController] per player instance (the factory itself holds no per-player state).
 * Links come from the app's provider resolver, never from the player's spent URL; the capture's HTTP
 * is the app's OkHttp stack with the playlist's DNS and the resolver's headers (User-Agent included,
 * decided by StreamUserAgentPolicy inside the resolver).
 */
@Singleton
internal class LivePauseControlFactoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolvers: ProviderPlaybackResolverFactory,
    private val memory: PlayerMemoryBudget,
    private val dns: ApplicationDnsResolver,
) : LivePauseControlFactory {
    private val root = File(context.cacheDir, "live-pause")
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().build()
    private val swept = AtomicBoolean(false)

    override fun create(request: LivePauseControlRequest): LivePauseControl? {
        if (swept.compareAndSet(false, true)) {
            // A capture directory untouched for 6 h belongs to a process that died.
            cleanupScope.launch { FileLiveSegmentStore.sweepStale(root, System.currentTimeMillis()) }
        }
        val resolver = resolvers.create(request.profileId)
        val selection = request.selection
        val demux = memory.demuxerBytes()
        val normal = LivePausePolicy.EngineCachePlan(demux.maxBytes, demux.maxBackBytes, cacheSecs = 0L)
        // The low tier never raises the caches (it has none to spare); the others may hold ~3x the normal plan.
        val budget = if (memory.isLowMemoryTier()) demux.maxBytes + demux.maxBackBytes else MID_HIGH_ENGINE_BUDGET_BYTES
        val env = LivePauseEnvironment(
            facts = request.engine::facts,
            lowMemoryTier = memory::isLowMemoryTier,
            freeBytes = { root.apply { mkdirs() }.usableSpace },
            engineMemoryBudgetBytes = { budget },
            normalEnginePlan = { normal },
            singleUseLink = { ProviderLinkReusePolicy.isSingleUse(selection.sourceType, selection.contentType) },
        )
        val controller = LivePauseController(
            instanceId = request.instanceId,
            parentScope = request.scope,
            engine = request.engine,
            captureFactory = OkHttpLiveCaptureFactory(
                root = root,
                baseClient = http,
                dnsFor = { key -> dns.resolve(key) },
                io = Dispatchers.IO,
                cleanupScope = cleanupScope,
            ),
            minter = LiveLinkMinter { mint(resolver, selection) },
            env = env,
            monotonicMs = { SystemClock.elapsedRealtime() },
            wallMs = { System.currentTimeMillis() },
        )
        return ControllerControl(controller, request.scope)
    }

    private suspend fun mint(resolver: ProviderPlaybackResolver, selection: ProviderPlaybackSelection): LiveLink? =
        when (val resolved = resolver.resolve(selection, ProviderResolutionContext(ProviderResolutionTrigger.RECOVERY))) {
            is PlaybackResult.Success -> {
                val req = resolved.value.request
                val headers = req.assembledHttpHeaders()
                headers.putReplacingCaseInsensitive("User-Agent", req.userAgent ?: DEFAULT_STREAM_USER_AGENT)
                LiveLink(req.url, headers, req.applicationDnsKey)
            }
            is PlaybackResult.Failure -> null
        }

    private class ControllerControl(
        private val controller: LivePauseController,
        scope: CoroutineScope,
    ) : LivePauseControl {
        override val view: StateFlow<LivePauseView> =
            controller.state.map { toView(it) }.stateIn(scope, SharingStarted.Eagerly, LivePauseView.OFF)

        override fun onPause() = controller.onPause()
        override fun onPlay() = controller.onPlay()
        override fun seekInWindow(positionMs: Long) = controller.seekInWindow(positionMs)
        override fun goLive() = controller.goLive()
        override fun retry() = controller.retry()
        override fun onChannelChanged() = controller.onChannelChanged()
        override fun onLiveStarted() = controller.onLiveStarted()
        override fun dispose() = controller.dispose()
    }

    private companion object {
        const val MID_HIGH_ENGINE_BUDGET_BYTES = 192L * 1024 * 1024

        fun toView(state: LivePauseState): LivePauseView = when (state) {
            LivePauseState.Off -> LivePauseView.OFF
            is LivePauseState.Preparing -> LivePauseView(
                phase = LivePausePhase.PREPARING,
                paused = true,
                preparingDeadlineAtMs = state.status.deadlineAtMs,
            )
            is LivePauseState.Shifted -> LivePauseView(
                phase = LivePausePhase.SHIFTED,
                paused = state.paused,
                behindLiveMs = state.behindLiveMs,
                windowStartMs = state.windowStartMs,
                windowEndMs = state.windowEndMs,
                positionMs = state.positionMs,
                bufferFull = state.bufferFull,
            )
            is LivePauseState.Failed -> LivePauseView(
                phase = LivePausePhase.FAILED,
                paused = true,
                problem = when (state.reason) {
                    LivePauseFailure.NO_SPACE -> LivePauseProblem.NO_SPACE
                    LivePauseFailure.PROVIDER_REFUSED -> LivePauseProblem.PROVIDER_REFUSED
                    LivePauseFailure.STALLED -> LivePauseProblem.STALLED
                    LivePauseFailure.SEQUENCE_RESET -> LivePauseProblem.SEQUENCE_RESET
                    LivePauseFailure.UNSUPPORTED_STREAM -> LivePauseProblem.UNSUPPORTED_STREAM
                    LivePauseFailure.TIMED_OUT -> LivePauseProblem.TIMED_OUT
                },
            )
        }
    }
}
