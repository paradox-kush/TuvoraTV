package com.nuvio.tv.playback.media3

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.ApplicationDnsKey
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackEngineState
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.PlaybackTimelineFacts
import com.nuvio.tv.playback.core.PlaybackTrackCatalog
import com.nuvio.tv.playback.core.PlaybackTrackDescriptor
import com.nuvio.tv.playback.core.PlaybackTrackId
import com.nuvio.tv.playback.core.PlaybackTrackType
import com.nuvio.tv.playback.core.ExternalSubtitleId
import com.nuvio.tv.playback.core.ExternalSubtitleResolver
import com.nuvio.tv.playback.core.RestorableTrackSelection
import com.nuvio.tv.playback.core.VodRestorationCheckpoint
import com.nuvio.tv.playback.core.ProxyMode
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy
import com.nuvio.tv.playback.core.TlsPolicy
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** Maps an opaque request selector to the application-owned resolver for that exact playlist. */
internal fun interface ApplicationDnsResolver {
    fun resolve(key: ApplicationDnsKey): Dns?
}

internal fun resolveApplicationDns(
    request: Media3NetworkPlan,
    resolver: ApplicationDnsResolver,
): PlaybackResult<Dns?> {
    if (request.dnsPolicy == DnsPolicy.SYSTEM) return PlaybackResult.Success(null)
    val key = request.applicationDnsKey ?: return applicationDnsFailure()
    val dns = try {
        resolver.resolve(key)
    } catch (_: Exception) {
        null
    }
    return dns?.let { PlaybackResult.Success(it) } ?: applicationDnsFailure()
}

private fun applicationDnsFailure(): PlaybackResult.Failure = failure(
    FailureCode.NETWORK_UNREACHABLE,
    FailureDomain.NETWORK,
    FailurePhase.ENGINE_START,
    Retryability.RETRYABLE_WITH_FRESH_REQUEST,
)

