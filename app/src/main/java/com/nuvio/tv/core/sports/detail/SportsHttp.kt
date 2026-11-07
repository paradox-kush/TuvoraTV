package com.nuvio.tv.core.sports.detail

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HttpResponse(val code: Int, val body: String?, val etag: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Port over the existing OkHttp stack. Throws [IOException] on transport failure or when the deadline passes. */
interface HttpFetcher {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), deadlineMs: Long): HttpResponse
}

/**
 * Whole-call deadline per request (design: 8 s) set on a derived client so the shared connection
 * pool is reused. Cancelling the coroutine cancels the call.
 */
class OkHttpFetcher(private val client: OkHttpClient) : HttpFetcher {
    override suspend fun get(url: String, headers: Map<String, String>, deadlineMs: Long): HttpResponse {
        val call = client.newBuilder().callTimeout(deadlineMs, TimeUnit.MILLISECONDS).build()
            .newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = try {
                            HttpResponse(it.code, it.body?.string(), it.header("ETag"))
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                            return
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                }
            })
        }
    }
}
