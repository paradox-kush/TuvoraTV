package com.nuvio.tv.core.iptv.dns

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-playlist DNS-over-HTTPS. Each playlist may pick a [XtreamAccount.dnsProvider]; when it isn't
 * [XtreamAccount.DNS_SYSTEM] the app resolves that playlist's hostnames through the chosen DoH
 * resolver instead of the OS resolver (useful when an ISP blocks the IPTV panel's DNS record).
 *
 * The resolver clients are derived from a shared bootstrap [OkHttpClient] (one connection pool for
 * every DoH request). Two of the validated endpoints are addressed by pure IP
 * ([DnsProviderEndpoint.bootstrapIps] empty) so the DoH request itself needs no prior name lookup;
 * the rest carry bootstrap IPs fed to [DnsOverHttps.Builder.bootstrapDnsHosts] so OkHttp can reach
 * the resolver host without ever calling the system resolver. **A DoH client never depends on the
 * system DNS to find its own resolver.**
 *
 * Everything here is defensive: a failure to build a resolver falls back to [Dns.SYSTEM] and a
 * failure to resolve at the call site falls back to the original URL + system DNS, so DNS selection
 * can never break API traffic or playback.
 */
@Singleton
class PlaylistDns @Inject constructor() {

    /** One shared bootstrap client for all DoH POSTs (single connection pool, small in-mem cache). */
    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .build()
    }

    private val dnsByProvider = ConcurrentHashMap<String, Dns>()
    private val clientCache = ConcurrentHashMap<ClientKey, OkHttpClient>()

    /**
     * The [Dns] for [provider]. [XtreamAccount.DNS_SYSTEM] (and any unknown provider id) → the
     * system resolver ([IPv4FirstDns] wrapping [Dns.SYSTEM]); otherwise a [DnsOverHttps] built from
     * the validated endpoint, wrapped in [IPv4FirstDns] so IPv4 stays ordered first (issue #651).
     */
    fun dnsFor(provider: String?): Dns {
        val key = provider ?: XtreamAccount.DNS_SYSTEM
        return dnsByProvider.getOrPut(key) { buildDns(key) }
    }

    private fun buildDns(provider: String): Dns {
        val endpoint = DnsProviderEndpoint.forProvider(provider) ?: return IPv4FirstDns()
        return try {
            val builder = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(endpoint.url.toHttpUrl())
                .includeIPv6(false) // IPTV hosts are reached over IPv4; skip AAAA lookups.
            if (endpoint.bootstrapIps.isNotEmpty()) {
                builder.bootstrapDnsHosts(endpoint.bootstrapIps.mapNotNull { it.toInetAddressOrNull() })
            }
            IPv4FirstDns(builder.build())
        } catch (t: Throwable) {
            // Any DoH build failure degrades to the system resolver — never break networking.
            IPv4FirstDns()
        }
    }

    /**
     * Derives a per-provider [OkHttpClient] from [baseClient] that resolves names through the
     * playlist's chosen resolver, sharing [baseClient]'s connection pool + dispatcher (only the
     * [Dns] differs). Returns [baseClient] unchanged for the system provider. Cached per
     * (base-client identity, provider) so repeat calls reuse one derived client.
     */
    fun clientFor(baseClient: OkHttpClient, provider: String?): OkHttpClient {
        val key = provider ?: XtreamAccount.DNS_SYSTEM
        if (!usesDoh(key)) return baseClient
        return clientCache.getOrPut(ClientKey(System.identityHashCode(baseClient), key)) {
            baseClient.newBuilder().dns(dnsFor(key)).build()
        }
    }

    /** True when [provider] selects a non-system DoH resolver we know how to build. */
    fun usesDoh(provider: String?): Boolean =
        provider != null &&
            provider != XtreamAccount.DNS_SYSTEM &&
            DnsProviderEndpoint.forProvider(provider) != null

    private data class ClientKey(val baseId: Int, val provider: String)
}

/**
 * A validated DoH endpoint. Endpoints are either pure-IP ([bootstrapIps] empty — the resolver host
 * IS an IP literal, so no bootstrap lookup is needed) or hostname-based with [bootstrapIps] the app
 * hands OkHttp so it can reach the resolver host without the system resolver. All URLs were verified
 * to return HTTP 200 for a DoH query.
 */
enum class DnsProviderEndpoint(
    val provider: String,
    val url: String,
    val bootstrapIps: List<String>
) {
    CLOUDFLARE(XtreamAccount.DNS_CLOUDFLARE, "https://1.1.1.1/dns-query", emptyList()),
    GOOGLE(XtreamAccount.DNS_GOOGLE, "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
    MULLVAD(XtreamAccount.DNS_MULLVAD, "https://dns.mullvad.net/dns-query", listOf("194.242.2.2")),
    QUAD9(XtreamAccount.DNS_QUAD9, "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
    DNSSB(XtreamAccount.DNS_DNSSB, "https://185.222.222.222/dns-query", emptyList());

    companion object {
        fun forProvider(provider: String?): DnsProviderEndpoint? =
            entries.firstOrNull { it.provider == provider }
    }
}

/** Parse an IPv4/IPv6 literal without a DNS lookup (InetAddress.getByName short-circuits literals). */
private fun String.toInetAddressOrNull(): InetAddress? =
    try { InetAddress.getByName(this) } catch (_: Throwable) { null }

/**
 * Rewrites [url]'s host to a resolved [ip] literal and returns the rewritten URL plus the `Host`
 * header the caller must send so the origin still routes/serves the intended virtual host. This is
 * the mpv live path's helper: mpv is handed the IP-literal URL (bypassing its own resolver) with the
 * original host echoed via `http-header-fields`.
 *
 * Pure + defensive: an unparseable URL, a blank host, or a URL whose host is already [ip] returns
 * `null` (the caller keeps the original URL untouched). IPv6 literals are bracketed in the URL host.
 */
fun rewriteUrlToResolvedIp(url: String, ip: String): UrlHostRewrite? {
    if (ip.isBlank()) return null
    val parsed = url.toHttpUrlOrNullSafe() ?: return null
    val originalHost = parsed.host
    if (originalHost.isBlank() || originalHost == ip) return null
    val hostForUrl = if (ip.contains(':')) "[$ip]" else ip
    val rewritten = parsed.newBuilder().host(hostForUrl).build().toString()
    // Preserve the original host:port in the Host header when the URL used a non-default port.
    val hostHeader = if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) {
        "$originalHost:${parsed.port}"
    } else {
        originalHost
    }
    return UrlHostRewrite(rewritten, hostHeader)
}

/** Result of [rewriteUrlToResolvedIp]: the IP-literal URL + the `Host` header to send with it. */
data class UrlHostRewrite(val url: String, val hostHeader: String)

private fun String.toHttpUrlOrNullSafe(): HttpUrl? =
    try { toHttpUrl() } catch (_: Throwable) { null }
