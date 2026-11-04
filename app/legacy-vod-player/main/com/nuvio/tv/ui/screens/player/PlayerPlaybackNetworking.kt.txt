package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.network.PlaybackSocketFactory
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import com.nuvio.tv.core.contracts.PlaybackTlsClass
import com.nuvio.tv.core.contracts.PlaybackTrustHosts
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    /** The local torrent engine can block a read while it fetches pieces (upstream 76c05edd3). */
    private const val LOOPBACK_READ_TIMEOUT_SECONDS = 65L

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // Trust-all certificate chains are only ever used for an IPTV panel host. The verifier runs on EVERY hop
    // (redirects included), so a panel redirecting to a media server, an add-on or any other host is refused.
    private val playbackHostnameVerifier = HostnameVerifier { host, _ ->
        PlaybackTrustHosts.classify(host) == PlaybackTlsClass.PermissivePanel
    }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    /**
     * Fallback OkHttpClient with trust-all certificate chains, for IPTV PANEL hosts only: its hostname verifier
     * accepts a host only when [PlaybackTrustHosts] classifies it as a panel (and never a media-server host).
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
     * Includes an automatic fallback to [trustAllPlaybackHttpClient] if an [SSLException]
     * occurs on self-signed local media servers.
     */
    internal val playbackHttpClient: OkHttpClient by lazy {
        strictPlaybackHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    chain.proceed(request)
                } catch (e: SSLException) {
                    // Unverified TLS is a panel-only escape hatch (IPTV panels often present self-signed certificates).
                    // Media servers, add-ons, CDNs and unknown hosts keep the SSL failure.
                    if (PlaybackTrustHosts.classify(request.url.host) != PlaybackTlsClass.PermissivePanel) throw e
                    trustAllPlaybackHttpClient.newCall(request).execute()
                }
            }
            .build()
    }

    /**
     * The same client WITHOUT the trust-all fallback. A media-server host the user pinned (trust on first
     * use) is reached through a client derived from this one, so a changed certificate is refused rather
     * than silently accepted by the fallback.
     */
    internal val strictPlaybackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            .socketFactory(PlaybackSocketFactory)
            .eventListenerFactory(PlaybackConnectionEvents)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** The playback client for [url]: the pinned-certificate client for a pinned media-server host, else the normal one. */
    internal fun clientFor(url: String?): OkHttpClient =
        url?.let { com.nuvio.tv.data.mediaserver.ServerMediaRouting.router?.pinnedClient(it, strictPlaybackHttpClient) }
            ?: playbackHttpClient

    @OptIn(UnstableApi::class)
    fun createHttpClient(
        defaultHeaders: Map<String, String> = emptyMap(),
        dns: Dns? = null,
        useLongReadTimeout: Boolean = false,
        url: String? = null
    ): OkHttpClient {
        val builder = clientFor(url).newBuilder()
        if (useLongReadTimeout) {
            builder.readTimeout(LOOPBACK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        // Per-playlist DoH resolver (VOD/series). SNI/cert stay keyed on the original host, so this
        // works for https too — only mpv's live URL-rewrite can't do https (see PlaylistLivePlayback).
        if (dns != null) builder.dns(dns)
        if (defaultHeaders.any { it.key.equals("Authorization", ignoreCase = true) }) {
            // OkHttp strips the Authorization header on cross-host redirects.
            // WebDAV servers behind reverse proxies commonly redirect to a
            // different host/port, causing auth to be lost. A network
            // interceptor ensures the header is always present on every
            // outgoing request — same behavior as mpv/curl.
            val authValue = defaultHeaders.entries
                .first { it.key.equals("Authorization", ignoreCase = true) }
                .value
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") == null) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", authValue)
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
        }
        return builder
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
    }

    @OptIn(UnstableApi::class)
    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        dns: Dns? = null,
        useLongReadTimeout: Boolean = false,
        url: String? = null
    ): DataSource.Factory {
        val client = createHttpClient(defaultHeaders, dns, useLongReadTimeout, url)
        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap(),
        dns: Dns? = null,
        useLongReadTimeout: Boolean = false,
        url: String? = null
    ): DataSource.Factory {
        // DefaultDataSource keeps file/content/rtmp/udp/asset URIs working; only http(s) uses the
        // OkHttp (optionally DoH-resolved) upstream.
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders, dns, useLongReadTimeout, url))
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