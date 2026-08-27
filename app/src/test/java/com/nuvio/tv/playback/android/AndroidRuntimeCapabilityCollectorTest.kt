package com.nuvio.tv.playback.android

import com.nuvio.tv.playback.core.AudioCodec
import com.nuvio.tv.playback.core.AudioRoute
import com.nuvio.tv.playback.core.HdrType
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.ThermalState
import com.nuvio.tv.playback.core.VideoCodec
import com.nuvio.tv.playback.core.VideoDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRuntimeCapabilityCollectorTest {
    @Test
    fun `refresh maps platform observations without losing codec performance evidence`() {
        val collector = AndroidRuntimeCapabilityCollector(AndroidCapabilitySource { fireFacts() })

        val snapshot = collector.refresh(observationSequence = 7, capturedAtEpochMs = 1_800_000_000_000L)

        assertEquals(7, snapshot.capabilities.snapshotVersion)
        assertEquals(35, snapshot.capabilities.apiLevel)
        assertEquals(VideoCodec.HEVC, snapshot.capabilities.videoDecoders.single().codec)
        assertEquals(4, snapshot.capabilities.videoDecoders.single().maxSupportedInstances)
        assertEquals(null, snapshot.capabilities.videoDecoders.single().maxDimensions)
        assertEquals(null, snapshot.capabilities.videoDecoders.single().maxFrameRate)
        assertTrue(snapshot.decoderFacts.single().sizeRateSupport.single().supported)
        assertEquals(1, snapshot.decoderPerformancePoints.getValue("OMX.MTK.HEVC|video/hevc").size)
        assertEquals(setOf(HdrType.HDR10, HdrType.HLG), snapshot.capabilities.display.hdrTypes)
        assertEquals(AudioRoute.HDMI_EARC, snapshot.capabilities.audioRoute.route)
        assertEquals(setOf(AudioCodec.AC3, AudioCodec.EAC3), snapshot.capabilities.audioRoute.encodedFormats)
        assertTrue(snapshot.capabilities.surfaces.secureSurfaceSupported)
        assertEquals(
            setOf("amazon-aftkm-embedded-surface-texture-v1"),
            snapshot.capabilities.verifiedQuirkIds,
        )
        val override = snapshot.appliedQuirks.single().quirk.override
            as AndroidPlaybackQuirkOverride.ForceEmbeddedSurface
        assertEquals(SurfaceMode.TEXTURE_VIEW, override.surfaceMode)
    }

    @Test
    fun `refresh re-reads actual route memory and display facts`() {
        var facts = fireFacts()
        var reads = 0
        val collector = AndroidRuntimeCapabilityCollector(AndroidCapabilitySource { reads++; facts })

        val first = collector.refresh(observationSequence = 1, capturedAtEpochMs = 100)
        facts = facts.copy(
            audio = facts.audio.copy(route = AudioRoute.BLUETOOTH, encodedFormats = emptySet()),
            resources = facts.resources.copy(availableMemoryBytes = 128, lowMemory = true),
            display = facts.display.copy(currentDimensions = VideoDimensions(1920, 1080)),
        )
        val second = collector.refresh(observationSequence = 2, capturedAtEpochMs = 200)

        assertEquals(2, reads)
        assertEquals(AudioRoute.HDMI_EARC, first.capabilities.audioRoute.route)
        assertEquals(AudioRoute.BLUETOOTH, second.capabilities.audioRoute.route)
        assertEquals(128, second.capabilities.resources.availableMemoryBytes)
        assertTrue(second.capabilities.resources.lowMemory)
        assertEquals(VideoDimensions(1920, 1080), second.capabilities.display.currentDimensions)
        assertEquals(2, second.capabilities.snapshotVersion)
        assertEquals(2, second.observationSequence)
        assertEquals(200, second.capabilities.capturedAtEpochMs)
        assertEquals(first.stableFingerprint, second.stableFingerprint)
        assertEquals(first.compatibilityRuntime, second.compatibilityRuntime)
        assertEquals(second.stableFingerprint.value, second.compatibilityRuntime.capabilityFingerprint)
    }

    @Test
    fun `unverified model receives no renderer quirk`() {
        val facts = fireFacts().copy(
            device = fireFacts().device.copy(model = "future-model"),
        )

        val snapshot = AndroidRuntimeCapabilityCollector(AndroidCapabilitySource { facts })
            .refresh(observationSequence = 1, capturedAtEpochMs = 1_800_000_000_000L)

        assertTrue(snapshot.capabilities.verifiedQuirkIds.isEmpty())
        assertTrue(snapshot.appliedQuirks.isEmpty())
    }

    @Test
    fun `global decoder budget uses weakest hardware hint and ignores software inflation`() {
        val hardware = fireFacts().codecs.single()
        val codecs = listOf(
            hardware.copy(stableId = "hardware-a", maxSupportedInstances = 4),
            hardware.copy(stableId = "hardware-b", maxSupportedInstances = 2),
            hardware.copy(
                stableId = "software",
                hardwareAccelerated = false,
                softwareOnly = true,
                maxSupportedInstances = 32,
            ),
        )

        assertEquals(2, conservativeConcurrentDecoderBudget(codecs))
        assertEquals(1, conservativeConcurrentDecoderBudget(codecs + hardware.copy(maxSupportedInstances = null)))
    }

    @Test
    fun `basic surface availability and secure decoder do not imply GPU or secure surface proof`() {
        val facts = fireFacts().copy(
            surfaces = AndroidSurfaceFacts(
                surfaceViewAvailable = true,
                textureViewAvailable = true,
                gpuRenderingProof = AndroidCapabilityProof.UNPROBED,
                secureSurfaceProof = AndroidCapabilityProof.UNPROBED,
                secureGpuRenderingProof = AndroidCapabilityProof.UNPROBED,
            ),
        )

        val capabilities = AndroidRuntimeCapabilityCollector(AndroidCapabilitySource { facts })
            .refresh(observationSequence = 1, capturedAtEpochMs = 100)
            .capabilities

        assertTrue(capabilities.surfaces.surfaceViewSupported)
        assertTrue(capabilities.surfaces.textureViewSupported)
        assertFalse(capabilities.surfaces.gpuRenderingSupported)
        assertFalse(capabilities.surfaces.secureSurfaceSupported)
        assertFalse(capabilities.surfaces.secureGpuRenderingSupported)
    }

    private fun fireFacts() = AndroidPlatformCapabilityFacts(
        device = AndroidDeviceFacts(
            manufacturer = "Amazon",
            model = "AFTKM",
            device = "karat",
            hardware = "mt8696",
            board = "mt8696",
            firmware = "PS7699/4007",
        ),
        apiLevel = 35,
        codecs = listOf(
            AndroidVideoDecoderFacts(
                stableId = "OMX.MTK.HEVC|video/hevc",
                codec = VideoCodec.HEVC,
                hardwareAccelerated = true,
                softwareOnly = false,
                vendorProvided = true,
                securePlayback = true,
                widthRange = 64..4096,
                heightRange = 64..2160,
                globalFrameRateRange = 1..60,
                sizeRateSupport = listOf(
                    AndroidCodecSizeRateSupport(
                        dimensions = VideoDimensions(3840, 2160),
                        frameRate = 60.0,
                        supported = true,
                    ),
                ),
                profileLevels = setOf("2:4096"),
                maxSupportedInstances = 4,
                performancePoints = listOf(
                    AndroidCodecPerformancePoint(descriptor = "3840x2160@60"),
                ),
            ),
        ),
        display = AndroidDisplayFacts(
            currentDimensions = VideoDimensions(3840, 2160),
            supportedRefreshRates = setOf(23.976, 50.0, 59.94, 60.0),
            hdrTypes = setOf(HdrType.HDR10, HdrType.HLG),
            modeSwitchSupported = true,
        ),
        audio = AndroidAudioRouteFacts(
            route = AudioRoute.HDMI_EARC,
            encodedFormats = setOf(AudioCodec.AC3, AudioCodec.EAC3),
            maxChannelCount = 8,
            offloadSupported = false,
        ),
        resources = AndroidResourceFacts(
            availableMemoryBytes = 512L * 1024 * 1024,
            lowMemory = false,
            thermalState = ThermalState.NOMINAL,
            concurrentDecoderBudget = 4,
        ),
        surfaces = AndroidSurfaceFacts(
            surfaceViewAvailable = true,
            textureViewAvailable = true,
            secureSurfaceProof = AndroidCapabilityProof.PROBED_SUPPORTED,
            gpuRenderingProof = AndroidCapabilityProof.PROBED_SUPPORTED,
            secureGpuRenderingProof = AndroidCapabilityProof.PROBED_UNSUPPORTED,
        ),
    )
}
