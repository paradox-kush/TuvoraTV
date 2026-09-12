package com.nuvio.tv.core.iptv

/**
 * B24 — production activation policy for the v2 revision-contract sync path (twin of Mobile's
 * PlaylistSyncActivation). Pure decision; the load-bearing rule is that a profile which has already
 * committed a v2 write is NEVER downgraded to the destructive v1 full-replace when v2 is turned off —
 * it PAUSES (pending retained) instead.
 */
enum class PlaylistV2Rollout {
    ENABLED,      // v2 active everywhere the backend is reachable
    DEBUG_ONLY,   // v2 only under a debug build pointing at a local/dev backend (pre-rollout default)
    DISABLED,     // kill switch: un-adopted -> v1; adopted -> PAUSE (never v1)
    ;

    companion object {
        fun parse(raw: String?, fallback: PlaylistV2Rollout = DEBUG_ONLY): PlaylistV2Rollout =
            when (raw?.trim()?.lowercase()) {
                "enabled", "on", "true", "1" -> ENABLED
                "disabled", "off", "false", "0" -> DISABLED
                "debug_only", "debug", "auto" -> DEBUG_ONLY
                else -> fallback
            }
    }
}

enum class PlaylistSyncActivation { V2_ACTIVE, V1_LEGACY, V2_PAUSED }

object PlaylistSyncActivationPolicy {
    fun activation(
        rollout: PlaylistV2Rollout,
        isDebugLocalDev: Boolean,
        profileHasAdoptedV2: Boolean,
    ): PlaylistSyncActivation {
        val globallyOn = when (rollout) {
            PlaylistV2Rollout.ENABLED -> true
            PlaylistV2Rollout.DISABLED -> false
            PlaylistV2Rollout.DEBUG_ONLY -> isDebugLocalDev
        }
        return when {
            globallyOn -> PlaylistSyncActivation.V2_ACTIVE
            profileHasAdoptedV2 -> PlaylistSyncActivation.V2_PAUSED
            else -> PlaylistSyncActivation.V1_LEGACY
        }
    }
}
