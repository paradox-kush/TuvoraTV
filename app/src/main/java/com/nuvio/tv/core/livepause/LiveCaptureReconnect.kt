package com.nuvio.tv.core.livepause

/**
 * THE single capped reconnect rule for one live capture. It replaces homelessbrian/NuvioPlusIPTV's two
 * divergent loops (an uncapped HLS refetch and a TS loop that retried every 2-8 s forever): a drop is
 * retried with a 2/4/8 s backoff, and [maxAttemptsWithoutProgress] drops in a row with no piece landing
 * in between is a [Decision.GiveUp] (the owner shows Failed + Retry).
 *
 * A single-use link is NEVER re-opened: every Retry for it sets `mintFreshLink`, and the first capture
 * open mints too ([mintOnFirstOpen]) because the player just spent its URL.
 */
class LiveCaptureReconnect(val singleUseLink: Boolean, val maxAttemptsWithoutProgress: Int = 3) {
    sealed interface Decision {
        data class Retry(val delayMs: Long, val mintFreshLink: Boolean) : Decision
        data object GiveUp : Decision
    }

    var attempts: Int = 0
        private set

    /** The first capture open of a single-use link must mint a fresh link. */
    val mintOnFirstOpen: Boolean get() = singleUseLink

    /** The connection dropped (or failed to open). Attempt n (1-based) waits 2 s * 2^(n-1), capped at 8 s. */
    fun onDrop(): Decision {
        if (attempts >= maxAttemptsWithoutProgress) return Decision.GiveUp
        attempts++
        val delayMs = minOf(BASE_DELAY_MS shl (attempts - 1).coerceAtMost(10), MAX_DELAY_MS)
        return Decision.Retry(delayMs = delayMs, mintFreshLink = singleUseLink)
    }

    /** A piece / segment landed: the next drop starts the backoff over. */
    fun onProgress() {
        attempts = 0
    }

    private companion object {
        const val BASE_DELAY_MS = 2_000L
        const val MAX_DELAY_MS = 8_000L
    }
}
