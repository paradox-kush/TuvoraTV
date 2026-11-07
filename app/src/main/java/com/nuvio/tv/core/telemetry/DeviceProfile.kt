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
}
