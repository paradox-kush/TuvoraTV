package com.nuvio.tv.playback.android

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager
import android.view.Display
import com.nuvio.tv.playback.core.AudioCodec
import com.nuvio.tv.playback.core.AudioRoute
import com.nuvio.tv.playback.core.HdrType
import com.nuvio.tv.playback.core.ThermalState
import com.nuvio.tv.playback.core.VideoCodec
import com.nuvio.tv.playback.core.VideoDimensions

/** Thin, exception-contained Android API reader. It makes no playback decisions. */
internal class FrameworkAndroidCapabilitySource(
    context: Context,
    /** Supplied by the active engine's AudioTrack/AudioRouting owner when routing exists. */
    private val routedAudioDevice: () -> AudioDeviceInfo? = { null },
) : AndroidCapabilitySource {
    private val appContext = context.applicationContext

    override fun read(): AndroidPlatformCapabilityFacts {
        val codecs = readCodecs()
        val resources = readResources(codecs)
        return AndroidPlatformCapabilityFacts(
            device = AndroidDeviceFacts(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                hardware = Build.HARDWARE.orEmpty(),
                board = Build.BOARD.orEmpty(),
                firmware = Build.DISPLAY.orEmpty(),
            ),
            apiLevel = Build.VERSION.SDK_INT,
            codecs = codecs,
            display = readDisplay(),
            audio = readAudioRoute(),
            resources = resources,
            surfaces = readSurfaces(),
        )
    }

    private fun readCodecs(): List<AndroidVideoDecoderFacts> = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filterNot(MediaCodecInfo::isEncoder)
            .flatMap { info ->
                info.supportedTypes.asSequence().mapNotNull { mime ->
                    val codec = mime.toVideoCodec() ?: return@mapNotNull null
                    runCatching { info.toFacts(mime, codec) }.getOrNull()
                }
            }
            .sortedBy(AndroidVideoDecoderFacts::stableId)
            .toList()
    }.getOrDefault(emptyList())

    private fun MediaCodecInfo.toFacts(mime: String, codec: VideoCodec): AndroidVideoDecoderFacts {
        val caps = getCapabilitiesForType(mime)
        val video = caps.videoCapabilities
        val sizeRateSupport = video?.let { capabilities ->
            STANDARD_SIZE_RATE_PROBES.map { probe ->
                probe.copy(
                    supported = runCatching {
                        capabilities.areSizeAndRateSupported(
                            probe.dimensions.width,
                            probe.dimensions.height,
                            probe.frameRate,
                        )
                    }.getOrDefault(false),
                )
            }
        }.orEmpty()
        val points = if (Build.VERSION.SDK_INT >= 29) {
            video?.supportedPerformancePoints.orEmpty().map {
                AndroidCodecPerformancePoint(descriptor = it.toString())
            }
        } else {
            emptyList()
        }
        return AndroidVideoDecoderFacts(
            stableId = "$name|${mime.lowercase()}",
            codec = codec,
            hardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) isHardwareAccelerated else !name.isSoftwareCodecName(),
            softwareOnly = if (Build.VERSION.SDK_INT >= 29) isSoftwareOnly else name.isSoftwareCodecName(),
            vendorProvided = if (Build.VERSION.SDK_INT >= 29) isVendor else !name.startsWith("OMX.google.", true),
            securePlayback = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback),
            widthRange = video?.supportedWidths?.let { it.lower..it.upper },
            heightRange = video?.supportedHeights?.let { it.lower..it.upper },
            globalFrameRateRange = video?.supportedFrameRates?.let { it.lower..it.upper },
            sizeRateSupport = sizeRateSupport,
            profileLevels = caps.profileLevels.mapTo(linkedSetOf()) { "${it.profile}:${it.level}" },
            maxSupportedInstances = if (Build.VERSION.SDK_INT >= 23) caps.maxSupportedInstances else null,
            performancePoints = points,
        )
    }

    private fun readDisplay(): AndroidDisplayFacts {
        val display = (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val currentMode = display?.mode
        val currentDimensions = VideoDimensions(
            currentMode?.physicalWidth?.coerceAtLeast(1) ?: 1,
            currentMode?.physicalHeight?.coerceAtLeast(1) ?: 1,
        )
        val modes = display?.supportedModes.orEmpty()
        val refreshRates = buildSet {
            modes.mapTo(this) { it.refreshRate.toDouble() }
            currentMode?.refreshRate?.toDouble()?.let(::add)
        }
        return AndroidDisplayFacts(
            currentDimensions = currentDimensions,
            supportedRefreshRates = refreshRates,
            hdrTypes = (display?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()).asSequence().mapNotNull {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrType.HDR10
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> HdrType.HDR10_PLUS
                    Display.HdrCapabilities.HDR_TYPE_HLG -> HdrType.HLG
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrType.DOLBY_VISION
                    else -> null
                }
            }.toSet(),
            modeSwitchSupported = modes.map { Triple(it.physicalWidth, it.physicalHeight, it.refreshRate) }
                .distinct().size > 1,
        )
    }

    private fun readAudioRoute(): AndroidAudioRouteFacts {
        // Connected devices are candidates, not proof of the route used by an active AudioTrack.
        val selected = runCatching(routedAudioDevice).getOrNull()
        return AndroidAudioRouteFacts(
            route = selected?.type.toAudioRoute(),
            encodedFormats = (selected?.encodings ?: intArrayOf()).asSequence()
                .mapNotNull { it.toAudioCodec() }
                .toSet(),
            maxChannelCount = (selected?.channelCounts ?: intArrayOf()).maxOrNull()?.coerceAtLeast(2) ?: 2,
            // Offload depends on the concrete MediaFormat/AudioAttributes, not merely the route.
            offloadSupported = false,
        )
    }

    private fun readResources(codecs: List<AndroidVideoDecoderFacts>): AndroidResourceFacts {
        val activity = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) }
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus.toThermalState() else ThermalState.UNKNOWN
        return AndroidResourceFacts(
            availableMemoryBytes = memory.availMem.coerceAtLeast(0L),
            lowMemory = memory.lowMemory,
            thermalState = thermal,
            concurrentDecoderBudget = conservativeConcurrentDecoderBudget(codecs),
        )
    }

    private fun readSurfaces(): AndroidSurfaceFacts = AndroidSurfaceFacts(
        surfaceViewAvailable = true,
        textureViewAvailable = true,
        // GLES version, memory state, and secure decoders do not prove EGL/render/surface behavior.
        gpuRenderingProof = AndroidCapabilityProof.UNPROBED,
        secureSurfaceProof = AndroidCapabilityProof.UNPROBED,
        secureGpuRenderingProof = AndroidCapabilityProof.UNPROBED,
    )

    private fun String.isSoftwareCodecName(): Boolean =
        startsWith("OMX.google.", true) || startsWith("c2.android.", true) || contains("software", true)

    private fun String.toVideoCodec(): VideoCodec? = when (lowercase()) {
        "video/avc" -> VideoCodec.AVC
        "video/hevc" -> VideoCodec.HEVC
        "video/av01" -> VideoCodec.AV1
        "video/x-vnd.on2.vp9" -> VideoCodec.VP9
        "video/mpeg2" -> VideoCodec.MPEG2
        "video/mp4v-es" -> VideoCodec.MPEG4
        "video/wvc1", "video/x-ms-wmv" -> VideoCodec.VC1
        "video/dolby-vision" -> VideoCodec.DOLBY_VISION
        else -> null
    }

    private fun Int.toAudioCodec(): AudioCodec? = when (this) {
        AudioFormat.ENCODING_AAC_LC, AudioFormat.ENCODING_AAC_HE_V1, AudioFormat.ENCODING_AAC_HE_V2 -> AudioCodec.AAC
        AudioFormat.ENCODING_AC3 -> AudioCodec.AC3
        AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_E_AC3_JOC -> AudioCodec.EAC3
        AudioFormat.ENCODING_DOLBY_TRUEHD -> AudioCodec.TRUEHD
        AudioFormat.ENCODING_DTS -> AudioCodec.DTS
        AudioFormat.ENCODING_DTS_HD -> AudioCodec.DTS_HD
        AudioFormat.ENCODING_MP3 -> AudioCodec.MP3
        AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_FLOAT -> AudioCodec.PCM
        else -> null
    }

    private fun Int?.toAudioRoute(): AudioRoute = when (this) {
        AudioDeviceInfo.TYPE_HDMI_EARC -> AudioRoute.HDMI_EARC
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> AudioRoute.HDMI
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> AudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioRoute.USB
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.TV_SPEAKERS
        else -> AudioRoute.UNKNOWN
    }

    private fun Int?.toThermalState(): ThermalState = when (this) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
        PowerManager.THERMAL_STATUS_MODERATE, PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
        PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
        else -> ThermalState.UNKNOWN
    }

    private companion object {
        val STANDARD_SIZE_RATE_PROBES = listOf(
            AndroidCodecSizeRateSupport(VideoDimensions(1280, 720), frameRate = 60.0, supported = false),
            AndroidCodecSizeRateSupport(VideoDimensions(1920, 1080), frameRate = 60.0, supported = false),
            AndroidCodecSizeRateSupport(VideoDimensions(3840, 2160), frameRate = 30.0, supported = false),
            AndroidCodecSizeRateSupport(VideoDimensions(3840, 2160), frameRate = 60.0, supported = false),
        )
    }
}

/** A global budget may use only hardware decoder hints and never exceed their weakest known hint. */
internal fun conservativeConcurrentDecoderBudget(codecs: List<AndroidVideoDecoderFacts>): Int {
    val hardware = codecs.filter { it.hardwareAccelerated && !it.softwareOnly }
    if (hardware.isEmpty() || hardware.any { it.maxSupportedInstances == null }) return 1
    return hardware.minOf { it.maxSupportedInstances.orEmptyPositive() }
}

private fun Int?.orEmptyPositive(): Int = this?.coerceAtLeast(1) ?: 1
