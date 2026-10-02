package com.nuvio.tv.core.iptv

/**
 * Step 2 — the one pure mapping from "what the preview route / redeem RPC said" to what the person
 * holding the code sees. Used by BOTH preview and redeem so the wording cannot drift.
 *
 * Decision 6.2 (neutral wording, app-side): the server keeps distinct `used` / `already_used` /
 * `revoked` / `suspended` codes so the web page can help, and a code is a ~59-bit secret so the
 * distinction leaks nothing to a stranger — but the apps do not show it. Only `expired` names its cause
 * (and offers the provider's contacts, when known).
 */
sealed interface SetupCodeOutcome {
    data class Ready(val preview: SetupPreview) : SetupCodeOutcome
    /** Signed out or an anonymous account: route to sign-in/up, keep the code in memory only. */
    data object NeedsSignIn : SetupCodeOutcome
    data class Expired(val support: ProviderSupport?) : SetupCodeOutcome
    data class RateLimited(val retryAfterSec: Int?) : SetupCodeOutcome
    data object Network : SetupCodeOutcome
    data object Unusable : SetupCodeOutcome
    /** Redeem only: the chosen profile is gone. The screen re-shows its profile choices. */
    data object ProfileNotFound : SetupCodeOutcome
    data class Problem(val problem: SetupCode.Problem) : SetupCodeOutcome

    /** The sentence to show (see [SetupMessage]); null for [Ready] and [NeedsSignIn]. */
    val message: SetupMessage?
        get() = when (this) {
            is Ready, NeedsSignIn -> null
            is Expired -> SetupMessage.EXPIRED
            is RateLimited -> SetupMessage.RATE_LIMITED
            Network -> SetupMessage.NETWORK
            Unusable -> SetupMessage.UNUSABLE
            ProfileNotFound -> SetupMessage.PROFILE_NOT_FOUND
            is Problem -> when (problem) {
                SetupCode.Problem.EMPTY -> SetupMessage.EMPTY
                SetupCode.Problem.BAD_CHARACTERS -> SetupMessage.BAD_CHARACTERS
                SetupCode.Problem.WRONG_LENGTH -> SetupMessage.WRONG_LENGTH
            }
        }

    companion object {
        fun forProblem(problem: SetupCode.Problem): SetupCodeOutcome = Problem(problem)

        /**
         * A server code, from a returned `{ok:false, error}` (redeem), a raised exception, or the preview
         * route's `{error, code}` body. [support] is the preview's contacts when already known.
         */
        fun forServerCode(code: String?, support: ProviderSupport? = null, retryAfterSec: Int? = null): SetupCodeOutcome =
            when (code?.trim()?.lowercase()) {
                "expired" -> Expired(support?.takeUnless { it.isEmpty })
                "rate_limited" -> RateLimited(retryAfterSec)
                "anonymous_not_allowed", "not_authenticated" -> NeedsSignIn
                "profile_not_found" -> ProfileNotFound
                // invalid_code, not_found, used, already_used, revoked, suspended, unavailable, and
                // anything we do not recognise: one neutral sentence.
                else -> Unusable
            }

        /**
         * The preview route's non-200 answers: 400 invalid_code | 404 not_found | 409 used |
         * 410 expired|revoked|suspended|unavailable | 429 rate_limited (+ Retry-After) | 503 not configured |
         * 502 other. A 404 `{error:"Not found"}` WITHOUT a `code` means the feature is off: neutral text. A 5xx
         * WITHOUT a code is a gateway failure, not a verdict on the code: [Network] (same as the Mobile reference).
         */
        fun forPreviewHttp(status: Int, code: String?, retryAfterSec: Int? = null): SetupCodeOutcome = when {
            status == 429 || code == "rate_limited" -> RateLimited(retryAfterSec)
            status in 200..299 -> Unusable // a 2xx that did not parse is no preview
            // The preview is fetched with redirects OFF; the route never redirects, so any 3xx is not an answer.
            status in 300..399 -> Unusable
            code != null -> forServerCode(code, retryAfterSec = retryAfterSec)
            // A 5xx with no code at all is a gateway/proxy failure, not an answer about the code: "try again".
            status in 500..599 -> Network
            else -> Unusable
        }
    }
}

/**
 * The sentences of [SetupCodeOutcome] (and the redeem flow's profile-gone line). [english] is the
 * contract's text; the shipped strings live in `strings.xml` and a unit test pins them to this.
 */
enum class SetupMessage(val english: String) {
    EMPTY("Enter the setup code your provider gave you."),
    BAD_CHARACTERS("That doesn't look like a setup code. Codes use letters and the numbers 2-9 only."),
    WRONG_LENGTH("A code has 12 characters. Check it against the one your provider sent."),
    EXPIRED("This code has expired. Ask your provider for a new one."),
    RATE_LIMITED("Too many tries. Wait a little while and try again."),
    NETWORK("We couldn't reach Tuvora. Check your connection and try again."),
    UNUSABLE(
        "This code can't be used. If you've already used it, the playlist is in your account. " +
            "Otherwise ask your provider for a new code."
    ),
    PROFILE_NOT_FOUND("That profile no longer exists. Pick another."),

    // Beyond the contract's table: what a redeem that SUCCEEDED but added nothing says (the wording follows
    // the web claim page, nuvio-web `summaryLines`).
    NOTHING_NO_LOGIN("Your provider hasn't filled in your login yet. Ask them to update it."),
    NOTHING_BAD_ADDRESS("Your provider's server address isn't valid. Ask your provider to check it."),
    ALREADY_SET_UP("This code was already used with this account, so there was nothing new to add."),
    NOTHING_ADDED("Nothing was added. Ask your provider to check your setup."),
}
