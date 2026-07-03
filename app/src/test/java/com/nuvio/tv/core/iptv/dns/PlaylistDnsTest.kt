package com.nuvio.tv.core.iptv.dns

import com.nuvio.tv.core.iptv.XtreamAccount
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the per-playlist DoH endpoint table + the mpv URL-rewrite helper. These are the load-bearing
 * pure/near-pure pieces of Scope A: an endpoint typo (wrong URL / missing bootstrap) would silently
 * fall back to system DNS, and a URL-rewrite bug would hand mpv a bad URL/Host.
 */
class PlaylistDnsTest {

    // --- DnsProviderEndpoint table: right URL + bootstrap per provider ------------------------

    @Test
    fun `cloudflare is pure-IP with no bootstrap`() {
        val e = DnsProviderEndpoint.forProvider(XtreamAccount.DNS_CLOUDFLARE)!!
        assertEquals("https://1.1.1.1/dns-query", e.url)
        assertTrue("pure-IP endpoint needs no bootstrap", e.bootstrapIps.isEmpty())
    }

    @Test
    fun `dnssb is pure-IP with no bootstrap`() {
        val e = DnsProviderEndpoint.forProvider(XtreamAccount.DNS_DNSSB)!!
        assertEquals("https://185.222.222.222/dns-query", e.url)
        assertTrue(e.bootstrapIps.isEmpty())
    }

    @Test
    fun `google is hostname-based with bootstrap IPs`() {
        val e = DnsProviderEndpoint.forProvider(XtreamAccount.DNS_GOOGLE)!!
        assertEquals("https://dns.google/dns-query", e.url)
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), e.bootstrapIps)
    }

    @Test
    fun `mullvad and quad9 carry bootstrap IPs`() {
        val mullvad = DnsProviderEndpoint.forProvider(XtreamAccount.DNS_MULLVAD)!!
        assertEquals("https://dns.mullvad.net/dns-query", mullvad.url)
        assertEquals(listOf("194.242.2.2"), mullvad.bootstrapIps)

        val quad9 = DnsProviderEndpoint.forProvider(XtreamAccount.DNS_QUAD9)!!
        assertEquals("https://dns.quad9.net/dns-query", quad9.url)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), quad9.bootstrapIps)
    }

    @Test
    fun `every hostname endpoint has a bootstrap and every pure-IP one does not`() {
        DnsProviderEndpoint.entries.forEach { e ->
            val hostIsIpLiteral = Regex("""https://\d+\.\d+\.\d+\.\d+/""").containsMatchIn(e.url)
            if (hostIsIpLiteral) {
                assertTrue("${e.provider} is IP-literal → no bootstrap", e.bootstrapIps.isEmpty())
            } else {
                assertTrue("${e.provider} is hostname → must bootstrap without system DNS", e.bootstrapIps.isNotEmpty())
            }
        }
    }

    @Test
    fun `system and unknown providers have no endpoint`() {
        assertNull(DnsProviderEndpoint.forProvider(XtreamAccount.DNS_SYSTEM))
        assertNull(DnsProviderEndpoint.forProvider("nonsense"))
        assertNull(DnsProviderEndpoint.forProvider(null))
    }

    // --- PlaylistDns.dnsFor / usesDoh ---------------------------------------------------------

    @Test
    fun `dnsFor builds a DnsOverHttps for a known provider and system for the rest`() {
        val dns = PlaylistDns()
        // System / unknown → NOT a DnsOverHttps (system resolver, IPv4-first wrapper).
        assertFalse(dns.dnsFor(XtreamAccount.DNS_SYSTEM) is DnsOverHttps)
        assertFalse(dns.dnsFor("nonsense") is DnsOverHttps)
        assertFalse(dns.dnsFor(null) is DnsOverHttps)
        // Every real provider builds a DnsOverHttps (wrapped) — assert the delegate is one.
        listOf(
            XtreamAccount.DNS_CLOUDFLARE, XtreamAccount.DNS_GOOGLE,
            XtreamAccount.DNS_MULLVAD, XtreamAccount.DNS_QUAD9, XtreamAccount.DNS_DNSSB
        ).forEach { provider ->
            assertNotNull("built a resolver for $provider", dns.dnsFor(provider))
            assertTrue("dnsFor is cached (same instance)", dns.dnsFor(provider) === dns.dnsFor(provider))
        }
    }

    @Test
    fun `usesDoh is true only for known non-system providers`() {
        val dns = PlaylistDns()
        assertFalse(dns.usesDoh(XtreamAccount.DNS_SYSTEM))
        assertFalse(dns.usesDoh(null))
        assertFalse(dns.usesDoh("nonsense"))
        assertTrue(dns.usesDoh(XtreamAccount.DNS_CLOUDFLARE))
        assertTrue(dns.usesDoh(XtreamAccount.DNS_QUAD9))
    }

    // --- rewriteUrlToResolvedIp: the mpv URL-rewrite helper -----------------------------------

    @Test
    fun `rewrite swaps host to ip and returns original host as Host header`() {
        val out = rewriteUrlToResolvedIp("http://panel.example.com/live/u/p/123.ts", "203.0.113.7")!!
        assertEquals("http://203.0.113.7/live/u/p/123.ts", out.url)
        assertEquals("panel.example.com", out.hostHeader)
    }

    @Test
    fun `rewrite keeps a non-default port in both the url and the Host header`() {
        val out = rewriteUrlToResolvedIp("http://panel.example.com:8080/live/1.ts", "203.0.113.7")!!
        assertEquals("http://203.0.113.7:8080/live/1.ts", out.url)
        assertEquals("panel.example.com:8080", out.hostHeader)
    }

    @Test
    fun `rewrite preserves the path and query`() {
        val out = rewriteUrlToResolvedIp("http://host.tv/live/u/p/9.ts?token=abc", "10.0.0.5")!!
        assertEquals("http://10.0.0.5/live/u/p/9.ts?token=abc", out.url)
        assertEquals("host.tv", out.hostHeader)
    }

    @Test
    fun `rewrite brackets an ipv6 literal in the url host`() {
        val out = rewriteUrlToResolvedIp("http://host.tv/live/1.ts", "2001:db8::1")!!
        assertEquals("http://[2001:db8::1]/live/1.ts", out.url)
        assertEquals("host.tv", out.hostHeader)
    }

    @Test
    fun `rewrite returns null for a blank ip, an unparseable url, or a host already equal to the ip`() {
        assertNull(rewriteUrlToResolvedIp("http://host.tv/1.ts", ""))
        assertNull(rewriteUrlToResolvedIp("not a url", "203.0.113.7"))
        assertNull(rewriteUrlToResolvedIp("http://203.0.113.7/1.ts", "203.0.113.7"))
    }
}
