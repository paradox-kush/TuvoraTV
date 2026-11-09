package com.nuvio.tv.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Hardware facts that explain most device-specific failures (memory class, SoC, ABI).
 * Registered once per launch as PostHog super properties, so every event and every crash
 * report can be grouped by them. Contains no user data.
 */
object DeviceProfile {
    fun properties(context: Context): Map<String, Any> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return buildMap {
            put("hw_ram_total_mb", (mem.totalMem / (1024 * 1024)).toInt())
            put("hw_low_ram", am?.isLowRamDevice ?: false)
            put("hw_heap_limit_mb", am?.memoryClass ?: 0)
            put("hw_large_heap_limit_mb", am?.largeMemoryClass ?: 0)
            put("hw_abis", Build.SUPPORTED_ABIS.joinToString(","))
            put("hw_hardware", Build.HARDWARE.orEmpty())
            put("hw_board", Build.BOARD.orEmpty())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                put("hw_soc_manufacturer", Build.SOC_MANUFACTURER.orEmpty())
                put("hw_soc_model", Build.SOC_MODEL.orEmpty())
            }
            put("hw_sdk_int", Build.VERSION.SDK_INT)
        }
    }

    /**
     * Slower facts (codec list, display modes), gathered off the main thread after startup and
     * registered as super properties once ready. Everything is a capability flag or a number.
     */
    fun extended(context: Context): Map<String, Any> = buildMap {
        put("hw_cpu_cores", Runtime.getRuntime().availableProcessors())
        runCatching {
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val mode = display.mode
            put("hw_display_px", "${mode.physicalWidth}x${mode.physicalHeight}")
            put("hw_display_hz_max", display.supportedModes.maxOf { it.refreshRate }.toInt())
            val hdr = display.hdrCapabilities?.supportedHdrTypes?.map {
                when (it) {
                    android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dv"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "hdr10"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
                    android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10plus"
                    else -> "other"
                }
            }.orEmpty()
            put("hw_hdr", hdr.joinToString(","))
        }
        runCatching {
            val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            fun hw(mime: String): String = codecs
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                .let { list ->
                    if (list.isEmpty()) return@let "none"
                    val hardware = list.filter { c ->
                        if (android.os.Build.VERSION.SDK_INT >= 29) c.isHardwareAccelerated
                        else !c.name.startsWith("OMX.google.", true)
                    }
                    if (hardware.isEmpty()) "sw" else "hw"
                }
            put("hw_dec_h264", hw("video/avc"))
            put("hw_dec_hevc", hw("video/hevc"))
            put("hw_dec_av1", hw("video/av01"))
            put("hw_dec_vp9", hw("video/x-vnd.on2.vp9"))
            put("hw_dec_dv", hw("video/dolby-vision"))
        }
    }
}
