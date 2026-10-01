package com.nuvio.tv.core.iptv

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Step 0.3b — what one device has learned about one server of one playlist (device-local, never
 * synced; lives inside [ServerFailoverState], keyed by server index, and is reset with it).
 *
 * Every field has a default, so a state persisted by Step 0.3 (no stats) still loads.
 */
@Serializable
data class ServerLatencyStats(
    /** Exponentially weighted time-to-valid-answer in ms, null = no sample yet. */
    val ewmaMs: Long? = null,
    val samples: Int = 0,
    /** Epoch ms of the last sample folded in (drives the 24 h decay). */
    val lastSampleAtMs: Long = 0L,
    /** Epoch ms of the last fail-over-able failure; null = none (or it has since answered). */
    val lastFailAtMs: Long? = null,
)

/**
 * Step 0.3b — how long to wait on one attempt before starting its successor.
 *
 * `clamp(1.5 × ewma, 400 ms, 2500 ms)`; 1500 ms with no (fresh) samples; 200 ms when the server failed
 * within the last 10 minutes (its successor starts almost at once). RFC 8305 §5 recommends 250 ms
 * (min 100 ms, max 2 s) for a TCP SYN; the unit here is an HTTPS API round-trip over the WAN, so the
 * window is deliberately wider, but the 200 ms "known-bad" value stays above RFC's 100 ms floor.
 */
object FailoverStagger {
    const val DEFAULT_MS: Long = 1_500L
    const val MIN_MS: Long = 400L
    const val MAX_MS: Long = 2_500L
    const val RECENT_FAILURE_MS: Long = 200L
    const val RECENT_FAILURE_WINDOW_MS: Long = 10L * 60 * 1000
    const val EWMA_FACTOR: Double = 1.5

    fun compute(stats: ServerLatencyStats?, nowMs: Long): Long {
        if (stats == null) return DEFAULT_MS
        stats.lastFailAtMs?.let { failedAt ->
            if (nowMs - failedAt in 0 until RECENT_FAILURE_WINDOW_MS) return RECENT_FAILURE_MS
        }
        val ewma = stats.ewmaMs
        if (ewma != null && stats.samples > 0 && !FailoverLatencyStats.isStale(stats, nowMs)) {
            return (ewma * EWMA_FACTOR).roundToLong().coerceIn(MIN_MS, MAX_MS)
        }
        return DEFAULT_MS
    }
}

/**
 * Step 0.3b — the pure update rules for [ServerLatencyStats]. Updates that would not change what the
 * stagger does are dropped (return the same instance) so a healthy playlist does not rewrite its
 * prefs on every request.
 */
object FailoverLatencyStats {
    const val ALPHA: Double = 0.3
    /** A time-to-valid at or above this is an outlier, not a sample. */
    const val MAX_SAMPLE_MS: Long = 60_000L
    /** Samples older than this are dropped. */
    const val DECAY_MS: Long = 24L * 60 * 60 * 1000
    /** A new sample within this fraction of the current ewma (and fresh) changes nothing worth persisting. */
    const val MATERIAL_CHANGE: Double = 0.2
    const val REFRESH_MS: Long = 60L * 60 * 1000
    /** A repeat failure within this window of the last one changes nothing. */
    const val FAILURE_DEDUPE_MS: Long = 60_000L

    fun isStale(stats: ServerLatencyStats, nowMs: Long): Boolean = nowMs - stats.lastSampleAtMs > DECAY_MS

    /** [server] won a race (or answered a probe validly) after [timeToValidMs]. Also clears its failure mark. */
    fun onWin(stats: ServerLatencyStats?, timeToValidMs: Long, nowMs: Long): ServerLatencyStats? {
        val current = stats ?: ServerLatencyStats()
        val cleared = current.copy(lastFailAtMs = null)
        if (timeToValidMs < 0 || timeToValidMs >= MAX_SAMPLE_MS) {
            val pruned = prune(cleared, nowMs)
            return if (pruned == stats) stats else pruned
        }
        val old = cleared.ewmaMs
        val fresh = old != null && cleared.samples > 0 && !isStale(cleared, nowMs)
        val next = if (fresh) (ALPHA * timeToValidMs + (1 - ALPHA) * old!!).roundToLong() else timeToValidMs
        if (fresh && current.lastFailAtMs == null &&
            abs(next - old!!) <= (old * MATERIAL_CHANGE) && nowMs - cleared.lastSampleAtMs < REFRESH_MS
        ) return stats
        return cleared.copy(
            ewmaMs = next,
            samples = if (fresh) cleared.samples + 1 else 1,
            lastSampleAtMs = nowMs,
        )
    }

    /** [server] failed with a fail-over-able failure at [nowMs]. A cancelled loser must NEVER come here. */
    fun onFailure(stats: ServerLatencyStats?, nowMs: Long): ServerLatencyStats {
        val current = stats ?: ServerLatencyStats()
        val at = current.lastFailAtMs
        if (at != null && nowMs - at in 0 until FAILURE_DEDUPE_MS) return current
        return current.copy(lastFailAtMs = nowMs)
    }

    /** [stats] without what has decayed; null = nothing left worth storing. */
    fun prune(stats: ServerLatencyStats, nowMs: Long): ServerLatencyStats? = with(stats) {
        val keepSample = ewmaMs != null && samples > 0 && !isStale(this, nowMs)
        val keepFailure = lastFailAtMs != null && nowMs - lastFailAtMs < FailoverStagger.RECENT_FAILURE_WINDOW_MS
        if (!keepSample && !keepFailure) return@with null
        ServerLatencyStats(
            ewmaMs = ewmaMs.takeIf { keepSample },
            samples = if (keepSample) samples else 0,
            lastSampleAtMs = if (keepSample) lastSampleAtMs else 0L,
            lastFailAtMs = lastFailAtMs.takeIf { keepFailure },
        )
    }
}
