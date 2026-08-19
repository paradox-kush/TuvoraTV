package com.nuvio.tv.core.diagnostics

import android.util.Log
import com.nuvio.tv.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Debug-only HTTP tracer. Logs each call's source [tag] + host + path + status + timing to logcat
 * ("HttpTrace"), so cold-start network can be split by source (image vs catalog vs EPG) — see the
 * anti-jank design's F1 question.
 *
 * NEVER logs the query string: Xtream URLs carry username/password there. Gated on IS_DEBUG_BUILD,
 * NOT BuildConfig.DEBUG — TV debug builds set isDebuggable=false, so BuildConfig.DEBUG is false and
 * the stock HttpLoggingInterceptor silently never fires. Never ships; never leaves the device.
 */
class HttpTraceInterceptor(private val tag: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!BuildConfig.IS_DEBUG_BUILD) return chain.proceed(chain.request())
        val req = chain.request()
        val t0 = System.nanoTime()
        val resp = chain.proceed(req)
        val ms = (System.nanoTime() - t0) / 1_000_000
        // Classify the call WITHOUT leaking credentials: emit ONLY the `action` and `type` query
        // params by exact name. Stalker portal.php keeps mac/token in Cookie/Authorization headers,
        // and Xtream's username/password are NEVER in this whitelist — so no secret can ride along.
        val cls = listOf("type", "action")
            .mapNotNull { k -> req.url.queryParameter(k)?.let { "$k=$it" } }
            .joinToString(" ")
            .let { if (it.isEmpty()) "" else " [$it]" }
        Log.d("HttpTrace", "$tag ${req.method} ${req.url.host}${req.url.encodedPath} ${resp.code} ${ms}ms$cls")
        return resp
    }
}
