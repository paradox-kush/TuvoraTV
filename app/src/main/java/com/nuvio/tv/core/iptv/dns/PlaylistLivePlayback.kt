package com.nuvio.tv.core.iptv.dns

import android.util.Log
import com.nuvio.tv.core.iptv.XtreamAccount
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A live stream prepared for mpv: the URL to load + the headers mpv must send with it. */
data class PreparedLiveStream(val url: String, val headers: Map<String, String>)

/**
 * Prepares an IPTV live stream URL for the mpv player when the playlist opts into a non-system DNS
 * provider. mpv resolves hostnames with its own (system) resolver, so to make DoH apply to the
 * common **plain-http** live case we:
 *   1. resolve the URL's host through the playlist's DoH resolver,
 *   2. follow the panel's redirects over that DoH client (Xtream live URLs 302 to a CDN/load-balancer
 *      host — each hop's host is resolved via DoH),
 *   3. hand mpv the FINAL media URL rewritten to the resolved IP, with the original host echoed via
 *      the `Host` header (mpv `http-header-fields`).
 *
 * ### Documented caveat — https live falls back to system DNS
 * This rewrite is applied ONLY to `http` live URLs. For `https` live, rewriting the URL host to an IP
 * literal makes mpv open the TLS connection with SNI/cert-verification against the IP, which breaks
 * the handshake against a virtual-hosted origin (SNI-by-IP). So https live is handed to mpv unchanged
 * and resolves via mpv's system resolver. DoH still applies to https **API/ingest** traffic (OkHttp
 * there sets the SNI from the original host regardless of the resolved IP); it's only the mpv URL
 * rewrite that can't be done safely for https.
 *
 * Fully defensive: no DNS provider, a system provider, an https URL, a DoH/resolve/redirect failure,
 * or an unparseable URL all return the original URL with no added headers — DNS selection never breaks
 * playback.
 */
@Singleton
class PlaylistLivePlayback @Inject constructor(
    private val playlistDns: PlaylistDns
) {
    /** Convenience overload driven by a full [account] (uses its [XtreamAccount.dnsProvider]). */
    fun prepare(account: XtreamAccount, rawUrl: String): PreparedLiveStream =
        prepare(account.dnsProvider, rawUrl)

    /**
     * Returns the URL + headers to hand mpv for [rawUrl] under the playlist's [provider]. May perform
     * network I/O (DoH lookup + redirect GET) — call from a background dispatcher.
     */
    fun prepare(provider: String?, rawUrl: String): PreparedLiveStream {
        val untouched = PreparedLiveStream(rawUrl, emptyMap())
        if (!playlistDns.usesDoh(provider)) return untouched
        val doh = provider ?: return untouched

        val parsed = rawUrl.toHttpUrlOrNull() ?: return untouched
        // https live: SNI-by-IP breaks TLS — leave it to mpv's own resolver (see class caveat).
        if (!parsed.scheme.equals("http", ignoreCase = true)) return untouched

        return try {
            val client = redirectResolverClient(doh)
            // Follow redirects to the final media URL over the DoH client (each hop resolved via DoH).
            val finalUrl = resolveFinalUrl(client, rawUrl) ?: rawUrl
            val finalParsed = finalUrl.toHttpUrlOrNull() ?: return untouched
            // Only rewrite an http final hop (a redirect to https falls back to mpv's resolver).
            if (!finalParsed.scheme.equals("http", ignoreCase = true)) {
                return PreparedLiveStream(finalUrl, emptyMap())
            }
            val ip = resolveHostIp(doh, finalParsed.host) ?: return PreparedLiveStream(finalUrl, emptyMap())
            val rewrite = rewriteUrlToResolvedIp(finalUrl, ip) ?: return PreparedLiveStream(finalUrl, emptyMap())
            PreparedLiveStream(rewrite.url, mapOf("Host" to rewrite.hostHeader))
        } catch (t: Throwable) {
            Log.w(TAG, "DoH live prepare failed for provider=$provider; using original URL", t)
            untouched
        }
    }

    /** First resolved address for [host] via the playlist's DoH resolver, as a literal. Null on failure. */
    private fun resolveHostIp(provider: String, host: String): String? = try {
        playlistDns.dnsFor(provider).lookup(host).firstOrNull()?.hostAddress
    } catch (_: Throwable) {
        null
    }

    /**
     * Follows redirects for [url] over a DoH-backed client and returns the final URL. Uses GET (some
     * panels reject HEAD) but never reads the body. Returns null if the request never succeeds — the
     * caller then falls back to the original URL.
     */
    private fun resolveFinalUrl(client: OkHttpClient, url: String): String? {
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { resp -> resp.request.url.toString() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * A short-timeout, redirect-following client whose DNS is the playlist's resolver, derived from
     * [PlaylistLivePlaybackClient.base] so every provider shares one connection pool.
     */
    private fun redirectResolverClient(provider: String): OkHttpClient =
        playlistDns.clientFor(PlaylistLivePlaybackClient.base, provider)

    companion object {
        private const val TAG = "PlaylistLivePlayback"
    }
}

/** Lazily-built base client for the live redirect probe (trust-all, short timeouts, follows redirects). */
internal object PlaylistLivePlaybackClient {
    val base: OkHttpClient by lazy {
        val trustAll = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
