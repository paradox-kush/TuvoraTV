package com.nuvio.tv.core.network

/**
 * B24 — core-owned holder for the server-driven IPTV-playlist v2 rollout string from the sync-backend
 * manifest (twin of Mobile's PlaylistSyncRolloutSignal). Written by the sync-backend layer when it
 * applies a manifest; read by the IPTV activation policy. Null = manifest carried no override.
 */
object PlaylistSyncRolloutSignal {
    @Volatile
    var raw: String? = null
}
