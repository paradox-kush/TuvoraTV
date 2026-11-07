package com.nuvio.tv.core.sports.detail

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OkHttpFetcherTest {
    private fun client(handler: (okhttp3.Request) -> Response) =
        OkHttpClient.Builder().addInterceptor(Interceptor { handler(it.request()) }).build()

    private fun reply(req: okhttp3.Request, code: Int, body: String, etag: String? = null) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m")
            .apply { if (etag != null) header("ETag", etag) }
            .body(body.toByteArray().toResponseBody("application/json".toMediaType())).build()

    @Test fun `returns code body and etag and forwards headers`() = runBlocking {
        var seen: okhttp3.Request? = null
        val f = OkHttpFetcher(client { seen = it; reply(it, 200, "{}", "\"v1\"") })
        val r = f.get("https://example.test/x", mapOf("If-None-Match" to "\"v0\""), 8_000)
        assertEquals("code", 200, r.code)
        assertEquals("body", "{}", r.body)
        assertEquals("etag", "\"v1\"", r.etag)
        assertEquals("header forwarded", "\"v0\"", seen!!.header("If-None-Match"))
    }

    @Test fun `non-2xx is returned not thrown`() = runBlocking {
        val r = OkHttpFetcher(client { reply(it, 403, "denied") }).get("https://example.test/x", deadlineMs = 8_000)
        assertEquals("code", 403, r.code)
        assertTrue("not successful", !r.isSuccessful)
    }

    @Test fun `transport failure surfaces as IOException`() = runBlocking {
        val f = OkHttpFetcher(client { throw IOException("reset") })
        val e = runCatching { f.get("https://example.test/x", deadlineMs = 8_000) }.exceptionOrNull()
        assertTrue("io", e is IOException)
    }
}
