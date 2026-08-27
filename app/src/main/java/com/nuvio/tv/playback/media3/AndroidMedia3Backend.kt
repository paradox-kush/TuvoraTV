package com.nuvio.tv.playback.media3

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import kotlinx.coroutines.Dispatchers
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
            if (plan.downmixToStereo || plan.normalization || plan.audioDelayMs != 0L || plan.subtitleDelayMs != 0L) {
                return@withContext failure(
                    FailureCode.AUDIO_OUTPUT_FAILED,
                    FailureDomain.AUDIO,
                    FailurePhase.ENGINE_START,
                    Retryability.HANDOFF_ELIGIBLE,
                    deterministic = true,
                )
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
        player.trackSelectionParameters = buildTrackParameters(
            player.trackSelectionParameters.buildUpon(),
            plan,
        ).build()
        player.skipSilenceEnabled = plan.skipSilence
        val mediaItem = MediaItem.Builder()
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
        return AndroidMedia3Backend(
            player,
            mediaItem,
            callRegistry,
            byteProgress,
            playerDispatcher,
            releaseController,
            plan,
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
    private val mediaItem: MediaItem,
    private val calls: TrackingCallRegistry,
    private val byteProgress: Media3ByteProgressSignal,
    private val dispatcher: CoroutineDispatcher,
    private val releaseController: Media3ReleaseController,
    private var plan: Media3AdapterPlan,
) : Media3Backend {
    private val _events = MutableSharedFlow<Media3BackendEvent>(extraBufferCapacity = 64)
    override val events: Flow<Media3BackendEvent> = _events.asSharedFlow()
    private var surface: Media3SurfaceLease? = null
    private val firstAudioReported = AtomicBoolean(false)
    private val firstVideoReported = AtomicBoolean(false)
    private var terminalSuppressed = false
    private var released = false
    private var playerReleased = false
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

    override suspend fun start(paused: Boolean): PlaybackResult<Unit> = withContext(dispatcher) {
        if (released || surface == null) return@withContext backendFailure(FailurePhase.ENGINE_START)
        runCatching {
            terminalSuppressed = false
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = !paused
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

    override suspend fun apply(plan: Media3AdapterPlan): PlaybackResult<Unit> = withContext(dispatcher) {
        if (released) return@withContext backendFailure(FailurePhase.PLAYBACK)
        if (plan.downmixToStereo || plan.normalization || plan.audioDelayMs != 0L || plan.subtitleDelayMs != 0L) {
            return@withContext backendFailure(FailurePhase.PLAYBACK, FailureCode.AUDIO_OUTPUT_FAILED)
        }
        runCatching {
            val tracks = plan.tracks
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
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
            player.skipSilenceEnabled = plan.skipSilence
            this@AndroidMedia3Backend.plan = plan
        }.fold(
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

    override suspend fun release(): PlaybackResult<Unit> {
        if (released) return PlaybackResult.Success(Unit)
        terminalSuppressed = true
        if (!releasePlayer()) return backendFailure(FailurePhase.RELEASE)
        if (!calls.awaitIdle(NETWORK_RELEASE_TIMEOUT_MS)) {
            return backendFailure(FailurePhase.RELEASE, FailureCode.NETWORK_TIMEOUT)
        }
        released = true
        return PlaybackResult.Success(Unit)
    }

    override suspend fun hardAbort(): PlaybackResult<Unit> {
        if (released) return PlaybackResult.Success(Unit)
        terminalSuppressed = true
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
        return PlaybackResult.Success(Unit)
    }

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
                runCatching { releaseGate.await() }.getOrDefault(false)
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
        const val NETWORK_RELEASE_TIMEOUT_MS = 250L
        const val NETWORK_HARD_ABORT_TIMEOUT_MS = 750L
    }
}

/** Extracts only facts carried by Media3's selected video input format. */
internal fun media3VideoFormatFacts(format: Format): List<Media3BackendEvent> = buildList {
    add(Media3BackendEvent.VideoInputFormatChanged(format.sampleMimeType))
    validMedia3VideoFrameRate(format.frameRate)
        ?.let { add(Media3BackendEvent.VideoFrameRateChanged(it)) }
}

internal fun validMedia3VideoFrameRate(frameRate: Float): Float? = frameRate.takeIf {
    it != C.RATE_UNSET && it.isFinite() && it in MIN_CONTENT_FRAME_RATE..MAX_CONTENT_FRAME_RATE
}

private const val MIN_CONTENT_FRAME_RATE = 10f
private const val MAX_CONTENT_FRAME_RATE = 120f

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
    private val calls = Collections.synchronizedSet(mutableSetOf<Call>())

    fun track(call: Call): Call {
        lateinit var wrapped: TrackingCall
        wrapped = TrackingCall(
            delegate = call,
            completed = { calls -= wrapped },
            cloneCall = { track(call.clone()) },
        )
        calls += wrapped
        return wrapped
    }

    fun cancelAll() = synchronized(calls) { calls.toList() }.forEach(Call::cancel)

    suspend fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (calls.isNotEmpty() && System.nanoTime() < deadline) delay(10)
        return calls.isEmpty()
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

private fun backendFailure(
    phase: FailurePhase,
    code: FailureCode = FailureCode.UNKNOWN,
) = failure(
    code = code,
    domain = when (code) {
        FailureCode.SURFACE_LOST -> FailureDomain.VIDEO_RENDERER_SURFACE
        FailureCode.AUDIO_OUTPUT_FAILED -> FailureDomain.AUDIO
        FailureCode.NETWORK_TIMEOUT -> FailureDomain.NETWORK
        else -> FailureDomain.UNKNOWN
    },
    phase = phase,
    retryability = Retryability.HANDOFF_ELIGIBLE,
)
