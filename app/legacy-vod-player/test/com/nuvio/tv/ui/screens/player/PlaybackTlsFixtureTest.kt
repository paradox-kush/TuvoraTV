package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.contracts.PlaybackTrustHosts
import com.nuvio.tv.data.mediaserver.TestHttpsServer
import com.nuvio.tv.data.mediaserver.rangeReply
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.net.ssl.SSLException

/**
 * The unverified-TLS fallback against a REAL self-signed HTTPS server: only a host that is an IPTV panel (and not
 * also a media server) is played; every other host class keeps its SSL failure.
 */
class PlaybackTlsFixtureTest {
    private val body = ByteArray(4_096) { (it % 97).toByte() }
    private val server = TestHttpsServer { _, headers -> rangeReply(body, "video/mp4", headers["range"]) }

    @After
    fun tearDown() {
        server.stop()
        PlaybackTrustHosts.registerPanelHostPredicate("fixture-panel") { false }
        PlaybackTrustHosts.registerMediaServerHostPredicate("fixture-server") { false }
    }

    private fun play(host: String): Int {
        val request = Request.Builder().url("https://$host:${server.port}/live.ts").build()
        return PlayerPlaybackNetworking.playbackHttpClient.newCall(request).execute().use { it.code }
    }

    private fun assertRefused(host: String, message: String) {
        try {
            play(host)
            fail("$message: expected an SSL failure")
        } catch (_: SSLException) {
        }
    }

    @Test
    fun anUnknownOrAddonHostKeepsItsSslFailure() {
        assertRefused("127.0.0.1", "unknown host")
        assertRefused("localhost", "add-on style host")
    }

    @Test
    fun anIptvPanelHostWithABadCertificateStillPlays() {
        PlaybackTrustHosts.registerPanelHostPredicate("fixture-panel") { it == "127.0.0.1" }

        assertEquals(200, play("127.0.0.1"))
        assertRefused("localhost", "a different host is not the panel")
    }

    @Test
    fun aMediaServerHostIsStrictEvenWhenItIsAlsoAPanelHost() {
        PlaybackTrustHosts.registerPanelHostPredicate("fixture-panel") { it == "127.0.0.1" }
        PlaybackTrustHosts.registerMediaServerHostPredicate("fixture-server") { it == "127.0.0.1" }

        assertRefused("127.0.0.1", "media server")
    }

    @Test
    fun theTrustAllClientItselfRefusesAHostThatIsNotAPanel() {
        PlaybackTrustHosts.registerPanelHostPredicate("fixture-panel") { it == "127.0.0.1" }
        val verifier = PlayerPlaybackNetworking.trustAllPlaybackHttpClient.hostnameVerifier

        assertTrue(verifier.verify("127.0.0.1", null))
        assertTrue("a redirect hop to another host is judged on its own", !verifier.verify("nas.lan", null))
    }
}
