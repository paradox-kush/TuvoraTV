package com.nuvio.tv.updater

import java.util.Locale

/**
 * B175: the updater asks GitHub's unauthenticated REST API (60 requests/hour per IP, shared by every
 * app and tool behind the same NAT/CGNAT). A refusal used to surface as the bare "GitHub API error:
 * 403", throwing away the evidence that tells a rate limit (come back at <reset>) from a policy block
 * (switching network helps). KMP twin: NuvioMobile `UpdaterHttpErrorPolicy`.
 *
 * GitHub's documented signals: a primary-limit refusal is 403 or 429 with `x-ratelimit-remaining: 0`
 * and `x-ratelimit-reset` (epoch seconds); a secondary limit is 403 or 429 with `retry-after`
 * (seconds) and a "rate limit" message in the JSON body.
 */
object UpdaterHttpErrorPolicy {

    sealed interface Outcome {
        /** Do not ask again before [resetAtEpochSeconds]. */
        data class RateLimited(val resetAtEpochSeconds: Long) : Outcome

        /** 403 with no rate-limit evidence: a proxy / carrier / policy block, not a quota. */
        data object Forbidden : Outcome

        data class Other(val status: Int) : Outcome
    }

    /** Wait applied when GitHub says "limited" without saying until when. */
    const val UNKNOWN_RESET_BACKOFF_SECONDS = 15 * 60L

    /** Never plan a retry sooner than this, even if the reset header is already in the past (clock skew). */
    const val MIN_BACKOFF_SECONDS = 60L

    /**
     * Never block longer than GitHub's own hourly window, whatever a header (or a device clock that
     * is hours behind) claims: a garbage reset must not switch update checks off for days.
     */
    const val MAX_BACKOFF_SECONDS = 3600L

    fun classify(
        status: Int,
        headers: Map<String, String>,
        body: String,
        nowEpochSeconds: Long,
    ): Outcome {
        if (status != 403 && status != 429) return Outcome.Other(status)
        val remaining = headers.header("x-ratelimit-remaining")?.trim()?.toLongOrNull()
        val resetAt = headers.header("x-ratelimit-reset")?.trim()?.toLongOrNull()
        val retryAfter = headers.header("retry-after")?.trim()?.toLongOrNull()
        val limited = status == 429 ||
            remaining == 0L ||
            retryAfter != null ||
            body.lowercase(Locale.ROOT).contains("rate limit")
        if (!limited) return Outcome.Forbidden
        val resetAtEpoch = when {
            retryAfter != null -> nowEpochSeconds + retryAfter
            resetAt != null -> resetAt
            else -> nowEpochSeconds + UNKNOWN_RESET_BACKOFF_SECONDS
        }
        return Outcome.RateLimited(
            resetAtEpoch.coerceIn(nowEpochSeconds + MIN_BACKOFF_SECONDS, nowEpochSeconds + MAX_BACKOFF_SECONDS),
        )
    }

    /** Whole minutes (rounded up, at least 1) from [nowEpochSeconds] until [resetAtEpochSeconds]. */
    fun minutesUntil(resetAtEpochSeconds: Long, nowEpochSeconds: Long): Int {
        val seconds = (resetAtEpochSeconds - nowEpochSeconds).coerceAtLeast(0L)
        return ((seconds + 59L) / 60L).toInt().coerceAtLeast(1)
    }

    /**
     * Whether a check may hit the network: false while a known rate limit has not reset yet. The
     * in-app check, the periodic job and every re-check obey it, so a limited network is not asked
     * again and again.
     */
    fun mayCheck(blockedUntilEpochSeconds: Long?, nowEpochSeconds: Long): Boolean =
        blockedUntilEpochSeconds == null ||
            nowEpochSeconds >= blockedUntilEpochSeconds ||
            isStale(blockedUntilEpochSeconds, nowEpochSeconds)

    /**
     * A stored block further out than any block we would ever set was written under a wrong clock
     * (or is garbage): treat it as lifted so it cannot outlive a restart.
     */
    fun isStale(blockedUntilEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
        blockedUntilEpochSeconds > nowEpochSeconds + MAX_BACKOFF_SECONDS

    private fun Map<String, String>.header(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
