package com.nuvio.tv.core.iptv

/**
 * F48: the hub search runs against a catalog index (Xtream's persistent match index, an M3U's ingested catalog).
 * No index means the playlist could not be read — never "no matches". Mirrors the KMP apps' `IptvSearchReadiness`.
 */
class IptvSearchNotReadyException(message: String) : RuntimeException(message)

object IptvSearchReadiness {
    /** True when a catalog index built at [builtAtMs] exists (a build time of null or <= 0 means none). */
    fun isReady(builtAtMs: Long?): Boolean = builtAtMs != null && builtAtMs > 0L

    fun require(builtAtMs: Long?, what: String) {
        if (!isReady(builtAtMs)) throw IptvSearchNotReadyException("$what is not available yet")
    }
}
