package com.nuvio.tv.core.iptv

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.PlaylistSyncRolloutSignal

/**
 * B24 — resolves whether and how the v2 revision-contract sync path activates on NuvioTV (twin of
 * Mobile's PlaylistSyncConfig). Rollout is a build default (`DEBUG_ONLY` on release) overridable by the
 * sync-backend manifest (via [PlaylistSyncRolloutSignal]) without a client release. The per-profile
 * decision — including "never downgrade an adopted profile to v1" — lives in the pure
 * [PlaylistSyncActivationPolicy].
 */
object PlaylistSyncConfig {
    // B24 production activation (2026-09-12): v2 is ENABLED by default in released builds. The backend
    // guard (deployed to prod) enforces safety regardless of client, and adopted profiles are never
    // downgraded to the destructive v1 path (they pause). There is no fork-owned switch manifest host,
    // so activation is build-baked here rather than manifest-driven; a future kill switch would require
    // either a fork manifest host or another release.
    private val buildDefaultRollout: PlaylistV2Rollout = PlaylistV2Rollout.ENABLED

    val effectiveRollout: PlaylistV2Rollout
        get() = PlaylistSyncRolloutSignal.raw
            ?.let { PlaylistV2Rollout.parse(it, fallback = buildDefaultRollout) }
            ?: buildDefaultRollout

    val isDebugLocalDev: Boolean by lazy {
        BuildConfig.IS_DEBUG_BUILD && isLocalOrDevEndpoint(BuildConfig.SUPABASE_URL)
    }

    fun activationFor(profileHasAdoptedV2: Boolean): PlaylistSyncActivation =
        PlaylistSyncActivationPolicy.activation(effectiveRollout, isDebugLocalDev, profileHasAdoptedV2)

    fun recordsPending(profileHasAdoptedV2: Boolean): Boolean =
        activationFor(profileHasAdoptedV2) != PlaylistSyncActivation.V1_LEGACY

    /** Back-compat: would a FRESH (never-adopted) profile use v2 right now? Prefer [activationFor]. */
    val v2Enabled: Boolean
        get() = activationFor(profileHasAdoptedV2 = false) == PlaylistSyncActivation.V2_ACTIVE

    private fun isLocalOrDevEndpoint(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.contains("10.0.2.2") ||
            u.contains("127.0.0.1") ||
            u.contains("://localhost") ||
            u.contains("://192.168.") ||
            u.contains("://10.")
    }
}
