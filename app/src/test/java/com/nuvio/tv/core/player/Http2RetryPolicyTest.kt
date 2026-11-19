package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class Http2RetryPolicyTest {
    private val panels = setOf("panel.example.com", "cdn.iptv.example.net")
    private val servers = setOf("media.example.org")

    private fun allowed(
        host: String? = "addon.example.com",
        https: Boolean = true,
        singleUse: Boolean = false,
        playlistDns: Boolean = false,
    ) = Http2RetryPolicy.allowed(https, host, singleUse, playlistDns, { it in panels }, { it in servers })

    @Test
    fun `an add-on stream on a plain https host may retry over HTTP2`() {
        assertEquals("add-on stream", true, allowed())
    }

    @Test
    fun `an IPTV panel host is never retried because its 403 means spent or expired`() {
        assertEquals("panel host", false, allowed(host = "panel.example.com"))
        assertEquals("noted IPTV CDN host", false, allowed(host = "CDN.IPTV.example.net."))
    }

    @Test
    fun `a single-use link is never retried`() {
        assertEquals("single use", false, allowed(singleUse = true))
    }

    @Test
    fun `a playlist with its own resolver is IPTV and is never retried`() {
        assertEquals("playlist dns", false, allowed(playlistDns = true))
    }

    @Test
    fun `a media server host is never retried`() {
        assertEquals("media server", false, allowed(host = "media.example.org"))
    }

    @Test
    fun `cleartext and unknown hosts are left alone`() {
        assertEquals("cleartext", false, allowed(https = false))
        assertEquals("no host", false, allowed(host = null))
        assertEquals("blank host", false, allowed(host = "  "))
    }
}
