package com.nuvio.tv.core.network

import com.nuvio.tv.core.di.NetworkModule
import com.squareup.moshi.Moshi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Regression (TV twin of NuvioMobile's TextRequestCallTimeoutTest): a provider that trickles its answer —
 * a byte now and then, so the read timeout (which only measures silence) never fires — held a panel/addon
 * text request, and the IPTV page waiting on it, forever. Text requests now carry a whole-call limit, set
 * per call, so a @Streaming download through the same Retrofit still runs as long as it needs.
 *
 * Drives the PRODUCTION Retrofit ([NetworkModule.provideRetrofit], the one AddonApi + XtreamApi ride) over a
 * real socket.
 */
class TextRequestCallTimeoutTest {
    private interface PanelApi {
        @GET suspend fun text(@Url url: String): Response<ResponseBody>
        @Streaming @GET suspend fun streamed(@Url url: String): Response<ResponseBody>
    }

    private val server = MockWebServer()
    // Generous read timeout: the trickle never goes silent for that long, so only a whole-call limit can end it.
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val api = NetworkModule.provideRetrofit(client, Moshi.Builder().build()).create(PanelApi::class.java)

    /** 12 bytes at one per 150 ms: ~1.8 s to read, never 150 ms silent. */
    private fun trickle() = MockResponse.Builder()
        .body("[\"abcdefghij\"]".take(12))
        .throttleBody(1, 150, TimeUnit.MILLISECONDS)
        .build()

    @Before
    fun setUp() {
        server.start()
        TextCallTimeout.limitMs = 600L
    }

    @After
    fun tearDown() {
        TextCallTimeout.limitMs = 60_000L
        server.close()
    }

    @Test
    fun `a provider that trickles bytes cannot hold a text request past the call limit`() = runBlocking {
        server.enqueue(trickle())
        val started = System.currentTimeMillis()

        val result = runCatching { api.text(server.url("/player_api.php").toString()).body()?.string() }
        val waited = System.currentTimeMillis() - started

        assertTrue("the trickled text request must fail, not return (got $result)", result.isFailure)
        assertTrue("the call limit must end it near 600 ms, not at the end of the trickle (waited ${waited}ms)", waited < 1_500)
    }

    @Test
    fun `a streamed download through the same client is not cut by the text limit`() = runBlocking {
        server.enqueue(trickle())

        val body = api.streamed(server.url("/playlist.m3u").toString()).body()?.use { it.string() }

        assertEquals("a @Streaming body reads to its end, however long it takes", 12, body?.length)
    }
}
