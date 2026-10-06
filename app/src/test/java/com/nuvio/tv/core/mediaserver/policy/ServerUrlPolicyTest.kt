package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.policy.ServerUrlPolicy.Outcome
import com.nuvio.tv.core.mediaserver.policy.ServerUrlPolicy.Reason
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class ServerUrlPolicyTest {
    private fun candidates(input: String): List<String> = (ServerUrlPolicy.normalize(input) as Outcome.Ok).candidates
    private fun rejected(input: String): Reason = (ServerUrlPolicy.normalize(input) as Outcome.Rejected).reason

    @Test
    fun aBareHostTriesHttpsThenHttpThenTheProductPorts() {
        assertEquals(
            listOf("https://jf.example.com", "http://jf.example.com", "http://jf.example.com:8096", "https://jf.example.com:8920"),
            candidates("jf.example.com"),
        )
    }

    @Test
    fun anExplicitSchemeAndPortIsTheOnlyCandidate() {
        assertEquals(listOf("http://192.168.1.5:8096"), candidates("http://192.168.1.5:8096"))
        assertEquals(listOf("https://nas.local:8920"), candidates("https://nas.local:8920/"))
    }

    @Test
    fun aSchemeWithoutAPortAddsTheProductPort() {
        assertEquals(listOf("http://192.168.1.5", "http://192.168.1.5:8096"), candidates("http://192.168.1.5"))
        assertEquals("a public https host is a reverse proxy: just that", listOf("https://jf.example.com"), candidates("https://jf.example.com"))
        assertEquals(listOf("https://nas.local", "https://nas.local:8920"), candidates("https://nas.local"))
    }

    @Test
    fun aBarePortGuessesTheProtocolFromThePort() {
        assertEquals(listOf("http://192.168.1.5:8096", "https://192.168.1.5:8096"), candidates("192.168.1.5:8096"))
        assertEquals(listOf("https://192.168.1.5:8920", "http://192.168.1.5:8920"), candidates("192.168.1.5:8920"))
    }

    @Test
    fun aReverseProxyBasePathIsKeptAndTheBrowserPathIsStripped() {
        assertEquals(listOf("https://example.com/jellyfin"), candidates("https://example.com/jellyfin/"))
        assertEquals(listOf("https://example.com/jellyfin"), candidates("https://example.com/jellyfin/web/index.html#!/home"))
        assertEquals(listOf("http://nas:8096"), candidates("http://nas:8096/web/"))
        assertEquals(listOf("http://nas:8096"), candidates("http://nas:8096/web/index.html"))
        assertEquals(listOf("http://nas:8096"), candidates("http://nas:8096?x=1#y"))
    }

    @Test
    fun hostAndSchemeAreLowercasedAndTheDefaultPortDropped() {
        assertEquals(listOf("https://jf.example.com"), candidates("HTTPS://JF.Example.COM:443"))
        assertEquals(listOf("http://jf.example.com"), candidates("http://jf.example.com:80"))
    }

    @Test
    fun onlyHttpAndHttpsAreAccepted() {
        assertEquals(Reason.UNSUPPORTED_SCHEME, rejected("ftp://nas"))
        assertEquals(Reason.UNSUPPORTED_SCHEME, rejected("jellyfin://nas"))
        assertEquals(Reason.UNSUPPORTED_SCHEME, rejected("file:///etc/passwd"))
        assertEquals(Reason.UNSUPPORTED_SCHEME, rejected("javascript://alert(1)"))
    }

    @Test
    fun credentialsInAnAddressAreRefused() {
        assertEquals(Reason.CREDENTIALS_IN_ADDRESS, rejected("http://kid:pw@nas:8096"))
        assertEquals(Reason.CREDENTIALS_IN_ADDRESS, rejected("kid@nas"))
    }

    @Test
    fun malformedAddressesAreRefused() {
        assertEquals(Reason.EMPTY, rejected("   "))
        assertEquals(Reason.NO_HOST, rejected("http://"))
        assertEquals(Reason.INVALID_PORT, rejected("http://nas:99999"))
        assertEquals(Reason.INVALID_PORT, rejected("http://nas:abc"))
        assertEquals(Reason.INVALID_HOST, rejected("http://na s"))
        assertEquals(Reason.INVALID_HOST, rejected("http://[::1"))
    }

    @Test
    fun ipv6LinkLocalIsDroppedButOtherIpv6IsKept() {
        assertEquals(Reason.IPV6_LINK_LOCAL, rejected("http://[fe80::1%en0]:8096"))
        assertEquals(Reason.IPV6_LINK_LOCAL, rejected("[febf::1]"))
        assertEquals(listOf("http://[fd00::5]:8096"), candidates("http://[fd00::5]:8096"))
        assertEquals(listOf("http://[2001:db8::1]:8096"), candidates("http://[2001:DB8::1]:8096"))
    }

    @Test
    fun normalisingIsIdempotent() {
        for (input in listOf("jf.example.com", "http://192.168.1.5", "https://example.com/jellyfin/", "192.168.1.5:8096")) {
            candidates(input).forEach { c ->
                assertEquals("$c survives a second pass", listOf(c), candidates(c).filter { it == c }.take(1))
                assertEquals(c, ServerUrlPolicy.canonical(c))
            }
        }
    }

    @Test
    fun localHostDetection() {
        listOf("192.168.1.5", "10.0.0.2", "172.16.4.4", "172.31.255.1", "169.254.1.1", "127.0.0.1", "localhost", "nas", "nas.local", "media.lan", "[fd00::1]", "::1")
            .forEach { assertTrue("$it is local", ServerUrlPolicy.isLocalHost(it)) }
        listOf("jf.example.com", "8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1", "[2001:db8::1]")
            .forEach { assertFalse("$it is not local", ServerUrlPolicy.isLocalHost(it)) }
    }

    @Test
    fun insecureAndAuthorityHelpers() {
        assertTrue(ServerUrlPolicy.isInsecure("http://nas:8096"))
        assertFalse(ServerUrlPolicy.isInsecure("https://nas"))
        assertEquals("nas:8096", ServerUrlPolicy.hostAuthority("http://NAS:8096/x"))
        assertEquals("example.com:443", ServerUrlPolicy.hostAuthority("https://example.com/jf"))
        assertEquals("example.com:80", ServerUrlPolicy.hostAuthority("http://example.com"))
        assertNull(ServerUrlPolicy.hostAuthority("nas"))
        assertTrue(ServerUrlPolicy.isIpLiteral("192.168.1.5"))
        assertFalse(ServerUrlPolicy.isIpLiteral("nas.local"))
    }
}
