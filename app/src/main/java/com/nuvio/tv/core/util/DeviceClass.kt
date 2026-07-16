package com.nuvio.tv.core.util

import android.app.ActivityManager
import android.content.Context

/**
 * RAM-class check for budget TV hardware (Fire TV Stick / Onn 4K class).
 * `ActivityManager.isLowRamDevice` is false on 2GB Google TV boxes, so we
 * gate on physical RAM instead. Threshold matches the 2GB marketing tier
 * (which reports ~1.97GB usable).
 */
object DeviceClass {

    private const val LOW_RAM_THRESHOLD_BYTES = (2.3 * 1024 * 1024 * 1024).toLong()

    @Volatile
    private var cachedLowRam: Boolean? = null

    fun isLowRam(context: Context): Boolean {
        cachedLowRam?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // totalMem == 0 (unknown) counts as not-low so we never degrade capable devices.
        val lowRam = info.totalMem in 1 until LOW_RAM_THRESHOLD_BYTES
        cachedLowRam = lowRam
        return lowRam
    }
}
