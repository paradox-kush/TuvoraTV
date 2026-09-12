package com.nuvio.tv.core.network

/**
 * B24 — client-side canary cohort resolver for the IPTV-playlist v2 rollout (twin of Mobile's
 * PlaylistV2CohortPolicy). v2 activation is not backend authorization (the v2 RPCs + revision contract
 * enforce safety regardless), so the cohort is a pure client-side feature-flag decision. Bucketing is
 * by a stable FNV-1a hash of the account id, so raising the percent only ADDS accounts. Returns a
 * resolved mode string parsed by PlaylistSyncConfig.
 */
object PlaylistV2CohortPolicy {

    fun bucketOf(userId: String?): Int {
        if (userId.isNullOrBlank()) return -1
        var hash = 0x811c9dc5.toInt()
        for (c in userId) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        val mod = hash % 100
        return if (mod < 0) mod + 100 else mod
    }

    fun resolveMode(
        rawMode: String?,
        percent: Int?,
        platforms: List<String>?,
        userId: String?,
        platform: String,
    ): String {
        val mode = rawMode?.trim()?.lowercase()
        if (mode != "enabled" && mode != "on" && mode != "true" && mode != "1") {
            return rawMode ?: "debug_only"
        }
        val allowed = platforms?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
        if (!allowed.isNullOrEmpty() && platform.trim().lowercase() !in allowed) return "disabled"
        val pct = (percent ?: 100).coerceIn(0, 100)
        if (pct <= 0) return "disabled"
        if (pct >= 100) return "enabled"
        val bucket = bucketOf(userId)
        return if (bucket in 0 until pct) "enabled" else "disabled"
    }
}
