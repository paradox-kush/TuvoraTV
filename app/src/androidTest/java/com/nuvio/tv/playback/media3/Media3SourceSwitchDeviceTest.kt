package com.nuvio.tv.playback.media3

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.playback.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Media3 factory/decoder smoke on local synthetic media; no provider credentials or network. */
@RunWith(AndroidJUnit4::class)
class Media3SourceSwitchDeviceTest {
    @Test
    fun repeatedMixedFormatZapsProduceFramesWithoutRetry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val directory = File(target.cacheDir, "source-switch-test").apply { mkdirs() }
        for (name in instrumentation.context.assets.list("source-switch").orEmpty()) {
            instrumentation.context.assets.open("source-switch/$name").use { source ->
                File(directory, name).outputStream().use { source.copyTo(it) }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reader = ImageReader.newInstance(320, 180, ImageFormat.PRIVATE, 3)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val lease = OffscreenLease(reader.surface)
        var created = 0
        val factory = AndroidMedia3BackendFactory(target, OkHttpClient())
        val engine = Media3Engine(scope, Media3SurfaceHost { _, _ -> PlaybackResult.Success(lease) },
            Media3BackendFactory { plan -> created++; factory.create(plan) })
        val graph = PlaybackGraph("media3-device", EngineType.MEDIA3, GraphOutputProfile.MEDIA3_STANDARD,
            DecoderMode.SOFTWARE, AudioMode.DECODE, SurfaceMode.SURFACE_VIEW)
        val requirements = PlaybackRequirements(
            profile = SessionProfile.GUIDE, priority = SessionPriority.STARTUP_SPEED,
            qualityIntent = VideoQualityIntent.PREVIEW, displayModeSwitchAllowed = false,
            frameRatePreference = FrameRatePreference.OFF, hdrPreference = HdrPreference.AUTO,
            decoderPreference = DecoderPreference.SOFTWARE_ONLY, softwareDecodeFallbackAllowed = true,
            subtitleFidelity = SubtitleFidelity.COMPATIBLE, subtitlesEnabled = true,
            audioOutput = AudioOutputPreference.PCM, pcmProcessingAllowed = true,
            buffering = BufferingPreference.RECOMMENDED, gpuRenderingAllowed = false,
            eligibleEngines = setOf(EngineType.MEDIA3), allowedSurfaceModes = setOf(SurfaceMode.SURFACE_VIEW),
            secureOutputRequired = false, resourceBudget = ResourceBudget(), liveContent = true,
        )
        var activeGeneration = 1L
        try {
            repeat(12) { index ->
                val generation = index + 1L
                activeGeneration = generation
                val hls = index % 2 == 0
                val evidence = StreamEvidence(
                    delivery = EvidenceFact(if (hls) DeliveryType.HLS else DeliveryType.PROGRESSIVE,
                        EvidenceProvenance.PROVIDER_DECLARED),
                    container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.PROVIDER_DECLARED),
                )
                success(engine.attachSurface(generation, graph))
                val firstFrame = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(15_000) {
                        engine.events.first { it.generation == generation &&
                            (it is PlaybackEvent.FirstVideoFrame || it is PlaybackEvent.Failed) }
                    }
                }
                success(engine.start(PlaybackEngineStart(generation,
                    PlaybackRequest(File(directory, if (hls) "live.m3u8" else "part00.ts").toURI().toString(),
                        contentType = ContentType.LIVE), evidence, graph, requirements, false)))
                val event = firstFrame.await()
                assertTrue("generation $generation must render: $event", event is PlaybackEvent.FirstVideoFrame)
                success(engine.releaseSource(generation))
            }
            assertEquals("each format transition requires a fresh configured player", 12, created)
        } finally {
            withContext(NonCancellable) {
                if (engine.release(activeGeneration) is PlaybackResult.Failure) engine.hardAbort(activeGeneration)
            }
            scope.cancel()
            reader.close()
            directory.deleteRecursively()
        }
    }

    private fun success(result: PlaybackResult<Unit>) =
        assertTrue("Expected success, got $result", result is PlaybackResult.Success)

    private class OffscreenLease(private val surface: Surface) : Media3SurfaceLease {
        override val mode = SurfaceMode.SURFACE_VIEW
        override val secure = false
        private var attached = false
        override fun attach(player: ExoPlayer) { player.setVideoSurface(surface); attached = true }
        override fun detach(player: ExoPlayer): Boolean { player.clearVideoSurface(); attached = false; return true }
        override fun confirmPlayerReleased() { attached = false }
        override suspend fun release() = !attached
    }
}
