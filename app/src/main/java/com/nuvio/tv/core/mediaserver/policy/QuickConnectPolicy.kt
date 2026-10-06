package com.nuvio.tv.core.mediaserver.policy

/**
 * The cadence of one Quick Connect sign-in (design 5.3): a code shown with a countdown, polled gently until
 * someone approves it, regenerated when the server's request expires. Pure - the flow supplies the clock.
 * Jellyfin expires a request after 10 minutes; the poll is lifecycle-bound to the sign-in screen (the flow
 * is cancelled with it) and never overlaps itself.
 */
internal object QuickConnectPolicy {
    /** A Quick Connect request lives 10 minutes on the server. */
    const val REQUEST_LIFETIME_MS = 10 * 60_000L
    const val FIRST_POLL_MS = 2_000L
    const val STEADY_POLL_MS = 4_000L
    /** Consecutive transport failures tolerated before the sign-in gives up (a flaky LAN is not a failed sign-in). */
    const val MAX_CONSECUTIVE_FAILURES = 5

    /** Delay before poll number [attempt] (0 = the first). */
    fun pollDelayMs(attempt: Int): Long = if (attempt < 3) FIRST_POLL_MS else STEADY_POLL_MS

    fun remainingMs(startedAtMs: Long, nowMs: Long): Long = (REQUEST_LIFETIME_MS - (nowMs - startedAtMs)).coerceAtLeast(0L)

    fun isExpired(startedAtMs: Long, nowMs: Long): Boolean = remainingMs(startedAtMs, nowMs) == 0L

    /** `m:ss` for the countdown ("9:41"). */
    fun countdownLabel(remainingMs: Long): String {
        val totalSeconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

    /** The code the user reads out / types: digits grouped for legibility ("123 456"). The wire form stays ungrouped. */
    fun displayCode(code: String): String {
        val digits = code.filter { !it.isWhitespace() }
        return if (digits.length == 6) "${digits.substring(0, 3)} ${digits.substring(3)}" else digits
    }

    /** A code typed on the approving device: whitespace and dashes removed; null unless it is 6 digits. */
    fun normalizeTypedCode(input: String): String? =
        input.filter { it.isDigit() }.takeIf { it.length == 6 && input.all { c -> c.isDigit() || c == ' ' || c == '-' } }
}
