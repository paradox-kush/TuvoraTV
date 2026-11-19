package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.contracts.PlaybackTlsClass
import com.nuvio.tv.core.contracts.PlaybackTrustHosts
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.PlaybackSocketFactory
import com.nuvio.tv.core.player.Http2RetryPolicy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    private const val LOOPBACK_READ_TIMEOUT_SECONDS = 65L

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // Unverified TLS is only ever used for an IPTV panel host (panels commonly present self-signed or
    // mismatched certificates). The verifier runs on EVERY hop, redirects included, so a panel redirecting
    // to a media server, an add-on or any other host is refused.
    private fun isPermissivePanel(host: String?): Boolean =
        PlaybackTrustHosts.classify(host) == PlaybackTlsClass.PermissivePanel

    private val playbackHostnameVerifier = HostnameVerifier { host, _ -> isPermissivePanel(host) }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    /**
     * Fallback OkHttpClient with unverified certificates, for IPTV panel hosts only
     * ([PlaybackTrustHosts]); its hostname verifier refuses every other host.
     */
    internal val trustAllPlaybackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .socketFactory(PlaybackSocketFactory)
            .eventListenerFactory(PlaybackConnectionEvents)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Primary OkHttpClient using standard system SSL certificates and full SNI support.
     * Falls back to [trustAllPlaybackHttpClient] after an [SSLException] only when the host is an
     * IPTV panel; every other host (media servers, add-ons, CDNs, unknown) keeps its SSL failure.
     */
    internal val playbackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .socketFactory(PlaybackSocketFactory)
            .eventListenerFactory(PlaybackConnectionEvents)
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    chain.proceed(request)
                } catch (e: SSLException) {
                    if (!isPermissivePanel(request.url.host)) throw e
                    trustAllPlaybackHttpClient.newCall(request).execute()
                }
            }
            .build()
    }

    /**
     * Hosts that rejected an HTTP/1.1 request with 403 but accepted the same request over HTTP/2.
     * Kept in memory only (never persisted) so later requests to that host skip the failing
     * HTTP/1.1 attempt. Bounded so it can never grow without limit.
     */
    private val http2Hosts: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private const val HTTP2_HOSTS_MAX = 64

    /**
     * Playback stays on HTTP/1.1. Only for streams that carry addon proxyHeaders: if a request
     * gets 403 over HTTP/1.1, it is repeated once over HTTP/2 (ALPN, falling back to HTTP/1.1).
     * A successful retry marks the host, so the following requests go straight to HTTP/2.
     * Cleartext URLs never negotiate HTTP/2 and are left alone. If the retry also fails, the
     * original 403 is returned unchanged.
     */
    private class Http2On403Interceptor(
        private val http2Client: OkHttpClient,
        private val singleUseLink: Boolean,
        private val playlistDnsInUse: Boolean,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            // Fork: an IPTV / Stalker / own-source / single-use link is never repeated (a 403 there is
            // "spent", and the repeat is another request on a single-use URL). See Http2RetryPolicy.
            val host = request.url.host
            if (!Http2RetryPolicy.allowed(
                    isHttps = request.url.isHttps,
                    host = host,
                    singleUseLink = singleUseLink,
                    playlistDnsInUse = playlistDnsInUse,
                    isPanelHost = PlaybackTrustHosts::isPanelHost,
                    isMediaServerHost = PlaybackTrustHosts::isMediaServerHost,
                )
            ) {
                return chain.proceed(request)
            }
            if (host in http2Hosts) return http2Client.newCall(request).execute()

            val response = chain.proceed(request)
            if (response.code != 403) return response

            val retry = try {
                http2Client.newCall(request).execute()
            } catch (e: IOException) {
                return response
            }
            return if (retry.isSuccessful) {
                if (http2Hosts.size >= HTTP2_HOSTS_MAX) http2Hosts.clear()
                http2Hosts.add(host)
                response.close()
                retry
            } else {
                retry.close()
                response
            }
        }
    }

    /**
     * [dns]: the IPTV playlist's own resolver (DNS-over-HTTPS) for its VOD; null keeps the default
     * IPv4-first system DNS.
     */
    fun createHttpClient(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        dns: okhttp3.Dns? = null,
        singleUseLink: Boolean = false
    ): OkHttpClient {
        val builder = playbackHttpClient.newBuilder()
        if (dns != null) builder.dns(dns)
        if (useLongReadTimeout) {
            builder.readTimeout(LOOPBACK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val proxyHeaders = defaultHeaders.filterKeys { key ->
            !key.equals("Range", ignoreCase = true)
        }
        if (proxyHeaders.isNotEmpty()) {
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                val next = request.newBuilder()
                for ((key, value) in proxyHeaders) {
                    next.header(key, value)
                }
                chain.proceed(next.build())
            }
        }
        val http1Client = builder
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
        if (proxyHeaders.isEmpty()) return http1Client

        // Same client (same proxyHeaders network interceptor, pool and dispatcher) but allowed to
        // negotiate HTTP/2. It does not contain the retry interceptor below, so it cannot loop.
        val http2Client = http1Client.newBuilder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()
        return http1Client.newBuilder()
            .addInterceptor(Http2On403Interceptor(http2Client, singleUseLink, playlistDnsInUse = dns != null))
            .build()
    }

    @UnstableApi
    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        dns: okhttp3.Dns? = null,
        singleUseLink: Boolean = false
    ): DataSource.Factory {
        val requestHeaders = defaultHeaders.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || value.isEmpty() || key.equals("Range", ignoreCase = true)) {
                null
            } else {
                key to value
            }
        }.toMap()
        val client = createHttpClient(requestHeaders, useLongReadTimeout, dns, singleUseLink)
        val httpFactory = OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(requestHeaders)
            if (requestHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
        return LoggingDataSourceFactory(httpFactory, "HTTP")
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        dns: okhttp3.Dns? = null,
        singleUseLink: Boolean = false
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders, dns = dns, singleUseLink = singleUseLink))
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }
}
