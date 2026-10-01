package com.nuvio.tv.core.iptv

/**
 * Step 0.3 — a playlist's device-local server choice (never synced).
 *
 * [activeIndex] 0 = the main server; i > 0 = `backupUrls[i - 1]`. [mainRetryAfterMs] is the epoch ms
 * after which the main server is tried first again (null = no window running).
 */
data class ServerFailoverState(
    val activeIndex: Int = 0,
    val mainRetryAfterMs: Long? = null,
)

/**
 * Step 0.3 — the pure "which server, in which order" decision behind backup servers.
 *
 * Deterministic and I/O-free so it is pinned by one golden table that is identical in NuvioMobile /
 * NuvioDesktop commonTest and NuvioTV JUnit (`ServerFailoverGolden`). Twin of NuvioMobile/NuvioDesktop
 * `features/iptv/ServerFailoverPolicy.kt` — keep the two byte-for-byte in behaviour. The seam that executes it is
 * [PlaylistServerFailover]; the seam never makes a request the caller was not already making — the
 * main server is retried only when the app naturally makes a fail-over-able request after
 * [ServerFailoverState.mainRetryAfterMs] (no timers, no background probing).
 */
object ServerFailoverPolicy {

    /** How long a playlist stays on a backup before the main server is tried first again. */
    const val MAIN_RETRY_WINDOW_MS: Long = 30L * 60 * 1000

    /** The walk budget multiplies the single-request timeout by at most this many servers. */
    const val MAX_BUDGETED_SERVERS: Int = 3

    /** [state] with an index that no longer exists (the list shrank) reset to the main server. */
    fun clamp(state: ServerFailoverState, serverCount: Int): ServerFailoverState =
        if (state.activeIndex in 0 until serverCount) state else ServerFailoverState()

    /**
     * The try-order for one request at [nowMs], each server at most once.
     *  - On a backup inside its retry window: the active backup, then the other backups in list
     *    order, the main server last.
     *  - Otherwise: main, then the backups in list order.
     */
    fun order(state: ServerFailoverState, serverCount: Int, nowMs: Long): List<Int> {
        if (serverCount <= 0) return emptyList()
        val s = clamp(state, serverCount)
        val retryAfter = s.mainRetryAfterMs
        val onBackup = s.activeIndex > 0 && retryAfter != null && nowMs < retryAfter
        if (!onBackup) return (0 until serverCount).toList()
        return listOf(s.activeIndex) + (1 until serverCount).filter { it != s.activeIndex } + 0
    }

    /** The state after server [index] served a fail-over-able request at [nowMs]. */
    fun onSuccess(state: ServerFailoverState, index: Int, nowMs: Long, serverCount: Int): ServerFailoverState {
        if (index <= 0) return ServerFailoverState()
        val s = clamp(state, serverCount)
        val retryAfter = s.mainRetryAfterMs
        val windowRunning = s.activeIndex > 0 && retryAfter != null && nowMs < retryAfter
        return ServerFailoverState(
            activeIndex = index,
            // A fresh move off main (or a window that already ran out) starts a new window; moving
            // between backups inside a running window keeps it — main is not retried any later.
            mainRetryAfterMs = if (windowRunning) retryAfter else nowMs + MAIN_RETRY_WINDOW_MS,
        )
    }

    /** Every server failed: the state is kept (the caller surfaces the MAIN server's error). */
    fun onAllFailed(state: ServerFailoverState): ServerFailoverState = state

    /** The whole walk's time budget: the existing single-request timeout × min(servers, 3). */
    fun walkBudgetMs(singleRequestTimeoutMs: Long, serverCount: Int): Long =
        singleRequestTimeoutMs * serverCount.coerceIn(1, MAX_BUDGETED_SERVERS)

    /** Whether another server may still be tried [elapsedMs] into a walk with [budgetMs]. */
    fun mayStartNextAttempt(elapsedMs: Long, budgetMs: Long): Boolean = elapsedMs < budgetMs

    /** The settings-row note for [activeIndex] ("Using backup server N"), or null on the main server. */
    fun backupLabel(activeIndex: Int): String? =
        if (activeIndex > 0) "Using backup server $activeIndex" else null
}

/**
 * Why one fail-over-able request failed, in a platform-neutral vocabulary. Each platform maps its own
 * network exceptions onto it ([classifyFailoverThrowable] here: OkHttp / java.net); [FailoverFailureClassifier] decides.
 */
enum class FailoverFailureKind {
    DNS,
    CONNECT_REFUSED,
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    TLS_HANDSHAKE,
    /** The per-origin breaker ([PanelHostGuard]) refused the host: it already stopped answering. */
    HOST_UNAVAILABLE,
    /** A non-2xx status — the code decides. */
    HTTP_STATUS,
    /** 2xx with an auth=0 / expired body — the same answer on every server. */
    AUTH_REJECTED,
    CANCELLED,
    /** Anything else (reset mid-body, parse errors, …) — proves nothing about the host. */
    OTHER,
}

data class FailoverFailure(val kind: FailoverFailureKind, val httpStatus: Int? = null)

/**
 * The pure "does this failure move us to the next server" table (Step 0.3 contract). Fail over on a
 * host that is down or gone — DNS, refused/timed-out connects, read timeouts, TLS handshake failures,
 * HTTP 5xx (incl. Cloudflare's 521–530 origin-down codes), HTTP 404 on the panel endpoint itself
 * (domain parked/moved). Never on an answer the backup would repeat: 401/403 (bad credentials or a
 * banned account), 456 (provider WAF), any other 4xx, a 2xx auth=0 body, or a cancellation.
 */
object FailoverFailureClassifier {

    fun shouldFailOver(failure: FailoverFailure): Boolean = when (failure.kind) {
        FailoverFailureKind.DNS,
        FailoverFailureKind.CONNECT_REFUSED,
        FailoverFailureKind.CONNECT_TIMEOUT,
        FailoverFailureKind.READ_TIMEOUT,
        FailoverFailureKind.TLS_HANDSHAKE,
        FailoverFailureKind.HOST_UNAVAILABLE -> true
        FailoverFailureKind.HTTP_STATUS -> failure.httpStatus?.let(::statusFailsOver) ?: false
        FailoverFailureKind.AUTH_REJECTED,
        FailoverFailureKind.CANCELLED,
        FailoverFailureKind.OTHER -> false
    }

    /** 404 (endpoint gone) and every 5xx (521–530 included) fail over; every other status does not. */
    fun statusFailsOver(status: Int): Boolean = status == 404 || status in 500..599
}
