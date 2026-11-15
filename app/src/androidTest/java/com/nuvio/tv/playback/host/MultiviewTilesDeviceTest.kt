package com.nuvio.tv.playback.host

import android.os.Build
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.core.memory.AndroidPlayerMemoryBudget
import com.nuvio.tv.playback.android.AndroidAudioFocusPort
import com.nuvio.tv.playback.core.*
import com.nuvio.tv.playback.media3.AndroidMedia3BackendFactory
import com.nuvio.tv.playback.media3.Media3Engine
import com.nuvio.tv.playback.multiview.AudibleTileMediaSession
import com.nuvio.tv.playback.multiview.AudibleTileSource
import com.nuvio.tv.playback.multiview.MultiviewCoordinator
import com.nuvio.tv.playback.ui.PlaybackSessionController
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two tile-mode Media3 sessions play at once on one device while ONE focus request is held for the
 * screen and no tile owns a media session. Not part of the default run: it needs the same isolated
 * release gate and a real decoder as [LiveMedia3PresentationDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class MultiviewTilesDeviceTest {
    @get:Rule val compose = createComposeRule()

    private class CountingFocus(private val real: AudioFocusPort) : AudioFocusPort by real {
        var acquires = 0
        var abandons = 0
        override suspend fun acquire(): PlaybackResult<Unit> { acquires++; return real.acquire() }
        override suspend fun abandon(): PlaybackResult<Unit> { abandons++; return real.abandon() }
    }

    private class NoSession : AudibleTileMediaSession {
        var shown = 0
        override fun show(tile: AudibleTileSource?) { shown++ }
        override fun release() {}
    }

    @Test
    fun twoTilesPlayAtOnceWithOneFocusHolderAndNoTileMediaSession(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedReleaseGate") == "true")
        assumeTrue(Build.MODEL.contains("sdk") ||
            InstrumentationRegistry.getArguments().getString("hardwarePlaybackGate") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CompletableDeferred<FrameLayout>()
        compose.setContent {
            AndroidView(factory = { FrameLayout(it).also { root -> ready.complete(root) } }, modifier = Modifier.fillMaxSize())
        }
        val root = ready.await()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val tile = TileHostConfig(bufferBytes = 6 * 1024 * 1024)
        val clip = File(context.cacheDir, "tile-clip.ts").also { file ->
            instrumentation.context.assets.open("live-surface.ts").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val controllers = ArrayList<PlaybackSessionController>()
        val sessions = ArrayList<PlaybackSession>()
        val events = List(2) { CopyOnWriteArrayList<PlaybackEvent>() }
        val focus = CountingFocus(AndroidAudioFocusPort(context))
        val coordinator = MultiviewCoordinator(scope, focus, NoSession())
        try {
            withContext(Dispatchers.Main.immediate) { assertTrue(coordinator.request {}) }
            repeat(2) { slot ->
                val owner = withContext(Dispatchers.Main.immediate) {
                    FrameLayout(context).also { root.addView(it, FrameLayout.LayoutParams(320, 180)) }
                }
                val surfaces = withContext(Dispatchers.Main.immediate) {
                    CleanLiveSurfaceCoordinator(owner, scope, setOf(SurfaceMode.SURFACE_VIEW), true)
                }
                val engine = Media3Engine(scope, surfaces.media3SurfaceHost,
                    AndroidMedia3BackendFactory(context, OkHttpClient(),
                        playerMemoryBudget = AndroidPlayerMemoryBudget(context), tile = tile))
                val graph = PlaybackGraph("tile-$slot", EngineType.MEDIA3, GraphOutputProfile.MEDIA3_STANDARD,
                    DecoderMode.HARDWARE, AudioMode.DECODE, SurfaceMode.SURFACE_VIEW)
                val evidence = StreamEvidence(
                    delivery = EvidenceFact(DeliveryType.PROGRESSIVE, EvidenceProvenance.PROVIDER_DECLARED),
                    container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.PROVIDER_DECLARED))
                val session = PlaybackSession(scope,
                    PlaybackRequestResolver { request -> PlaybackResult.Success(ResolvedPlaybackRequest(request, request.summary(), evidence)) },
                    PlaybackEnvironmentProvider { input -> PlaybackResult.Success(PlaybackEnvironmentResolution(
                        PlaybackEnvironmentSnapshot(RuntimeCapabilities(1, System.currentTimeMillis(), Build.VERSION.SDK_INT,
                            display = DisplayCapabilities(VideoDimensions(1920, 1080)), audioRoute = AudioRouteCapabilities(AudioRoute.TV_SPEAKERS),
                            resources = ResourceCapabilities(1_000_000_000, false), surfaces = surfaces.capabilities), secureOutputRequired = false), input.effectivePreferences)) },
                    PlaybackRequirementsResolver { PlaybackResult.Success(tileRequirements()) },
                    PlaybackGraphProvider { PlaybackResult.Success(listOf(graph)) },
                    PlaybackEngineRegistry { if (it == EngineType.MEDIA3) engine else null },
                    object : PlaybackOutputController {
                        override suspend fun apply(request: PlaybackOutputRequest) = PlaybackResult.Success(PlaybackOutputApplication(PlaybackOutputStatus.DISABLED))
                        override suspend fun reset(releasedGeneration: Long?, reason: ActiveWorkReleaseReason) = PlaybackResult.Success(Unit)
                    },
                    object : PlaybackClock {
                        override fun nowEpochMs() = System.currentTimeMillis()
                        override suspend fun delayMs(durationMs: Long) = delay(durationMs)
                    }, PlaybackDiagnostics {},
                    // No audio focus of its own: the coordinator holds the one request.
                    audioFocus = NoopAudioFocusPort)
                sessions += session
                scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect { events[slot] += it } }
                val controller = PlaybackSessionController(session)
                controllers += controller
                assertTrue(surfaces.bindController(controller))
                assertTrue(surfaces.startHosting())
                controller.tune(PlaybackRequest(android.net.Uri.fromFile(clip).toString(), contentType = ContentType.LIVE), SessionProfile.GUIDE)
            }
            withTimeout(40_000) {
                while (events.any { list -> list.none { it is PlaybackEvent.FirstVideoFrame } }) delay(50)
            }
            assertEquals("both tiles decoded a frame at the same time", 2, events.count { l -> l.any { it is PlaybackEvent.FirstVideoFrame } })
            assertEquals("one focus request for the whole screen", 1, focus.acquires)
        } finally {
            withContext(NonCancellable) {
                sessions.forEach { withTimeoutOrNull(25_000) { it.release() } }
                coordinator.release()
            }
            scope.cancel()
            clip.delete()
        }
        assertEquals("focus is given back once", 1, focus.abandons)
    }

    private fun tileRequirements() = PlaybackRequirements(
        profile = SessionProfile.GUIDE, priority = SessionPriority.STARTUP_SPEED,
        qualityIntent = VideoQualityIntent.PREVIEW, displayModeSwitchAllowed = false,
        frameRatePreference = FrameRatePreference.OFF, hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO, softwareDecodeFallbackAllowed = false,
        subtitleFidelity = SubtitleFidelity.COMPATIBLE, subtitlesEnabled = false,
        audioOutput = AudioOutputPreference.PCM, pcmProcessingAllowed = true,
        buffering = BufferingPreference.LOW_LATENCY_LIVE, gpuRenderingAllowed = false,
        eligibleEngines = setOf(EngineType.MEDIA3), allowedSurfaceModes = setOf(SurfaceMode.SURFACE_VIEW),
        secureOutputRequired = false, resourceBudget = ResourceBudget(), liveContent = true,
    )
}
