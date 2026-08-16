package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.stalker.StalkerProtocol
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * The one process-wide [PanelHostGuard] (WP6) plus the reset helper every user-driven Retry
 * affordance calls. Shared by the Xtream request path (the player_api.php OkHttp interceptor in
 * NetworkModule) and StalkerSession — the guard is origin-keyed, so one instance covers every
 * panel of every playlist without the records colliding.
 *
 * The clock is the monotonic elapsed-time source the policy asks for: wall-clock jumps (NTP sync,
 * timezone changes) can neither reopen nor hold a breaker.
 *
 * JVM twin of the NuvioMobile/NuvioDesktop `IptvPanelGuard` (com.nuvio.app.features.iptv).
 */
object IptvPanelGuard {

    private val start = TimeSource.Monotonic.markNow()

    /** THE guard. Request choke points admit/report against this instance. */
    val guard: PanelHostGuard = PanelHostGuard { start.elapsedNow().inWholeMilliseconds }

    /**
     * User-driven Retry/refresh for [acc]: clears the breaker for the account's panel origin so the
     * retry is never met with a fast-fail. Call BEFORE the affordance's first request. No-op for
     * M3U playlists (no panel API to guard). Automatic retries and first-loads must NOT call this.
     */
    fun resetForAccount(acc: XtreamAccount) {
        panelOriginUrlOf(acc)?.let { guard.reset(it) }
    }

    /**
     * The URL whose origin keys [acc]'s breaker record — the SAME base the transports admit with:
     * Xtream requests start with [XtreamAccount.baseUrl]; Stalker requests start with the
     * normalized portal base (the session normalizes before building URLs, so reset must too).
     */
    internal fun panelOriginUrlOf(acc: XtreamAccount): String? = when (acc.sourceType) {
        XtreamAccount.SOURCE_XTREAM -> acc.baseUrl
        XtreamAccount.SOURCE_STALKER -> StalkerProtocol.normalizePortalBase(acc.portalUrl)
        else -> null   // M3U url/file playlists have no panel API
    }
}

/**
 * Classifies one throwable from a panel transport attempt into the guard's outcome vocabulary
 * (OkHttp / java.net — mirrors [PanelRequestOutcome]'s own definitions).
 *
 * Only the failures that mean "the host never answered" count as
 * [PanelRequestOutcome.CONNECTION_FAILURE]: timeouts (SocketTimeoutException covers OkHttp's
 * connect AND read timeouts), DNS (UnknownHostException), refused/unreachable (ConnectException,
 * NoRouteToHostException, PortUnreachableException). Every other [IOException] — ECONNRESET
 * (SocketException), truncated bodies (EOFException), TLS trouble (SSLException), OkHttp
 * call-timeout/cancel (InterruptedIOException) — is inconclusive: being wrong here means refusing
 * to talk to a live panel, so the mapping errs toward contacting the host. A cancelled coroutine
 * proves nothing either. Anything else happened AFTER a response arrived (`error("HTTP …")`,
 * rejection sentinels, parse errors all need response bytes first) and clears the record.
 */
internal fun classifyPanelThrowable(t: Throwable): PanelRequestOutcome = when (t) {
    is CancellationException -> PanelRequestOutcome.CONNECTION_RESET
    // Our own refusal is never evidence about the wire (defensive: cannot happen with one wrap).
    is PanelHostFastFailException -> PanelRequestOutcome.CONNECTION_RESET
    is PanelHostFastFailIOException -> PanelRequestOutcome.CONNECTION_RESET
    // A browse call dropped after a provider switch never touched the wire either — dozens of
    // these in one switch must not open the breaker for a perfectly healthy portal.
    is com.nuvio.tv.core.iptv.stalker.StalkerBrowseAbandonedException -> PanelRequestOutcome.CONNECTION_RESET

    is UnknownHostException,
    is ConnectException,
    is NoRouteToHostException,
    is PortUnreachableException,
    is SocketTimeoutException -> PanelRequestOutcome.CONNECTION_FAILURE

    is IOException -> PanelRequestOutcome.CONNECTION_RESET

    else -> PanelRequestOutcome.HTTP_RESPONSE
}

/**
 * Admission-checks one panel transport attempt against this guard, then reports how it ended.
 *
 * - Refused admission throws the policy's own [PanelHostFastFailException] BEFORE any transport
 *   work. Its message wording is a contract: no `HTTP Error <code>`, no timeout words, no auth
 *   phrases — the Stalker error paths classify failures from text and must read this as a
 *   connection-level refusal (and it is not a [com.nuvio.tv.core.iptv.stalker.StalkerAuthException],
 *   so it can never trigger a re-handshake).
 * - [discovery] marks a Stalker endpoint-discovery probe: admitted even while the breaker is
 *   open and its failures never count — but a success still clears the record.
 * - Every outcome is reported, including cancellation, so an abandoned half-open trial cannot
 *   wedge the breaker.
 */
internal suspend fun <T> PanelHostGuard.guardedPanelRequest(
    url: String,
    discovery: Boolean = false,
    attempt: suspend () -> T,
): T {
    val admission = when (val decision = admit(url, discovery)) {
        is PanelAdmission.FastFail -> throw decision.toException()
        is PanelAdmission.Allowed -> decision
    }
    val result = try {
        attempt()
    } catch (t: Throwable) {
        report(admission, classifyPanelThrowable(t))
        throw t
    }
    report(admission, PanelRequestOutcome.SUCCESS)
    return result
}

/**
 * [PanelHostFastFailException] in [IOException] clothing, for the one refusal point that lives
 * inside an OkHttp interceptor chain: interceptors may only throw IOException (anything else is
 * mangled into a generic "canceled due to …" wrapper by the async call machinery, losing both the
 * type and the carefully-worded message). Same message, and the policy's own exception rides
 * along as the cause. Catching code can (and must) tell it apart from a real network error.
 */
class PanelHostFastFailIOException(
    val fastFail: PanelHostFastFailException,
) : IOException(fastFail.message, fastFail) {
    val origin: String get() = fastFail.origin
    val retryAtMillis: Long get() = fastFail.retryAtMillis
}
