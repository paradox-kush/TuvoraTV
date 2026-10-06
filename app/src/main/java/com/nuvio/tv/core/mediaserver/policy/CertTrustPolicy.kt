package com.nuvio.tv.core.mediaserver.policy

/**
 * Certificate trust for a media server (design 5.5): system validation FIRST; only when it FAILS may the
 * user be offered trust-on-first-use of the presented leaf certificate, pinned per `host:port`; a later
 * mismatch is "certificate changed - accept the new one?". A publicly trusted chain is never pinned
 * (Let's Encrypt moves to 64-day then 45-day certificates - a pin would break every renewal). Pure: the
 * platform glue (OkHttp trust manager, Darwin challenge handler, JVM trust manager) reports the facts and
 * enforces the verdict; this object is the decision.
 */
internal object CertTrustPolicy {
    /** Why system validation failed - the platform maps its own error into one of these. */
    enum class FailureKind { UNTRUSTED_CHAIN, SELF_SIGNED, HOSTNAME_MISMATCH, EXPIRED, OTHER }

    sealed interface Verdict {
        /** Proceed: the system trusts the chain, or the pinned fingerprint matches. */
        data object Accept : Verdict

        /** System validation failed and nothing is pinned yet: ask the user whether to trust this certificate. */
        data class AskTrustNew(val fingerprint: String) : Verdict

        /** A different certificate than the pinned one: ask the user whether to accept the new one. */
        data class AskCertificateChanged(val pinned: String, val presented: String) : Verdict

        /** A failure TOFU must not paper over (expired, or an unrelated TLS error): refuse. */
        data class Reject(val failure: FailureKind) : Verdict
    }

    /**
     * @param systemTrusted whether platform validation accepted the chain (then nothing else matters);
     * @param failure why it did not, when it did not;
     * @param presentedFingerprint the leaf's fingerprint as the platform computes it (an opaque string - it
     * is only ever compared with the same platform's earlier value, never across devices);
     * @param pinnedFingerprint what this device pinned for the host before, if anything;
     * @param host the address host, to judge whether a hostname mismatch is expected (a LAN IP).
     */
    fun decide(
        systemTrusted: Boolean,
        failure: FailureKind?,
        presentedFingerprint: String,
        pinnedFingerprint: String?,
        host: String,
    ): Verdict {
        if (systemTrusted) return Verdict.Accept
        val kind = failure ?: FailureKind.OTHER
        if (!canOfferTrust(kind, host)) return Verdict.Reject(kind)
        return when {
            pinnedFingerprint == null -> Verdict.AskTrustNew(presentedFingerprint)
            pinnedFingerprint == presentedFingerprint -> Verdict.Accept
            else -> Verdict.AskCertificateChanged(pinnedFingerprint, presentedFingerprint)
        }
    }

    /**
     * Trust-on-first-use is for the self-signed / private-CA certificates of a home server: an untrusted
     * chain or a self-signed leaf always qualifies; a hostname mismatch qualifies only for a local or IP-literal
     * host (a LAN address is rarely what the certificate was issued for); an expired certificate never does.
     */
    fun canOfferTrust(failure: FailureKind, host: String): Boolean = when (failure) {
        FailureKind.UNTRUSTED_CHAIN, FailureKind.SELF_SIGNED -> true
        FailureKind.HOSTNAME_MISMATCH -> ServerUrlPolicy.isLocalHost(host) || ServerUrlPolicy.isIpLiteral(host)
        FailureKind.EXPIRED, FailureKind.OTHER -> false
    }
}
