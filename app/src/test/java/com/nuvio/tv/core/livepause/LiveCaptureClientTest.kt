package com.nuvio.tv.core.livepause

import com.nuvio.tv.playback.core.PlaybackNetworkRequest
import com.nuvio.tv.playback.core.ProxyMode
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.Proxy

class LiveCaptureClientTest {
    @Test
    fun `the capture client carries the link's proxy mode redirect policy and timeouts`() {
        val link = LiveLink(
            url = "http://h/x",
            headers = emptyMap(),
            network = PlaybackNetworkRequest(proxyMode = ProxyMode.DIRECT, connectTimeoutMs = 7_000, readTimeoutMs = 9_000),
            followRedirects = false,
        )

        val client = buildCaptureClient(OkHttpClient(), link) { null }!!

        assertEquals("connect timeout", 7_000, client.connectTimeoutMillis)
        assertEquals("read timeout", 9_000, client.readTimeoutMillis)
        assertFalse("redirect policy REJECT", client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals("proxy mode DIRECT", Proxy.NO_PROXY, client.proxy)
    }
}
