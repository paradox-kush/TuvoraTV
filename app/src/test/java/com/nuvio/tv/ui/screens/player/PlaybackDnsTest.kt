package com.nuvio.tv.ui.screens.player

import java.net.InetAddress
import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B5: an IPTV playlist can resolve its panel through its own DNS-over-HTTPS resolver (the system DNS
 * may not resolve or may block it). The playback client must use that per-playlist [Dns] when the
 * player passes one, or VOD from a DoH playlist stops resolving.
 */
class PlaybackDnsTest {
    private val server = MockWebServer().apply {
        enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun aPerPlaylistDnsResolvesTheVodHost() {
        val asked = mutableListOf<String>()
        val doh = Dns { host ->
            asked += host
            if (host == "panel.doh-only.test") listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(host)
        }
        val client = PlayerPlaybackNetworking.createHttpClient(dns = doh)
        val request = Request.Builder().url("http://panel.doh-only.test:${server.port}/movie/1.mp4").build()

        val code = client.newCall(request).execute().use { it.code }

        assertEquals("the DoH-only host plays", 200, code)
        assertEquals("the playlist resolver was asked", listOf("panel.doh-only.test"), asked)
    }
}
