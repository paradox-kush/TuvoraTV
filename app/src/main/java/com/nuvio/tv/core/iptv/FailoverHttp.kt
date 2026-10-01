package com.nuvio.tv.core.iptv

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Step 0.3b — how a failover attempt talks to the OkHttp transports without changing their
 * signatures. TV twin of NuvioMobile/NuvioDesktop `HttpAttemptSignal` (same contract, OkHttp-shaped).
 * The failover executor installs one of these in the coroutine context of each attempt; the
 * transports then
 *  - build that request on [forFailoverAttempt]: the CONNECT phase gets [connectTimeoutMs] (read /
 *    overall timeouts keep their values), and
 *  - report the attempt's 2xx response HEADERS before any body byte is read ([HeadersSignalInterceptor]),
 *    which is where a racing attempt says "I answered" and — if another attempt already won — is
 *    parked until it is cancelled, so a loser never reads, let alone downloads, a body.
 *
 * Outside a failover walk there is no element and every transport behaves exactly as it always did.
 */
class HttpAttemptSignal(
    val connectTimeoutMs: Long?,
    /** Called with true/false when the attempt starts/stops waiting on a LOCAL queue (see [awaitingLocally]). */
    internal val onLocalWait: ((Boolean) -> Unit)? = null,
    private val onHeaders: (suspend () -> Unit)? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HttpAttemptSignal>

    private val fired = AtomicBoolean(false)

    /** Calls of this attempt sitting in OkHttp's dispatcher queue (see [failoverCallFactory]). */
    private val queuedCalls = AtomicInteger(0)

    internal fun enterDispatcherQueue() {
        val notify = onLocalWait ?: return
        queuedCalls.incrementAndGet()
        notify(true)
    }

    internal fun leaveDispatcherQueue() {
        val notify = onLocalWait ?: return
        if (queuedCalls.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) notify(false)
    }

    /** Whether this attempt's response headers decide anything (only a racing REAL request's do). */
    internal val reportsHeaders: Boolean get() = onHeaders != null

    internal suspend fun fire() {
        val callback = onHeaders ?: return
        if (!fired.compareAndSet(false, true)) return
        callback()
    }

    /**
     * [fire] from an OkHttp interceptor thread — the dispatcher thread of an enqueued (Retrofit) call or
     * the IO thread of a synchronous `execute()`, never Main. Blocks that thread until the race decided:
     * the winner continues at once, a loser is released by its cancellation (throws [CancellationException]).
     */
    internal fun fireBlocking() {
        if (onHeaders == null) return
        runBlocking { fire() }
    }

    /** Same connect timeout, no header callback. */
    fun silenced(): HttpAttemptSignal = HttpAttemptSignal(connectTimeoutMs, onLocalWait, null)
}

/** The CONNECT timeout the current failover attempt asks for, or null outside a failover walk. */
suspend fun failoverConnectTimeoutMs(): Long? = coroutineContext[HttpAttemptSignal]?.connectTimeoutMs

/**
 * For a transport that has no OkHttp response of its own to intercept (test fakes): reports 2xx
 * headers of a racing attempt. May suspend until the attempt is cancelled (it lost the race).
 */
suspend fun signalHttpHeaders() {
    coroutineContext[HttpAttemptSignal]?.fire()
}

/**
 * Runs [block] so that the HTTP calls inside it do NOT count as "the answer" of a racing attempt
 * (a Stalker handshake is preparation, not the response the race is about). Connect timeout stays.
 */
suspend fun <T> withoutHttpHeadersSignal(block: suspend () -> T): T {
    val current = coroutineContext[HttpAttemptSignal] ?: return block()
    return withContext(current.silenced()) { block() }
}

/**
 * Runs [block] — a wait on a LOCAL queue (a per-portal connection permit, "hold browse traffic while a
 * stream plays") — telling a racing failover walk that this time is not the server's latency, so the
 * stagger clock stops while it lasts. No-op outside a failover walk.
 */
suspend fun <T> awaitingLocally(block: suspend () -> T): T {
    val notify = coroutineContext[HttpAttemptSignal]?.onLocalWait ?: return block()
    notify(true)
    try {
        return block()
    } finally {
        notify(false)
    }
}

/** [this] client as the current failover attempt needs it (see [HttpAttemptSignal]); unchanged outside a walk. */
suspend fun OkHttpClient.forFailoverAttempt(): OkHttpClient = forFailoverAttempt(coroutineContext[HttpAttemptSignal])

