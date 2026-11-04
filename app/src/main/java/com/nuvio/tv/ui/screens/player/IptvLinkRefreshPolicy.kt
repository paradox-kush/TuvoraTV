package com.nuvio.tv.ui.screens.player

/**
 * Stream-side HTTP statuses a fresh create_link can plausibly fix: the play token expired or was
 * consumed (401/410), or the portal session was rotated, e.g. a second device on the same MAC (403).
 * Everything else (404 removed, 5xx provider down) stays on the normal error path: hammering
 * create_link on a provider outage is how portals block MACs.
 */
internal fun isIptvRefreshableHttpStatus(code: Int): Boolean =
    code == 401 || code == 403 || code == 410

/**
 * Decides whether an engine error on upstream's VOD player re-mints the IPTV link (B1). Stalker
 * create_link links are single-use or short-lived, so the dead URL must never be replayed by a
 * retry, a probe or an engine failover: the re-mint runs first, once per stream.
 */
internal object IptvLinkRefreshPolicy {
    sealed interface Lane {
        /** An Xtream/Stalker VOD or episode id: re-mint by id. */
        data class IptvId(val id: String) : Lane

        /** A TMDB-matched IPTV copy: re-run the matcher for the account named by the stream's addon label. */
        data object Matched : Lane
    }

    fun lane(
        httpStatus: Int?,
        iptvId: String?,
        isLiveId: Boolean,
        isCatchUp: Boolean,
        addonName: String?,
        alreadyAttempted: Boolean,
    ): Lane? {
        if (alreadyAttempted || isCatchUp) return null
        if (httpStatus == null || !isIptvRefreshableHttpStatus(httpStatus)) return null
        if (iptvId != null) return if (isLiveId) null else Lane.IptvId(iptvId)
        return if (addonName.isNullOrBlank()) null else Lane.Matched
    }
}