/** Builds Media3 backends from the application OkHttp stack without importing legacy playback. */
@UnstableApi
internal class AndroidMedia3BackendFactory(
    context: Context,
    private val sharedHttpClient: OkHttpClient,
    private val applicationDnsResolver: ApplicationDnsResolver = ApplicationDnsResolver { null },
    /** Declared trust semantics of [sharedHttpClient]; STRICT forbids trust-all/provider exceptions. */
    private val sharedClientTlsPolicy: TlsPolicy = TlsPolicy.STRICT,
    private val playerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val releaseController: Media3ReleaseController = ForkMedia3ReleaseController,
    private val externalSubtitleResolver: ExternalSubtitleResolver = ExternalSubtitleResolver { null },
) : Media3BackendFactory {
    private val applicationContext = context.applicationContext

    override suspend fun create(plan: Media3AdapterPlan): PlaybackResult<Media3Backend> =
        withContext(playerDispatcher) {
            if (plan.request.drm?.schemeUuid == C.UUID_NIL) {
                return@withContext failure(
                    FailureCode.DRM_UNSUPPORTED,
                    FailureDomain.DRM,
                    FailurePhase.ENGINE_START,
                    Retryability.FATAL,
                    deterministic = true,
                )
            }
            val applicationDns = when (val result = resolveApplicationDns(plan.request, applicationDnsResolver)) {
                is PlaybackResult.Success -> result.value
                is PlaybackResult.Failure -> return@withContext result
            }
            if (plan.request.tlsPolicy == TlsPolicy.STRICT && sharedClientTlsPolicy != TlsPolicy.STRICT) {
                return@withContext failure(
                    FailureCode.TLS_HANDSHAKE_FAILED,
                    FailureDomain.TLS,
                    FailurePhase.ENGINE_START,
                    Retryability.FATAL,
                    deterministic = true,
                )
            }
            // The common contract asks for these effects, but Media3 exposes no reliable generic
            // in-place implementation. Refuse them instead of silently ignoring user intent.
            media3UnsupportedProcessingFailure(plan, FailurePhase.ENGINE_START)?.let {
                return@withContext it
            }

            runCatching { build(plan, applicationDns) }.fold(
                onSuccess = { PlaybackResult.Success(it) },
                onFailure = {
                    failure(
                        FailureCode.UNKNOWN,
                        FailureDomain.UNKNOWN,
                        FailurePhase.ENGINE_START,
                        Retryability.HANDOFF_ELIGIBLE,
                    )
                },
            )
        }

    private fun build(plan: Media3AdapterPlan, applicationDns: Dns?): Media3Backend {
        val clientBuilder = sharedHttpClient.newBuilder()
            .followRedirects(plan.request.followRedirects)
            .followSslRedirects(plan.request.followRedirects)
            // Media3/session owns retry. OkHttp may recover a broken pooled socket, but does not
            // replay a failed tune as an adapter recovery policy.
            .retryOnConnectionFailure(plan.request.retryConnectionFailures)
            .connectTimeout(plan.request.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(plan.request.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        plan.request.callTimeoutMs?.let { timeout ->
            clientBuilder.callTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
        }
        when (plan.request.proxyMode) {
            ProxyMode.SYSTEM -> Unit
            ProxyMode.DIRECT -> clientBuilder.proxy(Proxy.NO_PROXY)
            ProxyMode.HTTP -> {
                val proxy = requireNotNull(plan.request.httpProxy)
                clientBuilder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
                if (proxy.username != null && proxy.password != null) {
                    val credential = Credentials.basic(proxy.username.value, proxy.password.value)
                    clientBuilder.proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") == credential) {
                            null
                        } else {
                            response.request.newBuilder().header("Proxy-Authorization", credential).build()
                        }
                    }
                }
            }
        }
        if (plan.request.dnsPolicy == DnsPolicy.SHARED_APPLICATION_RESOLVER) {
            clientBuilder.dns(requireNotNull(applicationDns))
        }
        val configuredClient = clientBuilder.build()
        val mediaClientBuilder = configuredClient.newBuilder()
        val authorization = plan.request.headers.entries
            .firstOrNull { (name, _) -> name.equals("Authorization", ignoreCase = true) }
            ?.value
        val originUrl = plan.request.url.toHttpUrlOrNull()
        if (authorization != null) {
            mediaClientBuilder.addNetworkInterceptor(
                Media3AuthorizationInterceptor(
                    originUrl = originUrl,
                    preserveAcrossOrigins = plan.request.preserveAuthorizationAcrossHosts,
                    authorization = authorization,
                ),
            )
        }
        val callRegistry = TrackingCallRegistry()
        val mediaCalls = TrackingCallFactory(mediaClientBuilder.build(), callRegistry)
        val byteProgress = Media3ByteProgressSignal()
        val http = OkHttpDataSource.Factory(mediaCalls).apply {
            setDefaultRequestProperties(plan.request.headers)
            setTransferListener(byteProgress)
        }
        val upstream = DefaultDataSource.Factory(applicationContext, http)
        val drmPlan = plan.request.drm
        // DRM is deliberately isolated from stream defaults and stream child-origin policy.
        // Its factory carries only license request headers over the same configured/tracked stack.
        val drmCalls = TrackingCallFactory(configuredClient, callRegistry)
        val drmHttp = OkHttpDataSource.Factory(drmCalls).apply {
            setDefaultRequestProperties(drmDefaultRequestHeaders(plan.request))
        }
        val drmProvider = DefaultDrmSessionManagerProvider().apply {
            setDrmHttpDataSourceFactory(drmHttp)
        }
        val extractors = DefaultExtractorsFactory().apply {
            if (plan.request.confirmedRawTransportStream) {
                setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
                setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM,
                )
            }
        }
        val sourceFactory = DefaultMediaSourceFactory(upstream, extractors).apply {
            setDrmSessionManagerProvider(drmProvider)
            if (plan.request.transientLoadRetryPolicy == TransientLoadRetryPolicy.SESSION_ONLY) {
                setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            }
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                plan.buffer.minimumMs,
                plan.buffer.maximumMs,
                plan.buffer.playbackMs,
                plan.buffer.rebufferMs,
            )
            .build()
        val trackSelector = DefaultTrackSelector(applicationContext).apply {
            setParameters(buildTrackParameters(parameters.buildUpon(), plan).build())
        }
        val codecSelector = when (plan.decoderMode) {
            DecoderMode.HARDWARE -> MediaCodecSelector { mime, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { info ->
                    !MimeTypes.isVideo(mime) || info.hardwareAccelerated
                }
            }
            DecoderMode.SOFTWARE -> MediaCodecSelector { mime, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filter { info ->
                    !MimeTypes.isVideo(mime) || info.softwareOnly
                }
            }
        }
        val renderers = ContractRenderersFactory(
            applicationContext,
            forcePcm = plan.audioMode == AudioMode.DECODE,
        )
            .setExtensionRendererMode(extensionRendererModeFor(plan))
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(plan.decoderFallback)
        val player = ExoPlayer.Builder(applicationContext, renderers)
            .setMediaSourceFactory(sourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setReleaseTimeoutMs(GRACEFUL_RELEASE_TIMEOUT_MS)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            false,
        )
        player.trackSelectionParameters = buildTrackParameters(
            player.trackSelectionParameters.buildUpon(),
            plan,
        ).build()
        player.skipSilenceEnabled = plan.skipSilence
        val mediaItem = mediaItemFor(plan)
        return AndroidMedia3Backend(
            player,
            mediaItem,
            callRegistry,
            byteProgress,
            playerDispatcher,
            releaseController,
            plan,
            externalSubtitleResolver,
        )
    }

    private fun buildTrackParameters(
        builder: TrackSelectionParameters.Builder,
        plan: Media3AdapterPlan,
    ): TrackSelectionParameters.Builder = builder.apply {
        val tracks = plan.tracks
        if (tracks.viewportWidth != null && tracks.viewportHeight != null) {
            setViewportSize(tracks.viewportWidth, tracks.viewportHeight, true)
        } else {
            clearViewportSizeConstraints()
        }
        if (tracks.maximumVideoWidth != null && tracks.maximumVideoHeight != null) {
            setMaxVideoSize(tracks.maximumVideoWidth, tracks.maximumVideoHeight)
        } else {
            setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        setMaxVideoBitrate(tracks.maximumVideoBitrate ?: Int.MAX_VALUE)
        setPreferredAudioLanguage(tracks.preferredAudioLanguage)
        setPreferredTextLanguage(tracks.preferredSubtitleLanguage)
        setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !tracks.subtitlesEnabled)
        setAudioOffloadPreferences(
            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(
                    if (plan.audioMode == AudioMode.OFFLOAD) {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_REQUIRED
                    } else {
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                    },
                )
                .build(),
        )
    }

    private companion object {
        const val GRACEFUL_RELEASE_TIMEOUT_MS = 1_000L
    }
}

