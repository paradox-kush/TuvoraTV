package com.nuvio.tv.playback.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.iptv.dns.PlaylistDns
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.playback.android.AndroidRuntimeCapabilityCollector
import com.nuvio.tv.playback.android.FrameworkAndroidCapabilitySource
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.PlaybackEngine
import com.nuvio.tv.playback.core.PlaybackEngineStart
import com.nuvio.tv.playback.core.PlaybackEngineState
import com.nuvio.tv.playback.core.PlaybackEvent
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.TlsPolicy
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.media3.AndroidMedia3BackendFactory
import com.nuvio.tv.playback.media3.Media3Engine
import com.nuvio.tv.playback.media3.Media3SurfaceHost
import com.nuvio.tv.playback.media3.ViewMedia3SurfaceHost
import com.nuvio.tv.playback.media3.surfaceFailure
import com.nuvio.tv.playback.mpv.AndroidMpvBackendFactory
import com.nuvio.tv.playback.mpv.MpvEngine
import com.nuvio.tv.playback.mpv.MpvSurfaceHost
import com.nuvio.tv.playback.mpv.ViewMpvSurfaceHost
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import kotlin.coroutines.resume

internal class DebugProfileFixtureRepository @Inject constructor(
    private val accountStore: XtreamAccountStore,
    private val liveStore: XtreamLiveStore,
) {
    suspend fun load(): DebugFixtureCatalog = buildDebugFixtureCatalog(
        accounts = accountStore.accounts.first(),
        recents = liveStore.recents.first(),
    )
}

/**
 * Debug-only vertical adapter lab. It has no production route and accepts no Intent playback data.
 * The operator must explicitly press Start after the debug profile fixture resolves locally.
 */
@AndroidEntryPoint
@UnstableApi
class CleanMedia3PlaybackLabActivity : ComponentActivity() {
    @Inject internal lateinit var fixtures: DebugProfileFixtureRepository
    @Inject internal lateinit var playlistDns: PlaylistDns

    private val labScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var surfaceContainer: FrameLayout
    private lateinit var status: TextView
    private lateinit var selectionLabel: TextView
    private lateinit var eligibility: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var start: Button
    private lateinit var startMpv: Button
    private lateinit var recreateSurface: Button
    private lateinit var stop: Button
    private var fixtureOptions: List<DebugFixtureOption> = emptyList()
    private var selectedIndex: Int = 0
    private var preparedSelection: PreparedLabSelection? = null
    private var selectionJob: Job? = null
    private var runtime: CleanPlaybackLabRuntime? = null
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var receiverRegistered = false

