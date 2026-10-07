package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow

/**
 * What Home fetches from a media server, and when (CLAUDE.md "recurring network work is delta-shaped,
 * lifecycle-bound, and cheap"; design 5.8). The contributor is only ever called under Home's own
 * `repeatOnLifecycle(RESUMED)` - there is NO timer here - so this policy answers "does a refresh now need
 * the network at all?":
 *  - delta: only the rows the user enabled for this server are fetched (none enabled -> no request, ever);
 *  - TTL: a fetched row set is reused until it ages out, is invalidated (a websocket `UserDataChanged` /
 *    `LibraryChanged`, an own playback report) or the user pulls to refresh;
 *  - backoff: a failing server is not hammered - 503 honours `Retry-After`, other failures back off
 *    exponentially; an offline server hides its rows and is retried only after the backoff window;
 *  - minimal payload: `Limit`, minimal `Fields`, `EnableTotalRecordCount=false` ([RowQuery]).
 */
internal object HomeRefreshPolicy {
    const val ROW_TTL_MS = 5 * 60_000L
    const val ROW_LIMIT = 20
    const val ROW_FIELDS = "Overview,ProviderIds"
    const val MAX_BACKOFF_MS = 10 * 60_000L
    private const val BASE_BACKOFF_MS = 30_000L
    private const val MAX_RETRY_AFTER_MS = 5 * 60_000L
    /** Consecutive failures after which the server counts as offline (rows hidden, badge in Settings). */
    const val OFFLINE_AFTER_FAILURES = 2

    data class ServerState(
        val lastFetchedAtMs: Long? = null,
        val invalidated: Boolean = false,
        val consecutiveFailures: Int = 0,
        val blockedUntilMs: Long? = null,
    )

    sealed interface Decision {
        /** Fetch exactly [rows] (a subset of what the user enabled). */
        data class Fetch(val rows: Set<MediaServerHomeRow>) : Decision
        data class Skip(val reason: SkipReason) : Decision
    }

    enum class SkipReason { NO_ROWS_ENABLED, FRESH, BACKING_OFF }

    fun decide(enabledRows: Set<MediaServerHomeRow>, state: ServerState, nowMs: Long, force: Boolean): Decision {
        if (enabledRows.isEmpty()) return Decision.Skip(SkipReason.NO_ROWS_ENABLED)
        val blockedUntil = state.blockedUntilMs
        if (blockedUntil != null && nowMs < blockedUntil && !force) return Decision.Skip(SkipReason.BACKING_OFF)
        val last = state.lastFetchedAtMs
        val fresh = last != null && !state.invalidated && nowMs - last < ROW_TTL_MS
        if (fresh && !force) return Decision.Skip(SkipReason.FRESH)
        return Decision.Fetch(enabledRows)
    }

    /** One list with no per-row choice (a library on Home): the same TTL / backoff gate, fetched whole or not at all. */
    fun shouldFetchList(state: ServerState, nowMs: Long, force: Boolean): Boolean {
        val blockedUntil = state.blockedUntilMs
        if (blockedUntil != null && nowMs < blockedUntil && !force) return false
        val last = state.lastFetchedAtMs
        val fresh = last != null && !state.invalidated && nowMs - last < ROW_TTL_MS
        return force || !fresh
    }

    fun afterSuccess(state: ServerState, nowMs: Long): ServerState =
        ServerState(lastFetchedAtMs = nowMs, invalidated = false, consecutiveFailures = 0, blockedUntilMs = null)

    /** A 503 honours the server's `Retry-After` (seconds, capped); anything else backs off 30 s, 1 min, 2 min ... up to 10 min. */
    fun afterFailure(state: ServerState, nowMs: Long, httpStatus: Int?, retryAfterSeconds: Long?): ServerState {
        val failures = state.consecutiveFailures + 1
        val delayMs = if (httpStatus == 503 && retryAfterSeconds != null && retryAfterSeconds > 0) {
            minOf(retryAfterSeconds * 1_000L, MAX_RETRY_AFTER_MS)
        } else {
            val exponent = (failures - 1).coerceAtMost(5)
            minOf(BASE_BACKOFF_MS shl exponent, MAX_BACKOFF_MS)
        }
        return state.copy(consecutiveFailures = failures, blockedUntilMs = nowMs + delayMs)
    }

    fun invalidate(state: ServerState): ServerState = state.copy(invalidated = true)

    fun isOffline(state: ServerState): Boolean = state.consecutiveFailures >= OFFLINE_AFTER_FAILURES

    /** The query a row costs: the minimum that renders a card. */
    data class RowQuery(val limit: Int, val fields: String, val enableTotalRecordCount: Boolean)

    val rowQuery = RowQuery(limit = ROW_LIMIT, fields = ROW_FIELDS, enableTotalRecordCount = false)

    /** A server's own row hides items Tuvora's Continue Watching already shows, so nothing appears twice (design 5.7 dedupe). */
    fun dedupeAgainstContinueWatching(rowItemContentIds: List<String>, continueWatchingContentIds: Set<String>): List<String> =
        rowItemContentIds.filterNot { it in continueWatchingContentIds }
}