internal fun drmDefaultRequestHeaders(request: Media3NetworkPlan): Map<String, String> =
    request.drm?.headers.orEmpty()

/**
 * Extension ordering is resolved from the selected graph, never from legacy decoder-priority
 * settings: software prefers extension decoders; hardware admits them only as an allowed fallback.
 */
@UnstableApi
internal fun extensionRendererModeFor(plan: Media3AdapterPlan): Int = when {
    plan.decoderMode == DecoderMode.SOFTWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    plan.decoderFallback -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
}

private fun okhttp3.HttpUrl.sameOrigin(other: okhttp3.HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

internal class Media3AuthorizationInterceptor(
    private val originUrl: okhttp3.HttpUrl?,
    private val preserveAcrossOrigins: Boolean,
    private val authorization: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val outgoing = when {
            !preserveAcrossOrigins && originUrl != null && !chain.request().url.sameOrigin(originUrl) -> {
                chain.request().newBuilder().removeHeader("Authorization").build()
            }
            preserveAcrossOrigins && chain.request().header("Authorization") == null -> {
                chain.request().newBuilder().header("Authorization", authorization).build()
            }
            else -> chain.request()
        }
        return chain.proceed(outgoing)
    }
}

/** Must be backed by pinned-fork APIs exposing the original internal release acknowledgement. */
internal interface Media3ReleaseController {
    fun releaseWithProof(player: ExoPlayer): Boolean
    fun awaitReleaseWithProof(player: ExoPlayer): Boolean
}

/** Pinned Nuvio fork extensions initiate once, then await that same teardown without re-entry. */
private object ForkMedia3ReleaseController : Media3ReleaseController {
    override fun releaseWithProof(player: ExoPlayer): Boolean = player.releaseWithResult()
    override fun awaitReleaseWithProof(player: ExoPlayer): Boolean = player.awaitReleaseWithResult()
}

/** Ensures a timed-out graceful release is awaited rather than initiated a second time. */
internal class Media3ReleaseProofGate(
    private val initiateRelease: () -> Boolean,
    private val awaitRelease: () -> Boolean,
) {
    var proven: Boolean = false
        private set
    var initiated: Boolean = false
        private set

    fun initiate(): Boolean {
        if (proven) return true
        if (initiated) return false
        initiated = true
        proven = initiateRelease()
        return proven
    }

    fun await(): Boolean {
        if (proven) return true
        check(initiated) { "Release must be initiated before awaiting proof" }
        proven = awaitRelease()
        return proven
    }

    /**
     * Waits again on the same release condition without re-entering ExoPlayer.release(). Some TV
     * codec implementations acknowledge teardown after more than one Media3 release-timeout
     * window, especially after rejecting an oversized hardware-decoder profile.
     */
    fun awaitUpTo(maxAttempts: Int): Boolean {
        require(maxAttempts > 0) { "Release await attempts must be positive" }
        repeat(maxAttempts) {
            if (await()) return true
        }
        return false
    }
}

@UnstableApi
private class ContractRenderersFactory(
    context: Context,
    private val forcePcm: Boolean,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        if (!forcePcm) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
        }
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
    }
}

