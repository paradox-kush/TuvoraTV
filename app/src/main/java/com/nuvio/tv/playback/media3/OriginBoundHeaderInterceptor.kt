package com.nuvio.tv.playback.media3

import com.nuvio.tv.playback.core.OriginBoundHeaders
import okhttp3.Interceptor
import okhttp3.Response

/**
 * B259: puts one host's credentials (a Stalker portal's MAC cookie, bearer token, Referer, Origin) on
 * the hops that may carry them and takes them off every other hop.
 *
 * A NETWORK interceptor on purpose: OkHttp follows redirects above this layer and re-sends the
 * caller's request headers to the new host (it drops only Authorization), so an application-level
 * header would reach a foreign redirect target. Here every hop — the first request, each redirect,
 * each HLS child — is decided by [OriginBoundHeaders.allowsHop] on its own URL. The credentials are
 * never default request properties, so nothing above this layer holds them.
 */
internal class OriginBoundHeaderInterceptor(private val bound: OriginBoundHeaders) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        if (bound.allowsHop(request.url.scheme, request.url.host)) {
            bound.headers.forEach { (name, value) -> builder.header(name, value) }
        } else {
            bound.headers.keys.forEach { name -> builder.removeHeader(name) }
        }
        return chain.proceed(builder.build())
    }
}
