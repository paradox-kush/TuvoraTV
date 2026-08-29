package com.nuvio.tv.playback.android

import com.nuvio.tv.playback.core.AudioCodec
import com.nuvio.tv.playback.core.AudioRoute
import com.nuvio.tv.playback.core.AudioRouteCapabilities
import com.nuvio.tv.playback.core.CompatibilityRuntimeFingerprint
import com.nuvio.tv.playback.core.DisplayCapabilities
import com.nuvio.tv.playback.core.ResourceCapabilities
import com.nuvio.tv.playback.core.RuntimeCapabilities
import com.nuvio.tv.playback.core.SurfaceCapabilities
import com.nuvio.tv.playback.core.ThermalState
import com.nuvio.tv.playback.core.VideoCodec
import com.nuvio.tv.playback.core.VideoDecoderCapability
import com.nuvio.tv.playback.core.VideoDimensions
import java.security.MessageDigest

/** Raw Android observations. Tests replace the framework reader rather than mocking static APIs. */
internal fun interface AndroidCapabilitySource {
    fun read(): AndroidPlatformCapabilityFacts
}

internal data class AndroidPlatformCapabilityFacts(
    val device: AndroidDeviceFacts,
    val apiLevel: Int,
    val codecs: List<AndroidVideoDecoderFacts>,
    val display: AndroidDisplayFacts,
    val audio: AndroidAudioRouteFacts,
    val resources: AndroidResourceFacts,
    val surfaces: AndroidSurfaceFacts,
)

internal data class AndroidDeviceFacts(
    val manufacturer: String,
    val model: String,
    val device: String,
    val hardware: String,
    val board: String,
    val firmware: String,
)

internal data class AndroidVideoDecoderFacts(
    val stableId: String,
    val codec: VideoCodec,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val vendorProvided: Boolean,
    val securePlayback: Boolean,
    /** Independent framework ranges; never combine their upper bounds into a fictitious format. */
    val widthRange: IntRange?,
    val heightRange: IntRange?,
    val globalFrameRateRange: IntRange?,
    /** Coupled answers from [android.media.MediaCodecInfo.VideoCapabilities.areSizeAndRateSupported]. */
    val sizeRateSupport: List<AndroidCodecSizeRateSupport>,
    val profileLevels: Set<String>,
    val maxSupportedInstances: Int?,
    val performancePoints: List<AndroidCodecPerformancePoint> = emptyList(),
)

internal data class AndroidCodecPerformancePoint(
    /** Framework canonical descriptor (for example "3840x2160@60"); API exposes no getters. */
    val descriptor: String,
)

internal data class AndroidCodecSizeRateSupport(
    val dimensions: VideoDimensions,
    val frameRate: Double,
    val supported: Boolean,
)

internal data class AndroidDisplayFacts(
    val currentDimensions: VideoDimensions,
    val supportedRefreshRates: Set<Double>,
    val hdrTypes: Set<com.nuvio.tv.playback.core.HdrType>,
    val modeSwitchSupported: Boolean,
)

internal data class AndroidAudioRouteFacts(
    val route: AudioRoute,
    val encodedFormats: Set<AudioCodec>,
    val maxChannelCount: Int,
    val offloadSupported: Boolean,
)

internal data class AndroidResourceFacts(
    val availableMemoryBytes: Long,
    val lowMemory: Boolean,
    val thermalState: ThermalState,
    val concurrentDecoderBudget: Int,
)

internal data class AndroidSurfaceFacts(
    /** Basic platform availability is not evidence that an engine can render correctly. */
    val surfaceViewAvailable: Boolean,
    val textureViewAvailable: Boolean,
    val gpuRenderingProof: AndroidCapabilityProof,
    val secureSurfaceProof: AndroidCapabilityProof,
    val secureGpuRenderingProof: AndroidCapabilityProof,
)

