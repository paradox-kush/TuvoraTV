package com.nuvio.tv.playback.android

import android.media.MediaCodecList
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.playback.core.AudioRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** API/fact smoke probe only. Passing this test does not prove decoding, EGL, or surface rendering. */
@RunWith(AndroidJUnit4::class)
class AndroidRuntimeCapabilityDeviceTest {
    @Test
    fun runtimeCapabilityApiSmokeIsConservativeAndDeviceScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = AndroidRuntimeCapabilityCollector(
            FrameworkAndroidCapabilitySource(context),
        ).refresh(
            observationSequence = 1,
            capturedAtEpochMs = System.currentTimeMillis(),
        )
        val capabilities = snapshot.capabilities

        assertTrue(capabilities.apiLevel >= 21)
        assertTrue(capabilities.display.currentDimensions.width > 0)
        assertTrue(capabilities.display.currentDimensions.height > 0)
        assertTrue(capabilities.resources.availableMemoryBytes > 0)
        assertTrue(capabilities.resources.concurrentDecoderBudget > 0)
        assertTrue(capabilities.videoDecoders.isNotEmpty())
        assertTrue(capabilities.videoDecoders.all { it.stableId.isNotBlank() })
        assertTrue(snapshot.device.manufacturer.isNotBlank())
        assertTrue(snapshot.device.model.isNotBlank())
        assertTrue(snapshot.device.device.isNotBlank())
        assertTrue(snapshot.device.hardware.isNotBlank())
        assertTrue(snapshot.device.board.isNotBlank())
        assertTrue(snapshot.device.firmware.isNotBlank())
        assertEquals(1, snapshot.observationSequence)
        assertEquals(1, snapshot.stableFingerprint.schemaVersion)
        assertEquals(64, snapshot.stableFingerprint.value.length)

        // No AudioTrack/AudioRouting owner was supplied, so connected HDMI/Bluetooth devices are
        // deliberately not promoted to an "actual route" claim.
        assertEquals(AudioRoute.UNKNOWN, capabilities.audioRoute.route)
        assertTrue(capabilities.audioRoute.encodedFormats.isEmpty())

        assertTrue(snapshot.surfaceFacts.surfaceViewAvailable)
        assertTrue(snapshot.surfaceFacts.textureViewAvailable)
        assertEquals(AndroidCapabilityProof.UNPROBED, snapshot.surfaceFacts.gpuRenderingProof)
        assertEquals(AndroidCapabilityProof.UNPROBED, snapshot.surfaceFacts.secureSurfaceProof)
        assertFalse(capabilities.surfaces.gpuRenderingSupported)
        assertFalse(capabilities.surfaces.secureSurfaceSupported)

        assertFrameworkSizeRateQueriesMatchSnapshot(snapshot)

        val isVerifiedAmazonModel =
            snapshot.device.manufacturer.equals("Amazon", ignoreCase = true) &&
                snapshot.device.model.equals("AFTKM", ignoreCase = true)
        val amazonSurfaceQuirk = "amazon-aftkm-embedded-surface-texture-v1"
        if (isVerifiedAmazonModel) {
            assertTrue(capabilities.verifiedQuirkIds.contains(amazonSurfaceQuirk))
        } else {
            assertFalse(
                capabilities.verifiedQuirkIds.contains(amazonSurfaceQuirk),
            )
        }
        assertTrue(
            AndroidPlaybackQuirkRegistry.resolve(
                device = AndroidDeviceFacts("Amazon", "AFTKM", "karat", "mt8696", "mt8696", "test"),
                codecStableIds = emptySet(),
                nowEpochMs = 1_800_000_000_000L,
                apiLevel = capabilities.apiLevel,
            ).any { it.quirk.id == amazonSurfaceQuirk },
        )
        assertFalse(
            AndroidPlaybackQuirkRegistry.resolve(
                device = AndroidDeviceFacts("onn.", "4K Streaming Box", "onn_4k_gtv", "amlogic", "s905y4", "test"),
                codecStableIds = emptySet(),
                nowEpochMs = 1_800_000_000_000L,
                apiLevel = capabilities.apiLevel,
            ).any { it.quirk.id == amazonSurfaceQuirk },
        )

        Log.i(
            "CleanPlaybackCaps",
            "device=${snapshot.device.manufacturer}/${snapshot.device.model} " +
                "api=${capabilities.apiLevel} codecs=${capabilities.videoDecoders.size} " +
                "display=${capabilities.display.currentDimensions.width}x" +
                "${capabilities.display.currentDimensions.height} " +
                "audioEvidence=${capabilities.audioRoute.route} hdr=${capabilities.display.hdrTypes} " +
                "fingerprint=${snapshot.stableFingerprint.value.take(12)} " +
                "quirks=${capabilities.verifiedQuirkIds} SMOKE_NO_RENDERING",
        )
    }

    private fun assertFrameworkSizeRateQueriesMatchSnapshot(snapshot: AndroidRuntimeCapabilitySnapshot) {
        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.associateBy { it.name }
        var comparisons = 0
        for (decoder in snapshot.decoderFacts) {
            val separator = decoder.stableId.lastIndexOf('|')
            if (separator <= 0) continue
            val codecName = decoder.stableId.substring(0, separator)
            val mime = decoder.stableId.substring(separator + 1)
            val video = runCatching {
                codecInfos.getValue(codecName).getCapabilitiesForType(mime).videoCapabilities
            }.getOrNull() ?: continue
            for (evidence in decoder.sizeRateSupport) {
                val frameworkAnswer = runCatching {
                    video.areSizeAndRateSupported(
                        evidence.dimensions.width,
                        evidence.dimensions.height,
                        evidence.frameRate,
                    )
                }.getOrNull() ?: continue
                assertEquals(evidence.supported, frameworkAnswer)
                comparisons += 1
            }
        }
        assertTrue("No MediaCodec size/rate support query could be cross-checked", comparisons > 0)
    }
}
