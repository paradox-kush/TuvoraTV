package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.network.TextCallTimeout
import com.nuvio.tv.data.remote.api.XtreamApi
import com.nuvio.tv.data.remote.dto.FlexIntAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * A whole-request deadline on a streamed Xtream catalog must stop the TRANSPORT, not just abandon the
 * coroutine. The body is parsed by a blocking okio read that coroutine cancellation never interrupts, so
 * without cancelling the OkHttp call a `withTimeout` would wait for the trickling body to finish
 * (measured on a real panel: ~0.45 KB/s, never tripping the 60 s per-read timeout).
 */
class StreamedCatalogCancelTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.close() } }

    @Test
    fun `a deadline cancels a streamed catalog body that trickles`() {
        // ~60 KB of valid JSON, released 200 bytes per second: minutes of body if nobody cancels the call.
        val rows = (1..1500).joinToString(",", "[", "]") { """{"series_id":$it,"name":"Show $it","category_id":"1"}""" }
        server.enqueue(MockResponse.Builder().body(rows).throttleBody(200, 1, TimeUnit.SECONDS).build())
        val moshi = Moshi.Builder().add(FlexIntAdapter).add(KotlinJsonAdapterFactory()).build()
        val api = Retrofit.Builder()
            .baseUrl("https://placeholder.nuvio.tv/")
            .callFactory(TextCallTimeout.callFactory(OkHttpClient()))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build().create(XtreamApi::class.java)
        val client = XtreamClient(api, OkHttpClient(), moshi, com.nuvio.tv.core.iptv.dns.PlaylistDns(), PlaylistServerFailover.detached())
        val acc = XtreamAccount(
            id = "trickle", name = "trickle", baseUrl = server.url("/").toString().trimEnd('/'), username = "u", password = "p",
        )

        val startedAt = System.nanoTime()
        val result = runBlocking { withTimeoutOrNull(1_000) { client.seriesIndexItemsInto(acc, null) { } } }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000

        assertNull("the deadline fired", result)
        assertTrue("the blocked read was interrupted (took $tookMs ms), not waited out", tookMs < 5_000)
    }
}
