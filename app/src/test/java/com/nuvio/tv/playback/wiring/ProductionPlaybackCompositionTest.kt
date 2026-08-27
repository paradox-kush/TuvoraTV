package com.nuvio.tv.playback.wiring

import android.app.Application
import com.nuvio.tv.playback.core.ActiveWorkReleaseReason
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.PlaybackLifecyclePort
import com.nuvio.tv.playback.core.PlaybackOutputController
import com.nuvio.tv.playback.core.PlaybackOutputApplication
import com.nuvio.tv.playback.core.PlaybackOutputRequest
import com.nuvio.tv.playback.core.PlaybackOutputStatus
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.ProviderPlaybackResolver
import com.nuvio.tv.playback.core.ProviderPlaybackResolverFactory
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.SessionPriority
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SurfaceCapabilities
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoQualityIntent
import com.nuvio.tv.playback.media3.Media3SurfaceHost
import com.nuvio.tv.playback.media3.ApplicationDnsResolver
import com.nuvio.tv.playback.mpv.MpvSurfaceHost
import com.nuvio.tv.playback.mpv.MpvSurfaceLease
import com.nuvio.tv.playback.settings.LegacyPlayerSettingsSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProductionPlaybackCompositionTest {

    @Test
    fun `factory constructs one idle controller and its release reaches stopped`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val requestedProfiles = mutableListOf<PlaybackProfileId>()
        val factory = ProductionPlaybackSessionFactory(
            context = context,
            providerResolverFactory = ProviderPlaybackResolverFactory { profileId ->
                requestedProfiles += profileId
                ProviderPlaybackResolver { _, _ -> error("No provider resolution during construction") }
            },
            applicationDnsResolver = ApplicationDnsResolver { null },
            legacyPreferenceSource = { LegacyPlayerSettingsSnapshot("test-v1", emptyMap()) },
        )
        val host = ProductionPlaybackHost(
            parentScope = this,
            media3SurfaceHost = Media3SurfaceHost { _, _ -> error("No surface acquisition during construction") },
            mpvSurfaceHost = NoAcquireMpvSurfaceHost,
            surfaceCapabilities = SurfaceCapabilities(
                surfaceViewSupported = true,
                textureViewSupported = true,
                nativeEmbedSupported = true,
            ),
            outputController = NoopOutputController,
            lifecycle = PlaybackLifecyclePort { emptyFlow() },
        )

        val profileId = PlaybackProfileId("composition-test-profile")
        val controller = factory.create(profileId, host)

        assertEquals(listOf(profileId), requestedProfiles)
        assertFalse(profileId.toString().contains(profileId.value))
        assertEquals(PlaybackState.IDLE, controller.snapshot.value.state)
        controller.release()
        assertEquals(PlaybackState.STOPPED, controller.snapshot.value.state)
    }

    @Test
    fun `production graph catalog exposes only structurally owned surface paths`() = runTest {
        val result = ProductionPlaybackGraphProvider.candidates(
            com.nuvio.tv.playback.core.PlaybackGraphInput(requirements(), StreamEvidence()),
        ) as PlaybackResult.Success
        val graphs = result.value

        assertTrue(graphs.isNotEmpty())
        assertTrue(graphs.all { it.isStructurallyValid() })
        assertTrue(graphs.any { it.engine == EngineType.MEDIA3 && it.surfaceMode == SurfaceMode.SURFACE_VIEW })
        assertTrue(graphs.any { it.engine == EngineType.LIBMPV && it.surfaceMode == SurfaceMode.NATIVE_EMBED })
        assertFalse(
            graphs.any {
                it.engine == EngineType.LIBMPV &&
                    it.surfaceMode == SurfaceMode.NATIVE_EMBED &&
                    it.decoderMode == com.nuvio.tv.playback.core.DecoderMode.SOFTWARE
            },
        )
    }

    @Test
    fun `secure graph catalog never offers libmpv`() = runTest {
        val result = ProductionPlaybackGraphProvider.candidates(
            com.nuvio.tv.playback.core.PlaybackGraphInput(
                requirements().copy(
                    eligibleEngines = setOf(EngineType.MEDIA3),
                    allowedSurfaceModes = setOf(SurfaceMode.SURFACE_VIEW),
                    secureOutputRequired = true,
                ),
                StreamEvidence(),
            ),
        ) as PlaybackResult.Success

        assertTrue(result.value.all { it.engine == EngineType.MEDIA3 && it.secureOutput })
    }

    private fun requirements() = PlaybackRequirements(
        profile = SessionProfile.FULLSCREEN,
        priority = SessionPriority.QUALITY_AND_STABILITY,
        qualityIntent = VideoQualityIntent.FULL,
        displayModeSwitchAllowed = false,
        frameRatePreference = FrameRatePreference.OFF,
        hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO,
        softwareDecodeFallbackAllowed = true,
        subtitleFidelity = SubtitleFidelity.COMPATIBLE,
        subtitlesEnabled = false,
        audioOutput = AudioOutputPreference.AUTO,
        pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED,
        gpuRenderingAllowed = true,
        eligibleEngines = EngineType.entries.toSet(),
        allowedSurfaceModes = setOf(
            SurfaceMode.SURFACE_VIEW,
            SurfaceMode.TEXTURE_VIEW,
            SurfaceMode.NATIVE_EMBED,
            SurfaceMode.GPU_RENDER,
        ),
        secureOutputRequired = false,
        resourceBudget = ResourceBudget(),
    )

    private object NoAcquireMpvSurfaceHost : MpvSurfaceHost {
        override suspend fun acquire(
            mode: SurfaceMode,
            secure: Boolean,
        ): PlaybackResult<MpvSurfaceLease> = error("No surface acquisition during construction")
    }

    private object NoopOutputController : PlaybackOutputController {
        override suspend fun apply(
            request: PlaybackOutputRequest,
        ): PlaybackResult<PlaybackOutputApplication> = PlaybackResult.Success(
            PlaybackOutputApplication(PlaybackOutputStatus.NOT_REQUESTED),
        )

        override suspend fun reset(
            releasedGeneration: Long?,
            reason: ActiveWorkReleaseReason,
        ): PlaybackResult<Unit> = PlaybackResult.Success(Unit)
    }
}