@UnstableApi
private class AndroidMedia3Backend(
    private val player: ExoPlayer,
    private var mediaItem: MediaItem,
    private val calls: TrackingCallRegistry,
    private val byteProgress: Media3ByteProgressSignal,
    private val dispatcher: CoroutineDispatcher,
    private val releaseController: Media3ReleaseController,
    private var plan: Media3AdapterPlan,
    private val externalSubtitleResolver: ExternalSubtitleResolver,
) : Media3Backend {
    private val _events = MutableSharedFlow<Media3BackendEvent>(extraBufferCapacity = 64)
    override val events: Flow<Media3BackendEvent> = _events.asSharedFlow()
    private var surface: Media3SurfaceLease? = null
    private val firstAudioReported = AtomicBoolean(false)
    private val firstVideoReported = AtomicBoolean(false)
    private var terminalSuppressed = false
    private var released = false
    private var playerReleased = false
    private val factScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var timelineJob: Job? = null
    private var trackRevision = 0L
    private var trackReferences: Map<PlaybackTrackId, Media3TrackReference> = emptyMap()
    private var currentTrackCatalog = PlaybackTrackCatalog()
    private var pendingRestoration: VodRestorationCheckpoint? = null
    private val releaseGate = Media3ReleaseProofGate(
        initiateRelease = { releaseController.releaseWithProof(player) },
        awaitRelease = { releaseController.awaitReleaseWithProof(player) },
    )

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _events.tryEmit(Media3BackendEvent.BufferingStarted)
                Player.STATE_READY -> _events.tryEmit(Media3BackendEvent.BufferingEnded)
            }
            reportState()
            if (playbackState == Player.STATE_ENDED && !terminalSuppressed) {
                _events.tryEmit(Media3BackendEvent.Ended)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            reportState()
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            reportState()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val groups = tracks.groups
            _events.tryEmit(
                Media3BackendEvent.TracksAvailable(
                    hasVideo = groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 },
                    audioTrackCount = groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length },
                    subtitleTrackCount = groups.filter { it.type == C.TRACK_TYPE_TEXT }.sumOf { it.length },
                ),
            )
            publishTrackCatalog(tracks)
            restoreTrackSelectionIfReady()
        }

        override fun onPlayerError(error: PlaybackException) {
            reportState()
            if (!terminalSuppressed) {
                _events.tryEmit(Media3BackendEvent.Failed(Media3FailureMapper.map(error)))
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _events.tryEmit(Media3BackendEvent.VideoSizeChanged(videoSize.width, videoSize.height))
            }
        }
    }
    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            _events.tryEmit(Media3BackendEvent.VideoDecoderInitialized(decoderName))
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            media3VideoFormatFacts(format).forEach { _events.tryEmit(it) }
        }

        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            if (firstAudioReported.compareAndSet(false, true)) _events.tryEmit(Media3BackendEvent.FirstAudio)
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            if (firstVideoReported.compareAndSet(false, true)) _events.tryEmit(Media3BackendEvent.FirstVideoFrame)
        }
    }

    init {
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        byteProgress.bind { _events.tryEmit(Media3BackendEvent.BytesReceived) }
    }

    private fun reportState() {
        val state = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackEngineState.BUFFERING
            Player.STATE_READY -> PlaybackEngineState.READY
            Player.STATE_ENDED -> PlaybackEngineState.ENDED
            else -> PlaybackEngineState.IDLE
        }
        _events.tryEmit(Media3BackendEvent.StateObserved(state, player.playWhenReady, player.isLoading))
    }

    override suspend fun attachSurface(lease: Media3SurfaceLease): PlaybackResult<Unit> = withContext(dispatcher) {
        if (released || surface != null) return@withContext backendFailure(FailurePhase.SURFACE_ATTACHMENT)
        runCatching { lease.attach(player) }.fold(
            onSuccess = { surface = lease; PlaybackResult.Success(Unit) },
            onFailure = { backendFailure(FailurePhase.SURFACE_ATTACHMENT, FailureCode.SURFACE_LOST) },
        )
    }

    override suspend fun start(
        paused: Boolean,
        startPositionMs: Long,
        playbackRate: Float,
        restorationCheckpoint: VodRestorationCheckpoint?,
    ): PlaybackResult<Unit> = withContext(dispatcher) {
        if (released || surface == null) return@withContext backendFailure(FailurePhase.ENGINE_START)
        runCatching {
            terminalSuppressed = false
            pendingRestoration = restorationCheckpoint
            player.setMediaItem(mediaItem)
            if (startPositionMs > 0) player.seekTo(startPositionMs)
            player.playbackParameters = PlaybackParameters(playbackRate)
            player.prepare()
            player.playWhenReady = !paused
            _events.tryEmit(Media3BackendEvent.PlaybackRateChanged(playbackRate))
            startTimelineFacts()
        }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { backendFailure(FailurePhase.ENGINE_START) },
        )
    }

    override suspend fun setPaused(paused: Boolean): PlaybackResult<Unit> = withContext(dispatcher) {
        runCatching { player.playWhenReady = !paused }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { backendFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun seekTo(positionMs: Long): PlaybackResult<Unit> = withContext(dispatcher) {
        if (released || positionMs < 0) return@withContext backendFailure(FailurePhase.PLAYBACK)
        runCatching {
            player.seekTo(positionMs)
            publishTimelineFacts()
        }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { backendFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun setPlaybackRate(rate: Float): PlaybackResult<Unit> = withContext(dispatcher) {
        if (released || !rate.isFinite() || rate !in 0.25f..4f) {
            return@withContext backendFailure(FailurePhase.PLAYBACK)
        }
        runCatching {
            player.playbackParameters = PlaybackParameters(rate)
            _events.tryEmit(Media3BackendEvent.PlaybackRateChanged(rate))
        }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { backendFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun selectAudioTrack(trackId: PlaybackTrackId): PlaybackResult<Unit> =
        selectTrack(trackId, PlaybackTrackType.AUDIO)

    override suspend fun selectSubtitleTrack(trackId: PlaybackTrackId): PlaybackResult<Unit> =
        selectTrack(trackId, PlaybackTrackType.SUBTITLE)

    private suspend fun selectTrack(
        trackId: PlaybackTrackId,
        type: PlaybackTrackType,
    ): PlaybackResult<Unit> = withContext(dispatcher) {
        val reference = trackReferences[trackId]
            ?.takeIf { it.type == type }
            ?: return@withContext backendFailure(FailurePhase.PLAYBACK)
        runCatching {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(reference.trackType, false)
                .setOverrideForType(
                    TrackSelectionOverride(reference.group.mediaTrackGroup, reference.trackIndex),
                )
                .build()
            publishTrackCatalog(player.currentTracks)
        }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { backendFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun setSubtitlesEnabled(enabled: Boolean): PlaybackResult<Unit> =
        withContext(dispatcher) {
            runCatching {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
                    .build()
                publishTrackCatalog(player.currentTracks)
            }.fold(
                onSuccess = { PlaybackResult.Success(Unit) },
                onFailure = { backendFailure(FailurePhase.PLAYBACK) },
            )
        }

    override suspend fun attachExternalSubtitle(
        subtitleId: ExternalSubtitleId,
    ): PlaybackResult<Unit> = withContext(dispatcher) {
        if (released) return@withContext backendFailure(FailurePhase.PLAYBACK)
        val registration = externalSubtitleResolver.resolve(subtitleId)
            ?: return@withContext backendFailure(
                FailurePhase.PLAYBACK,
                FailureCode.NO_ELIGIBLE_GRAPH,
            )
        runCatching {
            val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(registration.uri))
                .setMimeType(registration.mimeType)
                .apply { registration.language?.let(::setLanguage) }
                .apply { registration.label?.let(::setLabel) }
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val existing = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
            mediaItem = mediaItem.buildUpon()
                .setSubtitleConfigurations(existing + subtitle)
                .build()

            // Media3 represents side-loaded subtitles on MediaItem. Rebuild that source once while
            // preserving VOD state; this is not a retry and does not select another graph.
            val positionMs = player.currentPosition.coerceAtLeast(0)
            val playWhenReady = player.playWhenReady
            player.setMediaItem(mediaItem, positionMs)
            player.prepare()
            player.playWhenReady = playWhenReady
        }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { backendFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun apply(plan: Media3AdapterPlan): PlaybackResult<Unit> = withContext(dispatcher) {
        if (released) return@withContext backendFailure(FailurePhase.PLAYBACK)
        media3UnsupportedProcessingFailure(plan, FailurePhase.PLAYBACK)?.let {
            return@withContext it
        }
        runCatching {
            player.trackSelectionParameters = applyMedia3RuntimeTrackPlan(
                player.trackSelectionParameters,
                plan.tracks,
            )
            player.skipSilenceEnabled = plan.skipSilence
            this@AndroidMedia3Backend.plan = plan
        }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { backendFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun setVolume(volume: Float): PlaybackResult<Unit> = withContext(dispatcher) {
        runCatching { player.volume = volume.coerceIn(0f, 1f) }.fold(
            onSuccess = { PlaybackResult.Success(Unit) },
            onFailure = { backendFailure(FailurePhase.PLAYBACK) },
        )
    }

    override suspend fun detachSurface(): PlaybackResult<Unit> = withContext(dispatcher) {
        val lease = surface ?: return@withContext PlaybackResult.Success(Unit)
        val detached = runCatching { lease.detach(player) }.getOrDefault(false)
        if (detached) {
            surface = null
            PlaybackResult.Success(Unit)
        } else {
            backendFailure(FailurePhase.RELEASE, FailureCode.SURFACE_LOST)
        }
    }

    override suspend fun stopSource(): PlaybackResult<Unit> = withContext(dispatcher) {
        if (released) return@withContext PlaybackResult.Success(Unit)
        terminalSuppressed = true
        timelineJob?.cancel()
        timelineJob = null
        runCatching {
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            calls.cancelAll()
        }.getOrElse { return@withContext backendFailure(FailurePhase.RELEASE) }
        if (!calls.awaitIdle(NETWORK_HARD_ABORT_TIMEOUT_MS)) {
            return@withContext backendFailure(FailurePhase.RELEASE, FailureCode.NETWORK_TIMEOUT)
        }
        PlaybackResult.Success(Unit)
    }

    override suspend fun replaceSource(
        plan: Media3AdapterPlan,
        paused: Boolean,
        startPositionMs: Long,
        playbackRate: Float,
        restorationCheckpoint: VodRestorationCheckpoint?,
    ): PlaybackResult<Unit> = withContext(dispatcher) {
        // The retained player's data-source factory owns request headers/DNS/proxy configuration.
        // Reuse is safe only when the per-source URL is the sole network-plan change.
        if (!media3SourceReplacementCompatible(this@AndroidMedia3Backend.plan, plan)) {
            return@withContext backendFailure(
                FailurePhase.ENGINE_START,
                FailureCode.NO_ELIGIBLE_GRAPH,
            )
        }
        this@AndroidMedia3Backend.plan = plan
        mediaItem = mediaItemFor(plan)
        firstAudioReported.set(false)
        firstVideoReported.set(false)
        trackReferences = emptyMap()
        currentTrackCatalog = PlaybackTrackCatalog()
        trackRevision = 0
        when (val applied = apply(plan)) {
            is PlaybackResult.Failure -> applied
            is PlaybackResult.Success -> start(
                paused,
                startPositionMs,
                playbackRate,
                restorationCheckpoint,
            )
        }
    }

    override suspend fun release(): PlaybackResult<Unit> {
        if (released) return PlaybackResult.Success(Unit)
        terminalSuppressed = true
        timelineJob?.cancel()
        timelineJob = null
        if (!releasePlayer()) return backendFailure(FailurePhase.RELEASE)
        if (!calls.awaitIdle(NETWORK_RELEASE_TIMEOUT_MS)) {
            return backendFailure(FailurePhase.RELEASE, FailureCode.NETWORK_TIMEOUT)
        }
        released = true
        factScope.cancel()
        return PlaybackResult.Success(Unit)
    }

    override suspend fun hardAbort(): PlaybackResult<Unit> {
        if (released) return PlaybackResult.Success(Unit)
        terminalSuppressed = true
        timelineJob?.cancel()
        timelineJob = null
        calls.cancelAll()
        val releasedPlayer = releasePlayer(awaitExistingRelease = true)
        val networkReleased = calls.awaitIdle(NETWORK_HARD_ABORT_TIMEOUT_MS)
        if (!releasedPlayer || !networkReleased) {
            return backendFailure(
                FailurePhase.RELEASE,
                if (!networkReleased) FailureCode.NETWORK_TIMEOUT else FailureCode.UNKNOWN,
            )
        }
        released = true
        factScope.cancel()
        return PlaybackResult.Success(Unit)
    }

    private fun startTimelineFacts() {
        timelineJob?.cancel()
        timelineJob = factScope.launch {
            while (isActive && !released) {
                publishTimelineFacts()
                delay(TIMELINE_FACT_INTERVAL_MS)
            }
        }
    }

    private fun publishTimelineFacts() {
        if (released) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
        _events.tryEmit(
            Media3BackendEvent.TimelineUpdated(
                PlaybackTimelineFacts(
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = duration,
                    bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0),
                    seekable = player.isCurrentMediaItemSeekable,
                ),
            ),
        )
    }

    private fun publishTrackCatalog(tracks: Tracks) {
        val references = linkedMapOf<PlaybackTrackId, Media3TrackReference>()
        val audio = mutableListOf<PlaybackTrackDescriptor>()
        val subtitles = mutableListOf<PlaybackTrackDescriptor>()
        var selectedAudio: PlaybackTrackId? = null
        var selectedSubtitle: PlaybackTrackId? = null
        tracks.groups.forEachIndexed { groupIndex, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_AUDIO -> PlaybackTrackType.AUDIO
                C.TRACK_TYPE_TEXT -> PlaybackTrackType.SUBTITLE
                else -> return@forEachIndexed
            }
            repeat(group.length) { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                val id = PlaybackTrackId("m3:$groupIndex:$trackIndex:${format.id.orEmpty()}")
                val descriptor = PlaybackTrackDescriptor(
                    id = id,
                    type = type,
                    label = format.label,
                    language = format.language,
                    mimeType = format.sampleMimeType,
                    codec = format.codecs,
                    channelCount = format.channelCount.takeIf { it > 0 },
                    sampleRate = format.sampleRate.takeIf { it > 0 },
                    forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                    default = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                )
                references[id] = Media3TrackReference(group, trackIndex, group.type, type, descriptor)
                if (type == PlaybackTrackType.AUDIO) audio += descriptor else subtitles += descriptor
                if (group.isTrackSelected(trackIndex)) {
                    if (type == PlaybackTrackType.AUDIO) selectedAudio = id else selectedSubtitle = id
                }
            }
        }
        trackReferences = references
        trackRevision = if (trackRevision == Long.MAX_VALUE) Long.MAX_VALUE else trackRevision + 1
        currentTrackCatalog = PlaybackTrackCatalog(
            revision = trackRevision,
            audio = audio,
            subtitles = subtitles,
            selectedAudioTrackId = selectedAudio,
            selectedSubtitleTrackId = selectedSubtitle,
            subtitlesEnabled = selectedSubtitle != null ||
                C.TRACK_TYPE_TEXT !in player.trackSelectionParameters.disabledTrackTypes,
        )
        _events.tryEmit(Media3BackendEvent.TrackCatalogUpdated(currentTrackCatalog))
    }

    private fun restoreTrackSelectionIfReady() {
        val checkpoint = pendingRestoration ?: return
        if (currentTrackCatalog.audio.isEmpty() && currentTrackCatalog.subtitles.isEmpty()) return
        pendingRestoration = null
        val audio = checkpoint.selectedAudio?.let(::findTrackReference)
        val subtitle = checkpoint.selectedSubtitle?.let(::findTrackReference)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            audio?.let {
                setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                setOverrideForType(TrackSelectionOverride(it.group.mediaTrackGroup, it.trackIndex))
            }
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !checkpoint.subtitlesEnabled)
            if (checkpoint.subtitlesEnabled) {
                subtitle?.let {
                    setOverrideForType(TrackSelectionOverride(it.group.mediaTrackGroup, it.trackIndex))
                }
            }
        }.build()
    }

    private fun findTrackReference(selection: RestorableTrackSelection): Media3TrackReference? {
        trackReferences[selection.originalId]?.let { exact ->
            if (exact.type == selection.type) return exact
        }
        return com.nuvio.tv.playback.core.TrackRestorationPolicy.bestMatch(
            trackReferences.values.filter { it.type == selection.type },
            selection,
        ) { it.descriptor }
    }

    private data class Media3TrackReference(
        val group: Tracks.Group,
        val trackIndex: Int,
        val trackType: Int,
        val type: PlaybackTrackType,
        val descriptor: PlaybackTrackDescriptor,
    )

    override suspend fun metrics(): PlaybackResult<Media3DecoderMetrics> = withContext(dispatcher) {
        if (released) return@withContext backendFailure(FailurePhase.PLAYBACK)
        val video = player.videoDecoderCounters
        val audio = player.audioDecoderCounters
        video?.ensureUpdated()
        audio?.ensureUpdated()
        PlaybackResult.Success(
            Media3DecoderMetrics(
                videoRendered = video?.renderedOutputBufferCount ?: 0,
                videoSkipped = video?.skippedOutputBufferCount ?: 0,
                videoDropped = video?.droppedBufferCount ?: 0,
                audioRendered = audio?.renderedOutputBufferCount ?: 0,
                audioSkipped = audio?.skippedOutputBufferCount ?: 0,
                audioDropped = audio?.droppedBufferCount ?: 0,
            ),
        )
    }

    private suspend fun releasePlayer(awaitExistingRelease: Boolean = false): Boolean {
        if (playerReleased) return true
        return withContext(dispatcher) {
            if (!releaseGate.initiated) {
                runCatching {
                    player.playWhenReady = false
                    player.stop()
                }
                runCatching { player.removeListener(playerListener) }
                runCatching { player.removeAnalyticsListener(analyticsListener) }
                byteProgress.unbind()
            }
            val initiatedProof = runCatching { releaseGate.initiate() }.getOrDefault(false)
            val success = if (initiatedProof || !awaitExistingRelease) {
                initiatedProof
            } else {
                // hardAbort is the second half of the session's bounded release barrier. Await the
                // already-issued release repeatedly; never initiate a second player teardown.
                runCatching {
                    releaseGate.awaitUpTo(HARD_ABORT_RELEASE_AWAIT_ATTEMPTS)
                }.getOrDefault(false)
            }
            if (success) {
                surface?.confirmPlayerReleased()
                surface = null
                playerReleased = true
            }
            success
        }
    }

    private companion object {
        const val TIMELINE_FACT_INTERVAL_MS = 500L
        const val NETWORK_RELEASE_TIMEOUT_MS = 250L
        const val NETWORK_HARD_ABORT_TIMEOUT_MS = 750L
        // Four 1 s fork await windows plus network cancellation stay inside the session's 5 s
        // release barrier while covering slow Amlogic/MediaCodec teardown observed on ONN.
        const val HARD_ABORT_RELEASE_AWAIT_ATTEMPTS = 4
    }
}

/** Runtime quality/profile changes deliberately build on, and therefore retain, manual overrides. */
internal fun applyMedia3RuntimeTrackPlan(
    current: TrackSelectionParameters,
    tracks: Media3TrackPlan,
): TrackSelectionParameters = current.buildUpon().apply {
    if (tracks.viewportWidth != null && tracks.viewportHeight != null) {
        setViewportSize(tracks.viewportWidth, tracks.viewportHeight, true)
    } else {
        clearViewportSizeConstraints()
    }
    if (tracks.maximumVideoWidth != null && tracks.maximumVideoHeight != null) {
        setMaxVideoSize(tracks.maximumVideoWidth, tracks.maximumVideoHeight)
    } else {
        setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
    }
    setMaxVideoBitrate(tracks.maximumVideoBitrate ?: Int.MAX_VALUE)
    setPreferredAudioLanguage(tracks.preferredAudioLanguage)
    setPreferredTextLanguage(tracks.preferredSubtitleLanguage)
    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !tracks.subtitlesEnabled)
}.build()

private fun mediaItemFor(plan: Media3AdapterPlan): MediaItem = MediaItem.Builder()
    .setUri(plan.request.url)
    .apply { plan.request.mimeType?.let(::setMimeType) }
    .apply {
        plan.request.drm?.let { drm ->
            setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(drm.schemeUuid)
                    .setLicenseUri(drm.licenseUrl)
                    .setLicenseRequestHeaders(drm.headers)
                    .setMultiSession(drm.multiSession)
                    .build(),
            )
        }
    }
    .build()

internal fun media3SourceReplacementCompatible(
    previous: Media3AdapterPlan,
    next: Media3AdapterPlan,
): Boolean = previous.copy(request = previous.request.copy(url = next.request.url)) == next

/** Extracts only facts carried by Media3's selected video input format. */
internal fun media3VideoFormatFacts(format: Format): List<Media3BackendEvent> = buildList {
    add(Media3BackendEvent.VideoInputFormatChanged(format.sampleMimeType))
    validMedia3VideoFrameRate(format.frameRate)
        ?.let { add(Media3BackendEvent.VideoFrameRateChanged(it)) }
}

internal fun validMedia3VideoFrameRate(frameRate: Float): Float? =
    frameRate.takeIf { it != C.RATE_UNSET }
        ?.let(com.nuvio.tv.playback.core.ContentFrameRatePolicy::validOrNull)


/**
 * Reports byte activity while an endless HTTP load is still open. The first callback is immediate;
 * later callbacks are rate-limited so transport chunking cannot flood the engine facts flow.
 */
internal class Media3ByteProgressSignal(
    private val clockNanos: () -> Long = System::nanoTime,
    private val minimumIntervalNanos: Long = TimeUnit.MILLISECONDS.toNanos(500),
) : TransferListener {
    private val nextEmissionNanos = AtomicLong(Long.MIN_VALUE)
    @Volatile private var sink: (() -> Unit)? = null

    fun bind(sink: () -> Unit) {
        this.sink = sink
    }

    fun unbind() {
        sink = null
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (!isNetwork || bytesTransferred <= 0) return
        val now = clockNanos()
        while (true) {
            val next = nextEmissionNanos.get()
            if (next != Long.MIN_VALUE && now < next) return
            if (nextEmissionNanos.compareAndSet(next, now + minimumIntervalNanos)) {
                sink?.invoke()
                return
            }
        }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
}

/** Tracks only calls created by this player, so hard-abort never cancels unrelated app traffic. */
private class TrackingCallFactory(
    private val delegate: Call.Factory,
    private val registry: TrackingCallRegistry,
) : Call.Factory {
    override fun newCall(request: Request): Call = registry.track(delegate.newCall(request))
}

private class TrackingCallRegistry {
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private var idleSignal = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }

    fun track(call: Call): Call {
        lateinit var wrapped: TrackingCall
        wrapped = TrackingCall(
            delegate = call,
            completed = { remove(wrapped) },
            cloneCall = { track(call.clone()) },
        )
        synchronized(lock) {
            if (calls.isEmpty()) idleSignal = kotlinx.coroutines.CompletableDeferred()
            calls += wrapped
        }
        return wrapped
    }

    private fun remove(call: Call) {
        synchronized(lock) {
            calls -= call
            if (calls.isEmpty()) idleSignal.complete(Unit)
        }
    }

    fun cancelAll() = synchronized(lock) { calls.toList() }.forEach(Call::cancel)

    // Completion-driven, not polled: every tracked call already reports completion, so the
    // release barrier wakes exactly when the last call finishes instead of burning 10ms timer
    // wakeups on the zap critical path.
    suspend fun awaitIdle(timeoutMs: Long): Boolean {
        val signal = synchronized(lock) {
            if (calls.isEmpty()) return true
            idleSignal
        }
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            signal.await()
            true
        } ?: synchronized(lock) { calls.isEmpty() }
    }
}

private class TrackingCall(
    private val delegate: Call,
    private val completed: () -> Unit,
    private val cloneCall: () -> Call,
) : Call by delegate {
    private val completionSent = AtomicBoolean(false)
    private fun completeOnce() {
        if (completionSent.compareAndSet(false, true)) completed()
    }

    override fun execute(): Response = try {
        delegate.execute().withTrackedBody(::completeOnce)
    } catch (failure: Throwable) {
        completeOnce()
        throw failure
    }

    override fun enqueue(responseCallback: Callback) {
        delegate.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                completeOnce()
                responseCallback.onFailure(this@TrackingCall, e)
            }

            override fun onResponse(call: Call, response: Response) {
                responseCallback.onResponse(this@TrackingCall, response.withTrackedBody(::completeOnce))
            }
        })
    }

    override fun clone(): Call = cloneCall()
}