/**
 * [this] with [signal]'s CONNECT timeout and, for a racing real request, the [HeadersSignalInterceptor].
 * `newBuilder()` shares the pool, dispatcher, DNS and cache, so this is one small allocation; the
 * interceptor is appended LAST — the innermost application interceptor — so it sees the true transport
 * answer (network or a fresh cache hit), below the stale-catalog fallback and the panel breaker.
 */
fun OkHttpClient.forFailoverAttempt(signal: HttpAttemptSignal?): OkHttpClient {
    if (signal == null) return this
    val builder = newBuilder()
    signal.connectTimeoutMs?.let { builder.connectTimeout(it, TimeUnit.MILLISECONDS) }
    // FIRST application interceptor: the call has left OkHttp's dispatcher queue (enqueued calls only).
    if (signal.onLocalWait != null) builder.interceptors().add(0, Interceptor { chain ->
        signal.leaveDispatcherQueue()
        chain.proceed(chain.request())
    })
    if (signal.reportsHeaders) builder.addInterceptor(HeadersSignalInterceptor(signal))
    return builder.build()
}

/**
 * The [Call.Factory] a Retrofit service uses inside a failover attempt: [forFailoverAttempt]'s client,
 * plus the time an ENQUEUED call waits in OkHttp's dispatcher queue (5 concurrent calls per host by
 * default — a hub fanning out a dozen catalog calls to one panel queues the rest) reported as a LOCAL
 * wait ([awaitingLocally]'s twin): a healthy main whose request merely queued behind its own siblings
 * must never look slow and hand the playlist to a backup.
 */
fun OkHttpClient.failoverCallFactory(signal: HttpAttemptSignal?): Call.Factory {
    val client = forFailoverAttempt(signal)
    if (signal?.onLocalWait == null) return client
    return Call.Factory { request ->
        signal.enterDispatcherQueue()
        client.newCall(request)
    }
}

/**
 * Reports a 2xx response's headers to the racing attempt BEFORE the body is handed to anyone
 * (`proceed` returns at the status line + headers; the body is still unread on the socket). A loser
 * is parked here until its cancellation, then closes the response unread.
 */
internal class HeadersSignalInterceptor(private val signal: HttpAttemptSignal) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        // Only the HOST's answer decides a race. A response served from the disk cache alone (a fresh
        // hit, or the stale stand-in the catalog fallback fetches through this same chain after the
        // host FAILED) completes on its own, where the executor judges it by its [ServedFrom] evidence.
        if (!response.isSuccessful || response.networkResponse == null) return response
        try {
            signal.fireBlocking()
        } catch (c: CancellationException) {
            response.close()
            throw IOException("Canceled: lost the failover race", c)
        }
        if (chain.call().isCanceled()) {
            response.close()
            throw IOException("Canceled")
        }
        return response
    }
}

/**
 * `execute()` + [block] (which reads the body) with coroutine cancellation wired to [Call.cancel]:
 * a blocking okio read never observes coroutine cancellation, but `cancel()` closes the socket, which
 * unblocks it. `onCancelling = true` fires as soon as the job is cancelled — BEFORE the blocking call
 * returns (a default `invokeOnCompletion` would only fire after it, i.e. never). A cancel-induced
 * IOException is rethrown as the [CancellationException] it really is, so a cancelled loser never reads
 * as a failure (breaker, stats, error text).
 */
@OptIn(InternalCoroutinesApi::class)
suspend inline fun <T> Call.executeCancellable(block: (Response) -> T): T {
    val call = this
    val hook = coroutineContext.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) call.cancel()
    }
    try {
        return call.execute().use(block)
    } catch (t: Throwable) {
        coroutineContext.ensureActive()
        throw t
    } finally {
        hook.dispose()
    }
}

/**
 * At most [maxBytes] of [source] as UTF-8 (a probe's prefix), whether or not the server honoured
 * `Range` — the caller then cancels the call instead of draining the rest (okio may have pulled one
 * buffer segment, ≤ 8 KiB, off the socket; nothing more is read). Null source = "".
 */
internal fun readAtMost(source: okio.BufferedSource?, maxBytes: Long): String {
    if (source == null) return ""
    val buffer = okio.Buffer()
    while (buffer.size < maxBytes) {
        if (source.read(buffer, maxBytes - buffer.size) == -1L) break
    }
    return buffer.readUtf8()
}
