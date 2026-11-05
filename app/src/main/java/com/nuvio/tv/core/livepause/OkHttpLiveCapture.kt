package com.nuvio.tv.core.livepause

import com.nuvio.tv.playback.core.ApplicationDnsKey
import com.nuvio.tv.playback.core.ProxyMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

// The capture I/O lives in LiveCaptureLoop (see its header for provenance); this is the pause adapter.

/** Mints the HTTP client for a link (honouring the playlist's DNS). Null = that DNS cannot be built. */
fun interface LiveCaptureClients {
    fun clientFor(link: LiveLink): OkHttpClient?
}

class OkHttpLiveCapture(
    spec: LiveCaptureSpec,
    scope: CoroutineScope,
    store: FileLiveSegmentStore,
    clients: LiveCaptureClients,
    io: CoroutineDispatcher,
    monotonicMs: () -> Long,
    freeBytes: () -> Long,
    cleanupScope: CoroutineScope,
) : LiveCapture {
    // The pause buffer is the shared capture loop with rewind retention.
    private val loop = LiveCaptureLoop(
        spec, scope, store, clients, io, monotonicMs, freeBytes, cleanupScope, LiveRetention.Trim(spec.keepMs),
    )
    override val events: SharedFlow<LiveCaptureEvent> get() = loop.events
    override fun start() = loop.start()
    override fun dispose() = loop.dispose()
}

/** Builds one capture per player instance under `cacheDir/live-pause/<instanceId>/`. */
class OkHttpLiveCaptureFactory(
    private val root: File,
    private val baseClient: OkHttpClient,
    private val dnsFor: (ApplicationDnsKey) -> Dns?,
    private val io: CoroutineDispatcher,
    private val cleanupScope: CoroutineScope,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val freeBytes: (File) -> Long = { it.usableSpace },
) : LiveCaptureFactory {
    override fun create(spec: LiveCaptureSpec, scope: CoroutineScope): LiveCapture {
        val dir = File(root, spec.instanceId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "player" })
        dir.deleteRecursively()
        val store = FileLiveSegmentStore(dir)
        val clients = LiveCaptureClients { link -> buildCaptureClient(baseClient, link, dnsFor) }
        return OkHttpLiveCapture(spec, scope, store, clients, io, monotonicMs, { freeBytes(dir) }, cleanupScope)
    }
}

/**
 * The capture's HTTP client for [link]: the app's stack with the link's own network policy (proxy mode,
 * redirect policy, connect/read timeouts) and the playlist's DNS, as the player's request carries them.
 * Null = that DNS cannot be built.
 */
internal fun buildCaptureClient(base: OkHttpClient, link: LiveLink, dnsFor: (ApplicationDnsKey) -> Dns?): OkHttpClient? {
    val net = link.network
    val builder = base.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(link.followRedirects)
        .followSslRedirects(link.followRedirects)
        .connectTimeout(net.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(net.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
    when (net.proxyMode) {
        ProxyMode.SYSTEM -> Unit
        ProxyMode.DIRECT -> builder.proxy(Proxy.NO_PROXY)
        ProxyMode.HTTP -> {
            val proxy = requireNotNull(net.httpProxy)
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
            if (proxy.username != null && proxy.password != null) {
                val credential = Credentials.basic(proxy.username.value, proxy.password.value)
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") == credential) null
                    else response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            }
        }
    }
    val key = link.dnsKey
    if (key != null) builder.dns(dnsFor(key) ?: return null)
    return builder.build()
}
