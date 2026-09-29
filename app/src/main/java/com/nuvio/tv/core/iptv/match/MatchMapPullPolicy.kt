package com.nuvio.tv.core.iptv.match

/**
 * Per-(account, provider) pull cursor for the `iptv_tmdb_map` mirror, stored in the SAME SQLite
 * database as the mappings and written in the same transaction as the rows it covers — so a wiped
 * or purged mirror loses its cursor with it and the next pull is a full re-bootstrap.
 *
 * [markMs] = the highest remote `updated_at_ms` this mirror has fully applied (the delta cursor).
 * [lastPullAtMs] / [lastFullPullAtMs] = local clock at the last successful pull / full pull.
 */
data class MatchMapCursor(
    val markMs: Long,
    val lastPullAtMs: Long,
    val lastFullPullAtMs: Long,
)

/** What one pull should do. [Fetch.sinceMs] null = full pull (no `updated_at_ms` filter). */
sealed interface MatchMapPullPlan {
    data object Skip : MatchMapPullPlan
    data class Fetch(val sinceMs: Long?) : MatchMapPullPlan {
        val isFull: Boolean get() = sinceMs == null
    }
}

/**
 * B78 (egress): the match-map pull used to select EVERY row for the provider once per app session
 * (the "pulled" set was in-memory only), so each launch re-downloaded the whole mirror to find the
 * handful of rows another device had added. This decides what a pull fetches — pure, so it tests
 * without SQLite or the network (house pattern: RadarLiveRefreshPolicy).
 *
 *  - no cursor (fresh install, wiped DB, purged provider, new account) → full bootstrap;
 *  - pulled less than [MIN_PULL_INTERVAL_MS] ago → no request at all;
 *  - otherwise a DELTA: rows with `updated_at_ms >= mark - OVERLAP_MS`. `updated_at_ms` is CLIENT
 *    epoch millis (last-write-wins), so the overlap absorbs equal timestamps and small cross-device
 *    clock skew; overlapping rows are deduplicated by the LWW compare in [shouldApply];
 *  - every [FULL_RESYNC_INTERVAL_MS] a full pull heals what a client-clock cursor can miss by
 *    construction: a mapping pushed LATE (made offline, pushed on a later session) or by a device
 *    whose clock lags by more than the overlap carries an `updated_at_ms` already behind our mark.
 *    The table is a cache — a missed row costs a local re-resolve, never data — and per-provider
 *    rows are small (prod 2026-09-28: max 274 rows / ~32 KB per account+provider), so a weekly
 *    full pull is cheap insurance.
 *
 * A cursor from the future (device clock moved backwards, or a far-future row) is distrusted and
 * re-bootstrapped rather than letting it hide every later row.
 */
object MatchMapPullPolicy {
    const val OVERLAP_MS: Long = 10 * 60_000L
    const val MIN_PULL_INTERVAL_MS: Long = 15 * 60_000L
    const val FULL_RESYNC_INTERVAL_MS: Long = 7 * 24 * 60 * 60_000L
    /** How far past local "now" a remote timestamp may move the mark (clock-skew allowance). */
    const val FUTURE_TOLERANCE_MS: Long = 5 * 60_000L
    /** Page size for the pull; PostgREST caps responses at max_rows (1000 on prod). */
    const val PAGE_SIZE: Int = 1000

    fun plan(cursor: MatchMapCursor?, nowMs: Long): MatchMapPullPlan {
        if (cursor == null || cursor.markMs <= 0L) return MatchMapPullPlan.Fetch(sinceMs = null)
        val fromTheFuture = cursor.markMs > nowMs + FUTURE_TOLERANCE_MS ||
            cursor.lastPullAtMs > nowMs || cursor.lastFullPullAtMs > nowMs
        if (fromTheFuture) return MatchMapPullPlan.Fetch(sinceMs = null)
        if (nowMs - cursor.lastFullPullAtMs >= FULL_RESYNC_INTERVAL_MS) return MatchMapPullPlan.Fetch(sinceMs = null)
        if (nowMs - cursor.lastPullAtMs < MIN_PULL_INTERVAL_MS) return MatchMapPullPlan.Skip
        return MatchMapPullPlan.Fetch(sinceMs = cursor.markMs - OVERLAP_MS)
    }

