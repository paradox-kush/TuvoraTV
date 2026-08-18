package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.stalker.StalkerAuthException
import com.nuvio.tv.core.iptv.stalker.StalkerDeviceConflictException
import com.nuvio.tv.core.iptv.stalker.StalkerPortalRefusedException
import com.nuvio.tv.core.iptv.stalker.StalkerSessionUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV twin of the mobile/desktop policy test. NOTE the assertion order: JUnit is
 * `assertEquals(message, expected, actual)` — the reverse of commonTest's kotlin.test.
 *
 * Regression cover for a real support loop: a provider's Cloudflare answered 403 to a live Stalker
 * portal, and every surface reported it as though the portal were down.
 */
class IptvLoadFailurePolicyTest {

    private fun classify(t: Throwable?, host: String? = null) = IptvLoadFailurePolicy.classify(t, host)

    // --- the edge turned us away (portal is healthy) --------------------------

    @Test
    fun `a 403 reads as a provider block and not as a dead portal`() {
        val failure = classify(HttpStatusException(403, "HTTP 403"))
        assertEquals("403 is a WAF block", IptvLoadFailurePolicy.Kind.BLOCKED_BY_PROVIDER, failure.kind)
        assertEquals("the card shows the code it was given", 403, failure.status)
    }

    @Test
    fun `every blocking status classifies the same way`() {
        for (status in listOf(403, 419, 429, 451)) {
            assertEquals(
                "HTTP $status means the edge refused us",
                IptvLoadFailurePolicy.Kind.BLOCKED_BY_PROVIDER,
                classify(HttpStatusException(status, "HTTP $status")).kind,
            )
        }
    }

    @Test
    fun `a wrong portal path stays unreachable rather than claiming a block`() {
        assertEquals(
            "404 means the URL is wrong",
            IptvLoadFailurePolicy.Kind.UNREACHABLE,
            classify(HttpStatusException(404, "HTTP 404")).kind,
        )
    }

    @Test
    fun `a failing origin stays unreachable`() {
        for (status in listOf(500, 502, 503)) {
            assertEquals(
                "HTTP $status really is the portal being unwell",
                IptvLoadFailurePolicy.Kind.UNREACHABLE,
                classify(HttpStatusException(status, "HTTP $status")).kind,
            )
        }
    }

    // --- the portal answered and said no --------------------------------------

    @Test
    fun `a device conflict surfaces the portal's own remedy`() {
        val message = "Another device is using this MAC on portal — ask the provider to reset the MAC."
        val failure = classify(StalkerDeviceConflictException(message))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertEquals("the remedy is the whole point of surfacing it", message, failure.portalText)
    }

    @Test
    fun `a plain portal refusal is a refusal too`() {
        val failure = classify(StalkerPortalRefusedException("Stalker portal refused portal."))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertEquals("Stalker portal refused portal.", failure.portalText)
    }

    @Test
    fun `a rejected device identity is a refusal`() {
        val failure = classify(StalkerAuthException("check the MAC address"))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertEquals("check the MAC address", failure.portalText)
    }

    @Test
    fun `a line held by another device is a refusal`() {
        val failure = classify(StalkerSessionUnavailableException("held by another device"))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertEquals("held by another device", failure.portalText)
    }

    @Test
    fun `a refusal with no text still classifies and lets the card fall back`() {
        val failure = classify(StalkerPortalRefusedException("   "))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertNull("blank text must not render as an empty message", failure.portalText)
    }

    // --- everything else -------------------------------------------------------

    @Test
    fun `an unknown failure never leaks its message to the viewer`() {
        val failure = classify(IllegalStateException("java.net.UnknownHostException: tv.example.biz"))
        assertEquals(IptvLoadFailurePolicy.Kind.UNREACHABLE, failure.kind)
        assertNull("raw transport text is noise", failure.portalText)
        assertNull(failure.status)
    }

    // --- the support breadcrumb ------------------------------------------------

    @Test
    fun `a block names the status and the provider for a screenshot`() {
        val failure = classify(HttpStatusException(403, "HTTP 403"), host = "http://tv.example.biz")
        assertEquals(
            "one line support can read off a photo of the TV",
            "HTTP 403 · http://tv.example.biz",
            failure.detail,
        )
    }

    @Test
    fun `an unknown failure still names its type`() {
        assertEquals(
            "IllegalStateException · http://p.example",
            classify(IllegalStateException("boom"), host = "http://p.example").detail,
        )
    }

    @Test
    fun `the breadcrumb never carries the exception message`() {
        val failure = classify(
            StalkerDeviceConflictException("Another device is using this MAC on Kev's Portal"),
            host = "http://p.example",
        )
        assertTrue("the account name must not ride the breadcrumb", "Kev" !in failure.detail)
        assertEquals("StalkerDeviceConflictException · http://p.example", failure.detail)
    }

    @Test
    fun `a playlist with no panel origin still gets a reason`() {
        assertEquals("HTTP 429", classify(HttpStatusException(429, "HTTP 429"), host = null).detail)
    }

    @Test
    fun `a missing throwable degrades to the generic copy`() {
        val failure = classify(null)
        assertEquals(IptvLoadFailurePolicy.Kind.UNREACHABLE, failure.kind)
        assertNull(failure.status)
        assertNull(failure.portalText)
        assertEquals("even a missing cause leaves something to report", "unknown error", failure.detail)
    }
}
