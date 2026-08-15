package com.nuvio.tv.core.memory

import android.app.ActivityManager
import android.content.Context

/**
 * TV's honest self-measurement: ActivityManager's isLowRamDevice + memoryClass — the OS's
 * own words for "how much heap should you want" (twin of mobile's androidMain probe; note
 * this is deliberately NOT DeviceClass.isLowRam's totalMem heuristic — the tier follows
 * the memoryClass the OS actually enforces). Cached after the first read and mirrored
 * into [AppMemory] as the base tier.
 */
internal object AndroidMemoryTierProbe {

    @Volatile
    private var cached: MemoryTier? = null

    fun tier(context: Context): MemoryTier = cached ?: run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        // A null ActivityManager never happens in practice; not-low is the existing
        // default (the old raw check also fell to the bigger cache on null).
        val tier = if (am == null) MemoryTier.HIGH
        else MemoryTierPolicy.androidTier(am.isLowRamDevice, am.memoryClass)
        cached = tier
        AppMemory.setBaseTier(tier)
        tier
    }
}
