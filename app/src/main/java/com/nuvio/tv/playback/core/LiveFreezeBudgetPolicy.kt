package com.nuvio.tv.playback.core

/**
 * A channel that keeps freezing ends in a terminal error with Retry instead of looping forever.
 *
 * The reconnect budget (6 per 120 s) only bounds fast cycles, and a passed probation hands it back,
 * so a stream that freezes every 25 to 40 s would reconnect without end. This counts freezes of one
 * channel over a longer window, independent of how the reconnects were timed (bounded-load rule:
 * every wait ends in Loaded, Empty or Failed).
 */
object LiveFreezeBudgetPolicy {
    const val MAX_FREEZES = 6
    const val WINDOW_MS = 10 * 60_000L

    /** Drops freezes older than the window; [freezes] are epoch ms, oldest first. */
    fun prune(freezes: MutableCollection<Long>, nowEpochMs: Long) {
        val cutoff = nowEpochMs - WINDOW_MS
        freezes.removeAll { it <= cutoff }
    }

    /** True when the freezes still inside the window (including the one just seen) use the budget up. */
    fun exhausted(freezes: Collection<Long>, nowEpochMs: Long): Boolean =
        freezes.count { it > nowEpochMs - WINDOW_MS } >= MAX_FREEZES
}
