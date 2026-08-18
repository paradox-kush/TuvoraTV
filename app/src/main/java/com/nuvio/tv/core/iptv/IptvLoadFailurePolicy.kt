package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.stalker.StalkerAuthException
import com.nuvio.tv.core.iptv.stalker.StalkerPortalRefusedException
import com.nuvio.tv.core.iptv.stalker.StalkerSessionUnavailableException

/**
 * The server answered with a non-2xx status.
 *
 * Carries the [status] so callers can tell the failure classes apart WITHOUT parsing a message.
 * That distinction matters most for panels behind a WAF: a 403/429 means the provider's edge
 * refused us and the portal itself is healthy, which is the opposite of the "portal is down" story
 * a bare failure tells.
 */
class HttpStatusException(val status: Int, message: String) : IllegalStateException(message)

/**
 * What to tell the viewer when a playlist's category list fails to load.
 *
 * TV already surfaced `e.message` rather than swallowing it (which is why the mobile/desktop hub
 * needed the bigger repair), but a raw `HTTP 403` on screen is not an explanation: it was a live
 * provider's Cloudflare blocking the device while the portal itself served fine, and nothing on
 * screen said so. This turns the throwable into the same three outcomes the other platforms show.
 *
 * Pure and type-driven: classifies by exception TYPE, never by parsing a message, and holds no
 * Context — so it tests without Android or a portal. The ViewModel owns the string resources,
 * because TV's [com.nuvio.tv.ui.components.ErrorState] takes one message and no title.
 */
object IptvLoadFailurePolicy {

    enum class Kind {
        /** Nothing specific known: DNS, timeout, connection refused, an open breaker. */
        UNREACHABLE,

        /**
         * The provider's edge refused us outright. The portal itself is healthy, so "check the
         * portal is up" is the one thing the viewer should NOT go do.
         */
        BLOCKED_BY_PROVIDER,

        /** The portal answered and said no. [Failure.portalText] carries the reason and remedy. */
        REFUSED,
    }

    /**
     * [status] is set only for [Kind.BLOCKED_BY_PROVIDER]; [portalText] only for [Kind.REFUSED]
     * (our own already-worded explanation, safe to render verbatim). [detail] is the always-present
     * support breadcrumb — terse, technical, meant to survive being photographed off a TV screen.
     */
    data class Failure(
        val kind: Kind,
        val status: Int? = null,
        val portalText: String? = null,
        val detail: String = UNKNOWN_REASON,
    )

    /** [host] is the playlist's panel origin, appended to [Failure.detail] so support knows which. */
    fun classify(error: Throwable?, host: String? = null): Failure {
        val detail = detailOf(error, host)
        return when {
            error is HttpStatusException && error.status in BLOCKING_STATUSES ->
                Failure(Kind.BLOCKED_BY_PROVIDER, status = error.status, detail = detail)

            // StalkerDeviceConflictException is a subclass — its remedy ("ask the provider to reset
            // the MAC") is the single most useful sentence this whole path can produce.
            error is StalkerPortalRefusedException -> refusal(error, detail)
            error is StalkerAuthException -> refusal(error, detail)
            error is StalkerSessionUnavailableException -> refusal(error, detail)

            else -> Failure(Kind.UNREACHABLE, detail = detail)
        }
    }

    /** A refusal with no message is still a refusal — the card falls back to its own copy. */
    private fun refusal(error: Throwable, detail: String): Failure =
        Failure(Kind.REFUSED, portalText = error.message?.takeIf { it.isNotBlank() }, detail = detail)

    /**
     * The breadcrumb: what went wrong, then who it went wrong with. A status beats a class name
     * (`HTTP 403` tells us more than `HttpStatusException`); otherwise the exception's own type is
     * the most specific honest thing we have. Never the message — those carry the account name and
     * would put a viewer's playlist label into a screenshot they post publicly.
     */
    private fun detailOf(error: Throwable?, host: String?): String {
        val reason = when {
            error is HttpStatusException -> "HTTP ${error.status}"
            error != null -> error::class.simpleName ?: UNKNOWN_REASON
            else -> UNKNOWN_REASON
        }
        return listOfNotNull(reason, host?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    /**
     * Statuses that mean "the edge turned us away", not "the portal is broken".
     *
     * 403 is Cloudflare's block page (measured live against a real provider: a couple of dozen
     * ordinary MAG requests earned one), 429 is a plain rate limit, 419 is the non-standard code
     * some panels return for the same thing, and 451 is a filtering block. Deliberately narrow —
     * a 404 means the portal URL is wrong and a 5xx means the origin really is unwell, and
     * dressing either of those up as "you are blocked" would send the viewer somewhere useless.
     */
    private val BLOCKING_STATUSES = setOf(403, 419, 429, 451)

    private const val UNKNOWN_REASON = "unknown error"
}
