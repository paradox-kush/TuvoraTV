package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/**
 * UX11 / UX20 / UX21 (TV twin of the KMP lane): the playlist form used to show one sentence for
 * everything ("Enter a server URL, username and password") or the raw exception
 * ("Authentication failed", "Failed to connect to /10.0.2.2:8999"). Each failure class now gets
 * the approved plain sentence.
 */
class PlaylistSaveErrorPolicyTest {

    private fun msg(t: Throwable) = PlaylistSaveErrorPolicy.messageFor(t)

    @Test
    fun `an unreachable server says so, never the raw socket text`() {
        val raw = ConnectException("Failed to connect to /10.0.2.2:8999")
        assertEquals("connection refused", PlaylistSaveError.UNREACHABLE, PlaylistSaveErrorPolicy.classify(raw))
        assertEquals("approved wording", "Couldn't reach the server — check the address", msg(raw))
        assertEquals("dns", PlaylistSaveError.UNREACHABLE, PlaylistSaveErrorPolicy.classify(UnknownHostException("nope.invalid")))
        assertEquals("timeout", PlaylistSaveError.UNREACHABLE, PlaylistSaveErrorPolicy.classify(SocketTimeoutException("timeout")))
        assertEquals("generic io", PlaylistSaveError.UNREACHABLE, PlaylistSaveErrorPolicy.classify(IOException("unexpected end of stream")))
    }

    @Test
    fun `a wrapped network cause is still found`() {
        val wrapped = RuntimeException("boom", ConnectException("Failed to connect"))
        assertEquals("cause chain walked", PlaylistSaveError.UNREACHABLE, PlaylistSaveErrorPolicy.classify(wrapped))
    }

    @Test
    fun `a credential rejection is wrong username or password - not a connection failure`() {
        assertEquals("auth != 1", PlaylistSaveError.WRONG_CREDENTIALS, PlaylistSaveErrorPolicy.classify(XtreamAuthRejectedException()))
        assertEquals("http 401", PlaylistSaveError.WRONG_CREDENTIALS, PlaylistSaveErrorPolicy.classify(HttpStatusException(401, "HTTP 401: Unauthorized")))
        assertEquals("approved wording", "Wrong username or password", msg(XtreamAuthRejectedException()))
    }

    @Test
    fun `a provider firewall status is a block - not a username or password problem`() {
        // 403/419/429/451/456 are the provider's edge (WAF/Cloudflare) turning the device away while
        // the server is up — IptvLoadFailurePolicy's BLOCKED_BY_PROVIDER. Never "wrong password".
        for (status in listOf(403, 419, 429, 451, 456)) {
            assertEquals("http $status", PlaylistSaveError.PROVIDER_BLOCKED, PlaylistSaveErrorPolicy.classify(HttpStatusException(status, "HTTP $status")))
        }
        assertEquals("message", "This provider is blocking us", PlaylistSaveErrorPolicy.messageFor(HttpStatusException(403, "HTTP 403: Forbidden")))
    }

    @Test
    fun `a TLS failure says secure connection failed`() {
        val tls = SSLHandshakeException("Handshake failed")
        assertEquals("handshake", PlaylistSaveError.SECURE_CONNECTION_FAILED, PlaylistSaveErrorPolicy.classify(tls))
        assertEquals("certificate", PlaylistSaveError.SECURE_CONNECTION_FAILED, PlaylistSaveErrorPolicy.classify(IOException("x", CertificateException("bad cert"))))
        assertEquals("approved wording", "Secure connection failed — try http:// or check the certificate", msg(tls))
    }

    @Test
    fun `other server answers fall back to the unreachable sentence`() {
        assertEquals("http 500", PlaylistSaveError.UNREACHABLE, PlaylistSaveErrorPolicy.classify(HttpStatusException(500, "HTTP 500: Server Error")))
        assertEquals("junk body", PlaylistSaveError.UNREACHABLE, PlaylistSaveErrorPolicy.classify(IllegalStateException("Empty response")))
    }

    @Test
    fun `an inactive account names its status`() {
        val e = XtreamAccountInactiveException("Expired")
        assertEquals("kind", PlaylistSaveError.ACCOUNT_INACTIVE, PlaylistSaveErrorPolicy.classify(e))
        assertEquals("existing wording kept", "Account status: Expired", msg(e))
    }

    @Test
    fun `truly empty fields keep the original sentence`() {
        assertEquals("no server", PlaylistSaveError.MISSING_XTREAM_FIELDS, PlaylistSaveErrorPolicy.formError("", "u", "p"))
        assertEquals("no user", PlaylistSaveError.MISSING_XTREAM_FIELDS, PlaylistSaveErrorPolicy.formError("http://host:8080", " ", "p"))
        assertEquals("no pass", PlaylistSaveError.MISSING_XTREAM_FIELDS, PlaylistSaveErrorPolicy.formError("http://host:8080", "u", ""))
        assertEquals("wording", "Enter a server URL, username and password", PlaylistSaveErrorPolicy.message(PlaylistSaveError.MISSING_XTREAM_FIELDS))
    }

    @Test
    fun `an invalid port or malformed address says the address is not valid`() {
        assertEquals("port out of range", PlaylistSaveError.INVALID_ADDRESS, PlaylistSaveErrorPolicy.formError("http://host:99999", "u", "p"))
        assertEquals("non-numeric port", PlaylistSaveError.INVALID_ADDRESS, PlaylistSaveErrorPolicy.formError("host:80a", "u", "p"))
        assertEquals("spaces in host", PlaylistSaveError.INVALID_ADDRESS, PlaylistSaveErrorPolicy.formError("my host", "u", "p"))
        assertEquals("wording", "That server address isn't valid", PlaylistSaveErrorPolicy.message(PlaylistSaveError.INVALID_ADDRESS))
    }

    @Test
    fun `a well-formed form has no form error`() {
        assertNull("valid", PlaylistSaveErrorPolicy.formError("http://host:8080", "u", "p"))
        assertNull("scheme added", PlaylistSaveErrorPolicy.formError("host:8080", "u", "p"))
    }

    @Test
    fun `no mapped sentence ever leaks the raw exception text`() {
        val raw = ConnectException("Failed to connect to /10.0.2.2:8999")
        assertFalse("raw host must not leak", msg(raw).contains("10.0.2.2"))
    }
}
