package com.nuvio.tv.playback.media3

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class Media3AuthorizationInterceptorTest {
    private lateinit var origin: MockWebServer
    private lateinit var child: MockWebServer

    @Before
    fun setUp() {
        origin = MockWebServer().also(MockWebServer::start)
        child = MockWebServer().also(MockWebServer::start)
    }

    @After
    fun tearDown() {
        origin.close()
        child.close()
    }

    @Test
    fun `STRIP removes authorization from cross origin HLS child request`() {
        child.enqueue(MockResponse.Builder().code(200).body("segment").build())
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(
                Media3AuthorizationInterceptor(
                    originUrl = origin.url("/master.m3u8"),
                    preserveAcrossOrigins = false,
                    authorization = "Bearer secret",
                ),
            )
            .build()
        client.newCall(
            Request.Builder()
                .url(child.url("/variant.ts"))
                .header("Authorization", "Bearer secret")
                .build(),
        ).execute().close()
        assertNull(child.takeRequest().headers["Authorization"])
    }

    @Test
    fun `PRESERVE adds authorization to cross origin HLS child request`() {
        child.enqueue(MockResponse.Builder().code(200).body("segment").build())
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(
                Media3AuthorizationInterceptor(
                    originUrl = origin.url("/master.m3u8"),
                    preserveAcrossOrigins = true,
                    authorization = "Bearer secret",
                ),
            )
            .build()
        client.newCall(Request.Builder().url(child.url("/variant.ts")).build()).execute().close()
        assertEquals("Bearer secret", child.takeRequest().headers["Authorization"])
    }
}
