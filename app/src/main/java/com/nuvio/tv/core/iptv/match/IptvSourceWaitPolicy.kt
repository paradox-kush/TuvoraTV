package com.nuvio.tv.core.iptv.match

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * B63: how long a title's source list waits on one IPTV playlist.
 *
 * Every playlist is matched concurrently, but the list only SETTLES (auto-play picks, the loading
 * chips clear, Next Episode decides) once every job has finished — so one unreachable playlist held
 * every source list open for its connect timeout: 30 s per panel call on Xtream (two calls before the
 * host breaker opens), 15 s on Stalker. Measured on an Onn with two stale test playlists: ~28 s
 * between "Xtream match targets" and the list moving on.
 *
 * The rule:
 *  - within [PLAYLIST_BUDGET_MS] a playlist's answer is always taken;
 *  - past it, the playlist is waited on only while [othersSettled] says addon/plugin sources are
 *    still loading anyway — a panel slower than the budget but faster than the slowest addon still
 *    makes the list, at no cost to anyone;
 *  - once nothing else is pending, the playlist is cut: it contributes nothing to THIS list and its
 *    in-flight request is cancelled (no extra requests, no retry — egress stays the same or lower).
 *
 * Work the fetch started that outlives the request (a catalog index build runs in the resolver's
 * own scope) is unaffected, so a playlist cut while its first index builds shows up next time.
 * A host the process-wide [com.nuvio.tv.core.iptv.IptvPanelGuard] already knows is dead fast-fails
 * at the transport, so it finishes well inside the budget.
 */
object IptvSourceWaitPolicy {
    /** Inside the 5–8 s band: a healthy panel answers a resolve (0–3 info calls) well within it. */
    const val PLAYLIST_BUDGET_MS = 8_000L

    /**
     * Runs [fetch] for one playlist under the rule above. Returns its result, or null when the
     * playlist was cut. A failure inside the budget (or while others are pending) is rethrown.
     * Cancelling the caller cancels [fetch].
     */
    suspend fun <T : Any> await(
        othersSettled: Job,
        budgetMs: Long = PLAYLIST_BUDGET_MS,
        fetch: suspend () -> T,
    ): T? {
        // Deliberately NOT a child of the caller: once cut, a fetch parked in a call that ignores
        // cancellation (a blocking socket connect) must not hold the caller until its own timeout.
        // It is still cancelled — in the finally below — never left running unobserved.
        val work = CoroutineScope(currentCoroutineContext().minusKey(Job)).async { fetch() }
        try {
            withTimeoutOrNull(budgetMs) { work.await() }?.let { return it }
            return select {
                work.onAwait { it }
                othersSettled.onJoin { null }
            }
        } finally {
            if (!work.isCompleted) work.cancel()
        }
    }
}
