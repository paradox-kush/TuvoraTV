package com.nuvio.tv.core.iptv.refresh

/**
 * Pure due-selection for the IPTV auto-refresh worker, extracted so the "which playlists are due"
 * decision is unit-testable without WorkManager, the DB, or the network.
 *
 * A playlist is due when it is enabled, opts into auto-refresh ([autoRefreshHours] > 0), and its last
 * refresh is at least [autoRefreshHours] old (or it has never been refreshed). [autoRefreshHours] == 0
 * means "off" — never due.
 */
data class RefreshCandidate(
    val playlistId: String,
    val enabled: Boolean,
    val autoRefreshHours: Int,
    /** Epoch-ms of the last successful refresh, or null if never refreshed. */
    val lastRefreshMs: Long?,
)

object IptvRefreshDue {
    private const val HOUR_MS = 3_600_000L

    /** True when [candidate] is due for a refresh at [nowMs]. */
    fun isDue(candidate: RefreshCandidate, nowMs: Long): Boolean {
        if (!candidate.enabled) return false
        if (candidate.autoRefreshHours <= 0) return false
        val last = candidate.lastRefreshMs ?: return true // never refreshed → due now
        return nowMs - last >= candidate.autoRefreshHours * HOUR_MS
    }

    /** The subset of [candidates] due at [nowMs]. */
    fun duePlaylists(candidates: List<RefreshCandidate>, nowMs: Long): List<RefreshCandidate> =
        candidates.filter { isDue(it, nowMs) }

    /**
     * The periodic worker's run interval, in hours: the SHORTEST enabled auto-refresh across
     * [candidates] (so the every-N-hours playlist is honoured), clamped to WorkManager's 15-minute
     * floor upstream. Null when no playlist opts into auto-refresh (the worker can stay unscheduled).
     */
    fun shortestIntervalHours(candidates: List<RefreshCandidate>): Int? =
        candidates.filter { it.enabled && it.autoRefreshHours > 0 }
            .minOfOrNull { it.autoRefreshHours }
}