    /** Last-write-wins against the local mirror: apply only rows strictly newer than what we hold. */
    fun shouldApply(remoteUpdatedAtMs: Long, localUpdatedAtMs: Long?): Boolean =
        localUpdatedAtMs == null || remoteUpdatedAtMs > localUpdatedAtMs

    /**
     * The cursor after applying one fetched page. Never moves the mark backwards; a remote
     * timestamp beyond `now + FUTURE_TOLERANCE_MS` only advances it to that bound, so a single
     * far-future row cannot hide every row written after it.
     */
    fun advance(
        cursor: MatchMapCursor?,
        pageMaxUpdatedAtMs: Long?,
        full: Boolean,
        nowMs: Long,
    ): MatchMapCursor {
        // A full pull re-derives the mark from scratch (it may be healing a future-poisoned cursor).
        val previousMark = if (full) 0L else (cursor?.markMs ?: 0L)
        val candidate = pageMaxUpdatedAtMs?.coerceAtMost(nowMs + FUTURE_TOLERANCE_MS) ?: previousMark
        return MatchMapCursor(
            markMs = maxOf(previousMark, candidate),
            lastPullAtMs = nowMs,
            lastFullPullAtMs = if (full) nowMs else (cursor?.lastFullPullAtMs ?: 0L),
        )
    }
}

/** One remote `iptv_tmdb_map` row, platform-neutral. */
data class RemoteMapping(
    val kind: MatchKind,
    val tmdb: Int,
    val sid: Int?,
    val matchedName: String?,
    val updatedAtMs: Long,
)

/** Port: the remote table. [sinceMs] null = no `updated_at_ms` filter. Ordered by `updated_at_ms` ascending. */
fun interface MatchMapRemote {
    suspend fun fetchPage(provider: String, sinceMs: Long?, offset: Int, limit: Int): List<RemoteMapping>
}

/** Port: the local mirror. [applyPage] writes the rows (LWW) AND the cursor in ONE transaction. */
interface MatchMapStore {
    suspend fun readCursor(owner: String, provider: String): MatchMapCursor?
    suspend fun applyPage(owner: String, provider: String, rows: List<RemoteMapping>, cursor: MatchMapCursor): Int
}

data class MatchMapPullResult(val requests: Int, val fetched: Int, val applied: Int, val full: Boolean?)

/** Runs one pull per [MatchMapPullPolicy]: paged, each page applied atomically with its cursor. */
object MatchMapPuller {
    suspend fun pull(
        owner: String,
        provider: String,
        nowMs: Long,
        store: MatchMapStore,
        remote: MatchMapRemote,
    ): MatchMapPullResult {
        var cursor = store.readCursor(owner, provider)
        val plan = MatchMapPullPolicy.plan(cursor, nowMs)
        if (plan !is MatchMapPullPlan.Fetch) return MatchMapPullResult(requests = 0, fetched = 0, applied = 0, full = null)
        var requests = 0
        var fetched = 0
        var applied = 0
        var offset = 0
        var first = true
        while (true) {
            val page = remote.fetchPage(provider, plan.sinceMs, offset, MatchMapPullPolicy.PAGE_SIZE)
            requests++
            fetched += page.size
            // Only the first page of a full pull resets the mark; later pages build on it.
            cursor = MatchMapPullPolicy.advance(cursor, page.maxOfOrNull { it.updatedAtMs }, full = plan.isFull && first, nowMs = nowMs)
            applied += store.applyPage(owner, provider, page, cursor)
            first = false
            if (page.size < MatchMapPullPolicy.PAGE_SIZE) break
            offset += page.size
        }
        return MatchMapPullResult(requests, fetched, applied, full = plan.isFull)
    }
}