    private val releaseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RELEASE_ACTION) return
            if (intent.`package` != packageName) return
            val nonce = intent.getStringExtra("smoke_nonce")
                ?.takeIf(CleanPlaybackSmokeLine.RELEASE_NONCE::matches)
                ?: return
            labScope.launch {
                val released = runtime?.release(nonce) ?: false
                setStatus(if (released) LabReadinessCode.READY else LabReadinessCode.RELEASE_FAILED)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ContextCompat.registerReceiver(
            this,
            releaseReceiver,
            IntentFilter(RELEASE_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
        runtime = CleanPlaybackLabRuntime(
            context = applicationContext,
            scope = labScope,
            playlistDns = playlistDns,
            media3SurfaceHost = ::showMedia3Surface,
            mpvSurfaceHost = ::showMpvSurface,
            status = ::setStatus,
        )
        lifecycleScope.launch {
            when (val result = withContext(Dispatchers.IO) { fixtures.load() }) {
                is DebugFixtureCatalog.Blocked -> setStatus(result.code)
                is DebugFixtureCatalog.Ready -> {
                    fixtureOptions = result.options
                    selectFixture(0)
                }
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(releaseReceiver)
        receiverRegistered = false
        selectionJob?.cancel()
        val activeRuntime = runtime
        labScope.launch {
            activeRuntime?.release(null)
            labScope.cancel()
        }
        super.onDestroy()
    }

    override fun onStop() {
        // A one-connection provider must never remain owned after this foreground-only lab leaves
        // the screen. Main.immediate begins the pause/release barrier before onStop returns; the
        // force-stop in the host harness remains the independent device-switch safety barrier.
        start.isEnabled = false
        startMpv.isEnabled = false
        recreateSurface.isEnabled = false
        stop.isEnabled = false
        val activeRuntime = runtime
        labScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val released = activeRuntime?.release(null) ?: true
            val stillOwned = activeRuntime?.hasActiveSession() == true
            if (!isFinishing && !isDestroyed) {
                updateIdleControls(released && !stillOwned)
                stop.isEnabled = stillOwned
            }
        }
        super.onStop()
    }

    private fun buildUi() {
        surfaceContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            text = "LAB_LOADING"
        }
        selectionLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            text = "No live recent selected"
        }
        eligibility = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 13f
            text = "Media3=PREFLIGHT_PENDING · libmpv=PREFLIGHT_PENDING"
        }
        previous = Button(this).apply {
            text = "Previous"
            isEnabled = false
            setOnClickListener { moveSelection(-1) }
        }
        next = Button(this).apply {
            text = "Next"
            isEnabled = false
            setOnClickListener { moveSelection(1) }
        }
        start = Button(this).apply {
            text = "Start Media3"
            isEnabled = false
            setOnClickListener {
                val prepared = preparedSelection ?: return@setOnClickListener
                startEngine(prepared, EngineType.MEDIA3)
            }
        }
        startMpv = Button(this).apply {
            text = "Start libmpv"
            isEnabled = false
            setOnClickListener {
                val prepared = preparedSelection ?: return@setOnClickListener
                startEngine(prepared, EngineType.LIBMPV)
            }
        }
        recreateSurface = Button(this).apply {
            text = "Recreate surface"
            isEnabled = false
            setOnClickListener {
                isEnabled = false
                stop.isEnabled = false
                labScope.launch {
                    val recreated = runtime?.recreateSurface() == true
                    isEnabled = recreated
                    stop.isEnabled = runtime?.hasActiveSession() == true
                }
            }
        }
        stop = Button(this).apply {
            text = "Stop and release"
            isEnabled = false
            setOnClickListener {
                isEnabled = false
                labScope.launch {
                    val released = runtime?.release(null) == true
                    val stillOwned = runtime?.hasActiveSession() == true
                    recreateSurface.isEnabled = false
                    updateIdleControls(released && !stillOwned)
                    stop.isEnabled = stillOwned
                }
            }
        }
        val selector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(previous, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                LinearLayout(this@CleanMedia3PlaybackLabActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(selectionLabel)
                    addView(eligibility)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(startMpv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(recreateSurface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(stop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                addView(surfaceContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(selector, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
                addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
            },
        )
    }

    private fun moveSelection(delta: Int) {
        if (fixtureOptions.isEmpty()) return
        selectFixture((selectedIndex + delta + fixtureOptions.size) % fixtureOptions.size)
    }

    private fun selectFixture(index: Int) {
        val option = fixtureOptions.getOrNull(index) ?: return
        selectionJob?.cancel()
        selectedIndex = index
        preparedSelection = null
        selectionLabel.text = option.displayLabel
        eligibility.text = "Media3=PREFLIGHT_PENDING · libmpv=PREFLIGHT_PENDING"
        updateIdleControls(false)
        selectionJob = labScope.launch {
            val viewport = currentViewport()
            val prepared = runCatching { runtime?.preflight(option.fixture, viewport) }.getOrNull()
            if (fixtureOptions.getOrNull(selectedIndex)?.fingerprint != option.fingerprint) return@launch
            if (prepared == null) {
                eligibility.text =
                    "Media3=${LabEligibilityReason.PREFLIGHT_FAILED} · " +
                        "libmpv=${LabEligibilityReason.PREFLIGHT_FAILED}"
                setStatus(LabReadinessCode.PREFLIGHT_FAILED)
                updateIdleControls(false)
                return@launch
            }
            preparedSelection = prepared
            eligibility.text =
                "Media3=${prepared.reason(EngineType.MEDIA3)} · libmpv=${prepared.reason(EngineType.LIBMPV)}"
            setStatus(LabReadinessCode.READY)
            updateIdleControls(true)
        }
    }

    private fun startEngine(prepared: PreparedLabSelection, engine: EngineType) {
        start.isEnabled = false
        startMpv.isEnabled = false
        previous.isEnabled = false
        next.isEnabled = false
        labScope.launch {
            val started = runtime?.start(prepared, engine) == true
            val stillOwned = runtime?.hasActiveSession() == true
            stop.isEnabled = started || stillOwned
            recreateSurface.isEnabled = started
            if (!started && !stillOwned) {
                updateIdleControls(true)
            }
        }
    }

    private fun updateIdleControls(idle: Boolean) {
        val prepared = preparedSelection
        previous.isEnabled = idle && fixtureOptions.size > 1
        next.isEnabled = idle && fixtureOptions.size > 1
        start.isEnabled = idle && prepared?.isEligible(EngineType.MEDIA3) == true
        startMpv.isEnabled = idle && prepared?.isEligible(EngineType.LIBMPV) == true
    }

    private fun currentViewport(): VideoDimensions = VideoDimensions(
        surfaceContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.coerceAtLeast(1),
        surfaceContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.coerceAtLeast(1),
    )

    private fun showMedia3Surface(mode: SurfaceMode): Media3SurfaceHost {
        check(mode == SurfaceMode.SURFACE_VIEW || mode == SurfaceMode.TEXTURE_VIEW)
        surfaceContainer.removeAllViews()
        surfaceView = null
        textureView = null
        when (mode) {
            SurfaceMode.SURFACE_VIEW -> {
                surfaceView = SurfaceView(this).also { view ->
                    view.holder.addCallback(smokeSurfaceCallback(mode))
                    surfaceContainer.addView(view, matchParent())
                }
            }
            SurfaceMode.TEXTURE_VIEW -> {
                textureView = TextureView(this).also { view ->
                    view.surfaceTextureListener = smokeTextureListener(mode)
                    surfaceContainer.addView(view, matchParent())
                }
            }
        }
        return ViewMedia3SurfaceHost(
            surfaceView = { surfaceView },
            textureView = { textureView },
        )
    }

    private suspend fun showMpvSurface(mode: SurfaceMode): MpvSurfaceHost =
        withContext(Dispatchers.Main.immediate) {
            check(mode == SurfaceMode.NATIVE_EMBED || mode == SurfaceMode.GPU_RENDER)
            surfaceContainer.removeAllViews()
            textureView = null
            val view = SurfaceView(this@CleanMedia3PlaybackLabActivity).also { created ->
                surfaceView = created
                created.holder.addCallback(smokeSurfaceCallback(mode))
                surfaceContainer.addView(created, matchParent())
            }
            check(awaitValidSurface(view)) { "Debug libmpv surface was not created in time" }
            ViewMpvSurfaceHost(
                nativeEmbedSurface = { currentMpvSurface() },
                gpuRenderSurface = { currentMpvSurface() },
            )
        }

    private suspend fun awaitValidSurface(view: SurfaceView): Boolean =
        withTimeoutOrNull(MPV_SURFACE_TIMEOUT_MS) {
            if (!view.holder.surface.isValid) {
                suspendCancellableCoroutine { continuation ->
                    var finished = false
                    lateinit var callback: SurfaceHolder.Callback
                    fun complete() {
                        if (finished || !continuation.isActive) return
                        finished = true
                        view.holder.removeCallback(callback)
                        continuation.resume(Unit)
                    }
                    callback = object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = complete()
                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    }
                    view.holder.addCallback(callback)
                    continuation.invokeOnCancellation { view.holder.removeCallback(callback) }
                    if (view.holder.surface.isValid) complete()
                }
            }
            true
        } ?: false

    private fun currentMpvSurface(): Surface? = surfaceView?.holder?.surface?.takeIf(Surface::isValid)

    private fun smokeSurfaceCallback(mode: SurfaceMode) = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            smoke(CleanPlaybackSmokeLine.surface(mode, holder.surface.isValid, surfaceContainer.width, surfaceContainer.height))
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            smoke(CleanPlaybackSmokeLine.surface(mode, holder.surface.isValid, width, height))
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            smoke(CleanPlaybackSmokeLine.surface(mode, false, 0, 0))
        }
    }

    private fun smokeTextureListener(mode: SurfaceMode) = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            smoke(CleanPlaybackSmokeLine.surface(mode, true, width, height))
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            smoke(CleanPlaybackSmokeLine.surface(mode, true, width, height))
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            smoke(CleanPlaybackSmokeLine.surface(mode, false, 0, 0))
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun setStatus(code: LabReadinessCode) {
        status.text = "LAB_${code.name}"
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val RELEASE_ACTION = "com.tuvora.tv.debug.action.PLAYBACK_SMOKE_RELEASE"
        const val MPV_SURFACE_TIMEOUT_MS = 3_000L
    }
}

internal class PreparedLabSelection(
    val fixture: SelectedDebugFixture,
    val intent: LabPlaybackIntent,
    private val preflights: Map<EngineType, LabEnginePreflight>,
) {
    fun preflight(engine: EngineType): LabEnginePreflight =
        preflights[engine] ?: LabEnginePreflight.Ineligible(LabEligibilityReason.NO_ELIGIBLE_GRAPH)

    fun isEligible(engine: EngineType): Boolean = preflight(engine) is LabEnginePreflight.Eligible

    fun reason(engine: EngineType): String = preflight(engine).reason.name

    override fun toString(): String =
        "PreparedLabSelection(fixture=${fixture.fingerprint}, profile=${intent.profile}, " +
            "media3=${reason(EngineType.MEDIA3)}, libmpv=${reason(EngineType.LIBMPV)})"
}

@UnstableApi
private class CleanPlaybackLabRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val playlistDns: PlaylistDns,
    private val media3SurfaceHost: (SurfaceMode) -> Media3SurfaceHost,
    private val mpvSurfaceHost: suspend (SurfaceMode) -> MpvSurfaceHost,
    private val status: (LabReadinessCode) -> Unit,
) {
    private data class Active(
        val generation: Long,
        val engineType: EngineType,
        val graph: PlaybackGraph,
        val engine: PlaybackEngine,
        val events: Job,
    )

    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
    private var nextGeneration = 1L
    private var active: Active? = null
    private var lastPlayWhenReady = false
    private var lastLoading = false

    suspend fun preflight(
        fixture: SelectedDebugFixture,
        viewport: VideoDimensions,
    ): PreparedLabSelection {
        val usesDoh = fixture.usesDnsProvider(playlistDns::usesDoh)
        val intent = fixture.playbackIntent(
            if (usesDoh) DnsPolicy.SHARED_APPLICATION_RESOLVER else DnsPolicy.SYSTEM,
        )
        val android = withContext(Dispatchers.Default) {
            AndroidRuntimeCapabilityCollector(FrameworkAndroidCapabilitySource(context)).refresh(
                observationSequence = nextGeneration,
                capturedAtEpochMs = System.currentTimeMillis(),
            )
        }
        val preflights = mutableMapOf<EngineType, LabEnginePreflight>()
        listOf(EngineType.MEDIA3, EngineType.LIBMPV).forEach { engine ->
            preflights[engine] = preflightLabEngine(fixture, intent, android, viewport, engine)
        }
        return PreparedLabSelection(
            fixture = fixture,
            intent = intent,
            preflights = preflights,
        )
    }

    suspend fun start(
        prepared: PreparedLabSelection,
        engineType: EngineType,
    ): Boolean = mutex.withLock {
        if (active != null) return@withLock false
        val eligible = when (val preflight = prepared.preflight(engineType)) {
            is LabEnginePreflight.Ineligible -> {
                status(LabReadinessCode.POLICY_REJECTED)
                return@withLock false
            }
            is LabEnginePreflight.Eligible -> preflight
        }
        val graph = eligible.graph
        val requirements = eligible.requirements
        val intent = prepared.intent
        val generation = nextGeneration++
        lastPlayWhenReady = false
        lastLoading = false
        smoke(CleanPlaybackSmokeLine.session(generation, engineType))
        val preparedMpvHost = if (engineType == EngineType.LIBMPV) {
            runCatching { mpvSurfaceHost(graph.surfaceMode) }.getOrNull()
        } else {
            null
        }
        if (engineType == EngineType.LIBMPV && preparedMpvHost == null) {
            smoke(CleanPlaybackSmokeLine.error(surfaceFailure(), engineType))
            status(LabReadinessCode.START_FAILED)
            return@withLock false
        }
        val engine: PlaybackEngine = when (engineType) {
            EngineType.MEDIA3 -> Media3Engine(
                scope = scope,
                surfaceHost = media3SurfaceHost(graph.surfaceMode),
                backendFactory = AndroidMedia3BackendFactory(
                    context = context,
                    sharedHttpClient = http,
                    applicationDnsResolver = { key ->
                        key.value.takeIf(playlistDns::usesDoh)?.let(playlistDns::dnsFor)
                    },
                    sharedClientTlsPolicy = TlsPolicy.STRICT,
                ),
            )
            EngineType.LIBMPV -> MpvEngine(
                scope = scope,
                surfaceHost = requireNotNull(preparedMpvHost),
                backendFactory = AndroidMpvBackendFactory(context),
            )
        }
        val eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { event -> onEvent(generation, engineType, event) }
        }
        val running = Active(generation, engineType, graph, engine, eventJob)
        active = running
        val attached = engine.attachSurface(generation, graph)
        if (attached is PlaybackResult.Failure) {
            smoke(CleanPlaybackSmokeLine.error(attached.failure, engineType))
            releaseActive(running, null)
            status(LabReadinessCode.START_FAILED)
            return@withLock false
        }
        val started = engine.start(
            PlaybackEngineStart(
                generation = generation,
                request = intent.requestFor(engineType),
                evidence = intent.evidence,
                graph = graph,
                requirements = requirements,
                startPaused = false,
            ),
        )
        if (started is PlaybackResult.Failure) {
            smoke(CleanPlaybackSmokeLine.error(started.failure, engineType))
            releaseActive(running, null)
            status(LabReadinessCode.START_FAILED)
            return@withLock false
        }
        true
    }

    suspend fun recreateSurface(): Boolean = mutex.withLock {
        val current = active ?: return@withLock false
        when (val detached = current.engine.detachSurface(current.generation)) {
            is PlaybackResult.Failure -> {
                smoke(CleanPlaybackSmokeLine.error(detached.failure, current.engineType))
                status(LabReadinessCode.SURFACE_RECREATE_FAILED)
                return@withLock false
            }
            is PlaybackResult.Success -> Unit
        }
        // Rebuild only the selected View. The engine, backend, request, and provider connection are
        // deliberately retained; attachSurface reacquires through the original dynamic host.
        val surfaceReplaced = runCatching {
            when (current.engineType) {
                EngineType.MEDIA3 -> media3SurfaceHost(current.graph.surfaceMode)
                EngineType.LIBMPV -> mpvSurfaceHost(current.graph.surfaceMode)
            }
        }.isSuccess
        if (!surfaceReplaced) {
            smoke(CleanPlaybackSmokeLine.error(surfaceFailure(), current.engineType))
            status(LabReadinessCode.SURFACE_RECREATE_FAILED)
            return@withLock false
        }
        return@withLock when (val attached = current.engine.attachSurface(current.generation, current.graph)) {
            is PlaybackResult.Failure -> {
                smoke(CleanPlaybackSmokeLine.error(attached.failure, current.engineType))
                status(LabReadinessCode.SURFACE_RECREATE_FAILED)
                false
            }
            is PlaybackResult.Success -> {
                status(LabReadinessCode.READY)
                true
            }
        }
    }

    suspend fun hasActiveSession(): Boolean = mutex.withLock { active != null }

    suspend fun release(nonce: String?): Boolean = mutex.withLock {
        val current = active
        if (current == null) return@withLock nonce == null
        releaseActive(current, nonce)
    }

    private suspend fun releaseActive(current: Active, nonce: String?): Boolean {
        runCatching { current.engine.setPaused(current.generation, true) }
        when (val metrics = current.engine.snapshotMetrics(current.generation)) {
            is PlaybackResult.Success -> smoke(
                CleanPlaybackSmokeLine.metrics(
                    metrics.value.videoFramesRendered,
                    metrics.value.videoFramesDropped,
                    current.engineType,
                ),
            )
            is PlaybackResult.Failure -> Unit
        }
        var hardAbort = false
        val released = when (current.engine.release(current.generation)) {
            is PlaybackResult.Success -> true
            is PlaybackResult.Failure -> {
                hardAbort = true
                current.engine.hardAbort(current.generation) is PlaybackResult.Success
            }
        }
        current.events.cancel()
        if (released) {
            if (active === current) active = null
            smoke(
                CleanPlaybackSmokeLine.state(
                    current.generation,
                    LabPlayerState.RELEASED,
                    playWhenReady = false,
                    loading = false,
                    engine = current.engineType,
                ),
            )
            if (nonce != null) smoke(CleanPlaybackSmokeLine.release(nonce, hardAbort, current.engineType))
        } else {
            status(LabReadinessCode.RELEASE_FAILED)
        }
        return released
    }

    private fun onEvent(generation: Long, engineType: EngineType, event: PlaybackEvent) {
        if (event.generation != generation) return
        when (event) {
            is PlaybackEvent.EngineStateObserved -> {
                lastPlayWhenReady = event.playWhenReady
                lastLoading = event.isLoading
                smoke(
                    CleanPlaybackSmokeLine.state(
                        generation = generation,
                        state = event.state.toLabState(),
                        playWhenReady = event.playWhenReady,
                        loading = event.isLoading,
                        engine = engineType,
                    ),
                )
            }
            is PlaybackEvent.VideoDecoderInitialized ->
                smoke(CleanPlaybackSmokeLine.renderer(decoderName = event.decoderName, engine = engineType))
            is PlaybackEvent.VideoInputFormatChanged ->
                smoke(CleanPlaybackSmokeLine.renderer(sampleMimeType = event.sampleMimeType, engine = engineType))
            // The smoke schema has no AFR/display-mode field; the generation-bound fact is
            // exercised by adapter tests and must not be mislabeled as renderer cadence.
            is PlaybackEvent.VideoFrameRateChanged -> Unit
            is PlaybackEvent.VideoSizeChanged ->
                smoke(CleanPlaybackSmokeLine.videoSize(event.width, event.height, engineType))
            is PlaybackEvent.FirstVideoFrame -> smoke(CleanPlaybackSmokeLine.firstFrame(engineType))
            is PlaybackEvent.FirstAudio -> smoke(CleanPlaybackSmokeLine.firstAudio(engineType))
            is PlaybackEvent.PlaybackEnded ->
                smoke(
                    CleanPlaybackSmokeLine.state(
                        generation,
                        LabPlayerState.ENDED,
                        playWhenReady = lastPlayWhenReady,
                        loading = lastLoading,
                        engine = engineType,
                    ),
                )
            is PlaybackEvent.Failed -> {
                smoke(CleanPlaybackSmokeLine.error(event.failure, engineType))
                smoke(
                    CleanPlaybackSmokeLine.state(
                        generation,
                        LabPlayerState.ERROR,
                        playWhenReady = lastPlayWhenReady,
                        loading = lastLoading,
                        engine = engineType,
                    ),
                )
            }
            is PlaybackEvent.BytesReceived,
            is PlaybackEvent.BufferingStarted,
            is PlaybackEvent.BufferingEnded,
            is PlaybackEvent.TracksAvailable,
            is PlaybackEvent.TimelineUpdated,
            is PlaybackEvent.TrackCatalogUpdated,
            is PlaybackEvent.PlaybackRateChanged,
            is PlaybackEvent.RequestResolved,
            is PlaybackEvent.RequestRefreshed,
            is PlaybackEvent.HandoffRequestResolved,
            is PlaybackEvent.GraphSelected,
            is PlaybackEvent.SurfaceAttached,
            is PlaybackEvent.EngineStarting,
            is PlaybackEvent.EngineReleased,
            -> Unit
        }
    }

    private fun PlaybackEngineState.toLabState(): LabPlayerState = when (this) {
        PlaybackEngineState.IDLE -> LabPlayerState.IDLE
        PlaybackEngineState.BUFFERING -> LabPlayerState.BUFFERING
        PlaybackEngineState.READY -> LabPlayerState.READY
        PlaybackEngineState.ENDED -> LabPlayerState.ENDED
    }
}

private fun smoke(line: String) {
    Log.i("CleanPlaybackSmoke", line)
}