internal enum class AndroidCapabilityProof {
    UNPROBED,
    PROBED_SUPPORTED,
    PROBED_UNSUPPORTED,
}

internal data class AndroidStableCapabilityFingerprint(
    val schemaVersion: Int,
    val value: String,
)

internal data class AndroidRuntimeCapabilitySnapshot(
    val capabilities: RuntimeCapabilities,
    val observationSequence: Long,
    val stableFingerprint: AndroidStableCapabilityFingerprint,
    val compatibilityRuntime: CompatibilityRuntimeFingerprint,
    val device: AndroidDeviceFacts,
    val decoderFacts: List<AndroidVideoDecoderFacts>,
    val surfaceFacts: AndroidSurfaceFacts,
    val decoderPerformancePoints: Map<String, List<AndroidCodecPerformancePoint>>,
    val appliedQuirks: List<AppliedAndroidPlaybackQuirk>,
)

/** Each call re-reads route/display/memory/codec facts and produces a caller-versioned snapshot. */
internal class AndroidRuntimeCapabilityCollector(
    private val source: AndroidCapabilitySource,
    private val quirkRegistry: AndroidPlaybackQuirkRegistry = AndroidPlaybackQuirkRegistry,
) {
    private val staleQuirksLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    fun refresh(
        observationSequence: Long,
        capturedAtEpochMs: Long,
    ): AndroidRuntimeCapabilitySnapshot {
        require(observationSequence > 0) { "Capability observation sequence must be positive" }
        require(capturedAtEpochMs >= 0) { "Capability capture time must not be negative" }
        val facts = source.read()
        val codecStableIds = facts.codecs.mapTo(linkedSetOf(), AndroidVideoDecoderFacts::stableId)
        val appliedQuirks = quirkRegistry.resolve(
            device = facts.device,
            codecStableIds = codecStableIds,
            nowEpochMs = capturedAtEpochMs,
            apiLevel = facts.apiLevel,
        )
        if (staleQuirksLogged.compareAndSet(false, true)) {
            // A quirk expiring must be loud, never a silent shipped-fleet behavior revert.
            quirkRegistry.revalidationFindings(
                device = facts.device,
                codecStableIds = codecStableIds,
                nowEpochMs = capturedAtEpochMs,
                apiLevel = facts.apiLevel,
            ).forEach { finding -> android.util.Log.w("PlaybackQuirks", finding) }
        }
        val stableFingerprint = stableFingerprint(facts, appliedQuirks)
        val capabilities = RuntimeCapabilities(
            // Legacy core field remains observation-scoped until core exposes capabilityFingerprint.
            snapshotVersion = observationSequence.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            capturedAtEpochMs = capturedAtEpochMs,
            apiLevel = facts.apiLevel,
            videoDecoders = facts.codecs.map { codec ->
                VideoDecoderCapability(
                    stableId = codec.stableId,
                    codec = codec.codec,
                    hardwareAccelerated = codec.hardwareAccelerated,
                    softwareOnly = codec.softwareOnly,
                    vendorProvided = codec.vendorProvided,
                    securePlayback = codec.securePlayback,
                    // Core cannot express coupled size/rate evidence. Null means unknown, not support.
                    maxDimensions = null,
                    maxFrameRate = null,
                    profileLevels = codec.profileLevels,
                    maxSupportedInstances = codec.maxSupportedInstances,
                )
            },
            display = DisplayCapabilities(
                currentDimensions = facts.display.currentDimensions,
                supportedRefreshRates = facts.display.supportedRefreshRates,
                hdrTypes = facts.display.hdrTypes,
                modeSwitchSupported = facts.display.modeSwitchSupported,
            ),
            audioRoute = AudioRouteCapabilities(
                route = facts.audio.route,
                encodedFormats = facts.audio.encodedFormats,
                maxChannelCount = facts.audio.maxChannelCount,
                offloadSupported = facts.audio.offloadSupported,
            ),
            resources = ResourceCapabilities(
                availableMemoryBytes = facts.resources.availableMemoryBytes,
                lowMemory = facts.resources.lowMemory,
                thermalState = facts.resources.thermalState,
                concurrentDecoderBudget = facts.resources.concurrentDecoderBudget,
            ),
            surfaces = SurfaceCapabilities(
                surfaceViewSupported = facts.surfaces.surfaceViewAvailable,
                textureViewSupported = facts.surfaces.textureViewAvailable,
                secureSurfaceSupported = facts.surfaces.secureSurfaceProof == AndroidCapabilityProof.PROBED_SUPPORTED,
                gpuRenderingSupported = facts.surfaces.gpuRenderingProof == AndroidCapabilityProof.PROBED_SUPPORTED,
                secureGpuRenderingSupported =
                    facts.surfaces.secureGpuRenderingProof == AndroidCapabilityProof.PROBED_SUPPORTED,
            ),
            verifiedQuirkIds = appliedQuirks.mapTo(linkedSetOf()) { it.quirk.id },
        )
        return AndroidRuntimeCapabilitySnapshot(
            capabilities = capabilities,
            observationSequence = observationSequence,
            stableFingerprint = stableFingerprint,
            compatibilityRuntime = CompatibilityRuntimeFingerprint(
                deviceVersion = listOf(
                    facts.device.manufacturer,
                    facts.device.model,
                    facts.device.device,
                    facts.device.hardware,
                    facts.device.board,
                    "api-${facts.apiLevel}",
                ).joinToString("/") { it.ifBlank { "unknown" } },
                firmwareVersion = facts.device.firmware.ifBlank { "unknown" },
                capabilityFingerprint = stableFingerprint.value,
            ),
            device = facts.device,
            decoderFacts = facts.codecs,
            surfaceFacts = facts.surfaces,
            decoderPerformancePoints = facts.codecs.associate { it.stableId to it.performancePoints },
            appliedQuirks = appliedQuirks,
        )
    }

    private fun stableFingerprint(
        facts: AndroidPlatformCapabilityFacts,
        appliedQuirks: List<AppliedAndroidPlaybackQuirk>,
    ): AndroidStableCapabilityFingerprint {
        val stableParts = buildList {
            addAll(
                listOf(
                    facts.device.manufacturer,
                    facts.device.model,
                    facts.device.device,
                    facts.device.hardware,
                    facts.device.board,
                    facts.device.firmware,
                    facts.apiLevel.toString(),
                ),
            )
            facts.codecs.sortedBy(AndroidVideoDecoderFacts::stableId).forEach { codec ->
                add(
                    listOf(
                        codec.stableId,
                        codec.codec.name,
                        codec.hardwareAccelerated,
                        codec.softwareOnly,
                        codec.vendorProvided,
                        codec.securePlayback,
                        codec.maxSupportedInstances,
                        codec.profileLevels.sorted(),
                        codec.widthRange,
                        codec.heightRange,
                        codec.globalFrameRateRange,
                        codec.sizeRateSupport.sortedWith(
                            compareBy(
                                { it.dimensions.width },
                                { it.dimensions.height },
                                AndroidCodecSizeRateSupport::frameRate,
                            ),
                        ),
                        codec.performancePoints.map { it.descriptor }.sorted(),
                    ).joinToString(":")
                )
            }
            add(facts.display.supportedRefreshRates.sorted().joinToString(","))
            add(facts.display.hdrTypes.map { it.name }.sorted().joinToString(","))
            add(facts.surfaces.toString())
            add(appliedQuirks.map { it.quirk.id }.sorted().joinToString(","))
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(stableParts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AndroidStableCapabilityFingerprint(
            schemaVersion = CAPABILITY_SCHEMA_VERSION,
            value = digest,
        )
    }

    private companion object {
        const val CAPABILITY_SCHEMA_VERSION: Int = 1
    }
}
