package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.policy.CertTrustPolicy.FailureKind
import com.nuvio.tv.core.mediaserver.policy.CertTrustPolicy.Verdict
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class CertTrustPolicyTest {
    private fun decide(
        trusted: Boolean = false, failure: FailureKind? = FailureKind.SELF_SIGNED, presented: String = "AA",
        pinned: String? = null, host: String = "192.168.1.5",
    ) = CertTrustPolicy.decide(trusted, failure, presented, pinned, host)

    @Test
    fun aSystemTrustedChainIsAcceptedAndNeverAsksOrPins() {
        assertEquals(Verdict.Accept, decide(trusted = true, failure = null, pinned = null))
        // even with a stale pin: a publicly trusted chain wins (Let's Encrypt rotates; a pin would break every renewal)
        assertEquals(Verdict.Accept, decide(trusted = true, failure = null, pinned = "OLD", presented = "NEW"))
    }

    @Test
    fun aFailedValidationWithNothingPinnedOffersTrustOnFirstUse() {
        assertEquals(Verdict.AskTrustNew("AA"), decide())
        assertEquals(Verdict.AskTrustNew("AA"), decide(failure = FailureKind.UNTRUSTED_CHAIN, host = "jf.example.com"))
    }

    @Test
    fun aMatchingPinIsAcceptedSilently() {
        assertEquals(Verdict.Accept, decide(pinned = "AA", presented = "AA"))
    }

    @Test
    fun aDifferentCertificateThanThePinnedOneAsksAgain() {
        assertEquals(Verdict.AskCertificateChanged("AA", "BB"), decide(pinned = "AA", presented = "BB"))
    }

    @Test
    fun anExpiredOrUnrelatedTlsFailureIsNeverPapered() {
        assertEquals(Verdict.Reject(FailureKind.EXPIRED), decide(failure = FailureKind.EXPIRED, pinned = "AA", presented = "AA"))
        assertEquals(Verdict.Reject(FailureKind.OTHER), decide(failure = FailureKind.OTHER))
        assertEquals("an unknown failure is OTHER, not trust-on-first-use", Verdict.Reject(FailureKind.OTHER), decide(failure = null))
    }

    @Test
    fun aHostnameMismatchIsOnlyForgivenForLocalOrIpHosts() {
        assertTrue(CertTrustPolicy.canOfferTrust(FailureKind.HOSTNAME_MISMATCH, "192.168.1.5"))
        assertTrue(CertTrustPolicy.canOfferTrust(FailureKind.HOSTNAME_MISMATCH, "nas.local"))
        assertTrue(CertTrustPolicy.canOfferTrust(FailureKind.HOSTNAME_MISMATCH, "8.8.8.8"))
        assertFalse("a public host presenting the wrong name is an attack until proven otherwise", CertTrustPolicy.canOfferTrust(FailureKind.HOSTNAME_MISMATCH, "jf.example.com"))
        assertEquals(Verdict.Reject(FailureKind.HOSTNAME_MISMATCH), decide(failure = FailureKind.HOSTNAME_MISMATCH, host = "jf.example.com"))
    }
}
