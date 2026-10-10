package com.nuvio.tv.core.network

import java.util.concurrent.TimeUnit
import okhttp3.Call
import retrofit2.Invocation
import retrofit2.http.Streaming

/**
 * Whole-call limit for a TEXT request — an IPTV panel API call, a Stalker portal call, an addon manifest or
 * catalog — matching NuvioMobile's `textRequestCallTimeoutMs` and iOS's `requestTimeoutMillis` (60 s).
 *
 * Why: the clients' read timeout only fires after N seconds of SILENCE, so a provider that trickles a byte
 * now and then held a request — and the IPTV page waiting on it — forever. OkHttp's [Call.timeout] "spans
 * the entire call: resolving DNS, connecting, writing the request body, server processing, and reading the
 * response body" (okhttp3.Call KDoc), and RealCall enters it at `execute()` / `AsyncCall.run()`, so setting
 * it on the call before it runs is enough.
 *
 * Set PER CALL, never client-wide (`OkHttpClient.Builder.callTimeout`): the same clients stream a 190 MB M3U,
 * an XMLTV guide and the Xtream catalog-index body, which legitimately run far longer. Retrofit endpoints
 * marked [Streaming] are therefore left unlimited by [callFactory].
 */
object TextCallTimeout {
    /** A test shortens it. */
    @Volatile internal var limitMs: Long = 60_000L

    /** Puts the whole-call limit on [call] (before it executes). */
    fun <C : Call> apply(call: C): C = call.apply { timeout().timeout(limitMs, TimeUnit.MILLISECONDS) }

    /**
     * A Retrofit call factory over [delegate] that limits every call except a [Streaming] endpoint's (the
     * Invocation tag Retrofit attaches to each request names the interface method).
     */
    fun callFactory(delegate: Call.Factory): Call.Factory = Call.Factory { request ->
        val call = delegate.newCall(request)
        val streaming = request.tag(Invocation::class.java)?.method()?.isAnnotationPresent(Streaming::class.java) == true
        if (streaming) call else apply(call)
    }
}
