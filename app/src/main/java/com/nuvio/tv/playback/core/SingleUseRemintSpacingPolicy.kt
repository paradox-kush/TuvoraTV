package com.nuvio.tv.playback.core

/**
 * B259: how long an AUTOMATIC re-mint of a single-use (Stalker create_link) link waits after the
 * previous connection ended.
 *
 * The evidence: a Stalker line with a one-connection limit answered a request made right after a
 * failed one with HTTP 200 and an EMPTY body; requests 75 s apart behaved normally. The reconnect
 * loop's first attempt (PlaybackPolicy.RECONNECT_DELAYS_MS[0] = 0 ms), the in-place recovery and the
 * engine hand-off all re-minted and reconnected at once, inside that window, so the recovery was
 * spent on a connection the provider could only refuse.
 *
 * The value, [MIN_GAP_MS] = 5 s: the Infomir middleware's default lifetime of a temporary play link
 * (`tv_tmp_link_ttl = 5` and `nginx_secure_link_ttl = 5`, stalker_portal/server/config.ini; used in
 * itv.class.php). After it, no link minted for the previous attempt is still valid anywhere, so
 * per-link connection accounting on the portal side has lapsed. Measured from the end of the previous
 * connection (the session's release proof), so the reconnect loop's own 1 s / 2 s back-off counts
 * toward it rather than adding to it.
 *
 * What it never touches: an [ProviderResolutionTrigger.INITIAL] resolution — a tune, a zap, the
 * viewer's Retry (which also resets the panel breaker, UX345) — is never delayed; reusable Xtream/M3U
 * links keep their cadence; the reconnect budget (3 per incident, 6 per 120 s) is unchanged, only the
 * attempts are spaced.
 */
object SingleUseRemintSpacingPolicy {
    const val MIN_GAP_MS: Long = 5_000L

    /**
     * Milliseconds to wait before resolving. [previousLinkSingleUse]: the link being replaced was
     * single-use (`TransientLoadRetryPolicy.SESSION_ONLY`). [connectionEndedAtMs]: when its connection
     * was proven closed, or null when none was recorded. [identitySwitchAfterAuthRejection]: the
     * provider refused the previous attempt at open (HTTP 401/403 before any media) and this re-mint
     * presents a different stream identity (StalkerStreamIdentityPolicy). A refusal at open means
     * no connection was ever held, so there is nothing to wait out; every other re-mint keeps the gap.
     */
    fun delayBeforeResolveMs(
        trigger: ProviderResolutionTrigger,
        previousLinkSingleUse: Boolean,
        connectionEndedAtMs: Long?,
        nowMs: Long,
        identitySwitchAfterAuthRejection: Boolean = false,
    ): Long {
        if (trigger == ProviderResolutionTrigger.INITIAL || !previousLinkSingleUse) return 0L
        if (identitySwitchAfterAuthRejection) return 0L
        val endedAt = connectionEndedAtMs ?: return 0L
        val elapsed = (nowMs - endedAt).coerceAtLeast(0L)
        return (MIN_GAP_MS - elapsed).coerceIn(0L, MIN_GAP_MS)
    }
}
