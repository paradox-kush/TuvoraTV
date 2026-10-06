package com.nuvio.tv.core.mediaserver.store

import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerTrust
import com.nuvio.tv.core.mediaserver.policy.CertTrustPolicy.FailureKind
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.nuvio.tv.core.mediaserver.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerTrustStoreTest {
    private class Disk { var json: String? = null }

    private fun trust(disk: Disk = Disk()) = MediaServerTrust(MediaServerTrustStore({ disk.json }, { disk.json = it }, { disk.json = null }))

    @Test
    fun aPinnedCertificateIsAcceptedOnlyWhenSystemValidationFailsAndTheFingerprintMatches() {
        val t = trust()
        assertFalse("nothing pinned yet: refused, the user is asked", t.check("nas:8920", false, FailureKind.SELF_SIGNED, "AA"))
        val failure = assertNotNull(t.asException("nas:8920"))
        assertEquals("AA", failure.fingerprint)
        t.pin("nas:8920", "AA")
        assertTrue(t.check("nas:8920", false, FailureKind.SELF_SIGNED, "AA"))
        assertFalse("a different certificate is not the pinned one", t.check("nas:8920", false, FailureKind.SELF_SIGNED, "BB"))
        assertNull(t.asException("other:8920"))
    }

    @Test
    fun aSystemTrustedChainIsAlwaysAcceptedAndNeverRecorded() {
        val t = trust()
        assertTrue(t.check("example.com:443", true, null, "ZZ"))
        assertNull(t.asException("example.com:443"))
    }

    @Test
    fun anExpiredCertificateIsRefusedWithoutOfferingTrust() {
        val t = trust()
        assertFalse(t.check("nas:8920", false, FailureKind.EXPIRED, "AA"))
        assertNull("TOFU is not offered for an expired certificate", t.asException("nas:8920"))
    }

    @Test
    fun aPublicHostPresentingTheWrongNameIsNotOfferedTrust() {
        val t = trust()
        assertFalse(t.check("jf.example.com:443", false, FailureKind.HOSTNAME_MISMATCH, "AA"))
        assertNull(t.asException("jf.example.com:443"))
        assertFalse(t.check("192.168.1.5:8920", false, FailureKind.HOSTNAME_MISMATCH, "AA"))
        assertTrue(t.asException("192.168.1.5:8920") is MediaServerException.CertificateUntrusted)
    }

    @Test
    fun pinsPersistAreCaseInsensitiveAndUnpinnable() {
        val disk = Disk()
        trust(disk).pin("NAS:8920", "AA")
        val again = trust(disk)
        assertEquals("AA", again.pinnedFingerprint("nas:8920"))
        again.unpin("nas:8920")
        assertNull(trust(disk).pinnedFingerprint("nas:8920"))
        assertNull("the last pin gone: nothing left on disk", disk.json)
    }

    @Test
    fun clearAllErasesEveryPin() {
        val disk = Disk()
        val t = trust(disk)
        t.pin("a:1", "x"); t.pin("b:2", "y")
        t.clearAll()
        assertNull(t.pinnedFingerprint("a:1"))
        assertNull(disk.json)
    }
}