private fun Response.withTrackedBody(completed: () -> Unit): Response {
    val original = body ?: run {
        completed()
        return this
    }
    return newBuilder().body(TrackingResponseBody(original, completed)).build()
}

private class TrackingResponseBody(
    private val delegate: ResponseBody,
    private val completed: () -> Unit,
) : ResponseBody() {
    private val trackedSource: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    completed()
                }
            }
        }.buffer()
    }

    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun source(): BufferedSource = trackedSource
}

private fun failure(
    code: FailureCode,
    domain: FailureDomain,
    phase: FailurePhase,
    retryability: Retryability,
    deterministic: Boolean = false,
) = PlaybackResult.Failure(PlaybackFailure(code, domain, phase, retryability, deterministic))

/**
 * Media3 cannot truthfully apply these generic processing requirements with the stock TV path.
 * Keep subtitle and audio failures in separate domains so session policy never runs a subtitle
 * request through an audio fallback ladder.
 */
internal fun media3UnsupportedProcessingFailure(
    plan: Media3AdapterPlan,
    phase: FailurePhase,
): PlaybackResult.Failure? = when {
    plan.subtitleDelayMs != 0L -> failure(
        FailureCode.SUBTITLE_OUTPUT_UNSUPPORTED,
        FailureDomain.SUBTITLE,
        phase,
        Retryability.HANDOFF_ELIGIBLE,
        deterministic = true,
    )
    plan.downmixToStereo || plan.normalization || plan.audioDelayMs != 0L -> failure(
        FailureCode.AUDIO_OUTPUT_FAILED,
        FailureDomain.AUDIO,
        phase,
        Retryability.HANDOFF_ELIGIBLE,
        deterministic = true,
    )
    else -> null
}

private fun backendFailure(
    phase: FailurePhase,
    code: FailureCode = FailureCode.UNKNOWN,
) = failure(
    code = code,
    domain = when (code) {
        FailureCode.SURFACE_LOST -> FailureDomain.VIDEO_RENDERER_SURFACE
        FailureCode.AUDIO_OUTPUT_FAILED -> FailureDomain.AUDIO
        FailureCode.SUBTITLE_OUTPUT_UNSUPPORTED -> FailureDomain.SUBTITLE
        FailureCode.NETWORK_TIMEOUT -> FailureDomain.NETWORK
        else -> FailureDomain.UNKNOWN
    },
    phase = phase,
    retryability = Retryability.HANDOFF_ELIGIBLE,
)
