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
 * retry, a probe or an engine failover: the re-mint runs first, once per stream. A refreshed link
 * that then played [REARM_AFTER_PLAYED_MS] of content earns one more shot, so a long movie on a
 * short-TTL portal survives repeated expiries, while a link that dies again at once still ends in
 * the error (at most one mint per minute of playback, never a mint loop).
 */
internal object IptvLinkRefreshPolicy {
    const val REARM_AFTER_PLAYED_MS = 60_000L

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
        playedSinceRefreshMs: Long = 0L,
    ): Lane? {
        if (isCatchUp) return null
        if (alreadyAttempted && playedSinceRefreshMs < REARM_AFTER_PLAYED_MS) return null
        if (httpStatus == null || !isIptvRefreshableHttpStatus(httpStatus)) return null
        if (iptvId != null) return if (isLiveId) null else Lane.IptvId(iptvId)
        return if (addonName.isNullOrBlank()) null else Lane.Matched
    }
}

/**
 * Who refreshes an expired link (sync 15): upstream's add-on re-fetch
 * ([tryRefreshExpiredLinkAfterError]) only for add-on/debrid sources. IPTV ids, TMDB-matched IPTV copies
 * (a `tt…` id whose addonName is a playlist name), catch-up replays and any stream the fork's IPTV
 * re-mint has already handled stay with [IptvLinkRefreshPolicy] — a Stalker mint yields a new URL each
 * time, so upstream's per-URL guard cannot stop a mint loop.
 *
 * Backstop for every source: at most [MAX_CONSECUTIVE_ADDON_REFRESHES] refreshes in a row, counted
 * independently of the URL (each refresh may return a never-seen URL), re-armed only once the refreshed
 * link has played [HEALTHY_PLAYBACK_RESET_MS] past where it resumed. Same shape as NuvioMobile's
 * PlayerCredentialRefreshPolicy (decide on a consecutive count + hasRecovered on played time).
 */
internal object ExpiredLinkRefreshOwnership {
    const val MAX_CONSECUTIVE_ADDON_REFRESHES = 3
    const val HEALTHY_PLAYBACK_RESET_MS = 60_000L

    fun addonRefreshAllowed(
        iptvId: String?,
        isCatchUp: Boolean,
        iptvRefreshAttempted: Boolean,
        isMatchedIptvSource: Boolean,
        consecutiveRefreshes: Int,
    ): Boolean =
        iptvId == null &&
            !isMatchedIptvSource &&
            !isCatchUp &&
            !iptvRefreshAttempted &&
            consecutiveRefreshes < MAX_CONSECUTIVE_ADDON_REFRESHES

    /**
     * The consecutive count after the playhead reached [positionMs]: back to 0 once playback advanced
     * [HEALTHY_PLAYBACK_RESET_MS] past [refreshBaselineMs] (the content position the last refresh resumed at),
     * unchanged otherwise. A link that dies right after a refresh never gets that far, so it cannot re-arm.
     */
    fun consecutiveAfterPlayback(consecutiveRefreshes: Int, positionMs: Long, refreshBaselineMs: Long): Int =
        if (consecutiveRefreshes > 0 && positionMs - refreshBaselineMs >= HEALTHY_PLAYBACK_RESET_MS) 0
        else consecutiveRefreshes
}
