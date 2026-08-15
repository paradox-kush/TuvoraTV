package com.nuvio.tv.core.iptv.stalker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The static-vs-mint decision for Stalker playback, verified against three independent sources
 * (the portal server's `itv.class.php`, Kodi pvr.stalker's ChannelManager, iptvnator's
 * stalker-link-semantics utils) plus the portal's own `/c/player.js`:
 *
 *   `if (use_http_tmp_link == 1 || use_load_balancing == 1) → create_link else play cmd`
 *
 * Every guard below only ever pushes a row BACK onto today's create_link path, so a wrong verdict
 * can never regress a portal that works now — the risky direction (playing a static placeholder)
 * is what these tests pin shut. Twin of NuvioMobile's StalkerPlaybackLinkPolicyTest.
 */
class StalkerPlaybackLinkPolicyTest {

    private fun decide(tmp: Boolean?, lb: Boolean?, cmd: String?) =
        StalkerPlaybackLinkPolicy.decide(useHttpTmpLink = tmp, useLoadBalancing = lb, cmd = cmd)

    private fun assertStatic(expectedUrl: String, tmp: Boolean?, lb: Boolean?, cmd: String?) {
        assertEquals(
            "expected STATIC($expectedUrl) for cmd=$cmd tmp=$tmp lb=$lb",
            StalkerPlaybackLinkPolicy.Decision.Static(expectedUrl),
            decide(tmp, lb, cmd)
        )
    }

    private fun assertMint(tmp: Boolean?, lb: Boolean?, cmd: String?) {
        assertEquals(
            "expected MINT for cmd=$cmd tmp=$tmp lb=$lb",
            StalkerPlaybackLinkPolicy.Decision.Mint,
            decide(tmp, lb, cmd)
        )
    }

    // --- the two flags are the rule -------------------------------------------------------------

    @Test
    fun `use_http_tmp_link true means mint`() =
        assertMint(tmp = true, lb = false, cmd = "ffmpeg http://cdn.example.com/ch/1.ts")

    @Test
    fun `use_load_balancing true means mint`() =
        assertMint(tmp = false, lb = true, cmd = "ffmpeg http://cdn.example.com/ch/1.ts")

    @Test
    fun `both flags known-false on a clean absolute url is static`() =
        assertStatic(
            "http://cdn.example.com:8080/ch/1.ts",
            tmp = false, lb = false, cmd = "ffmpeg http://cdn.example.com:8080/ch/1.ts"
        )

    @Test
    fun `a bare url without launcher prefix is static`() =
        assertStatic("https://cdn.example.com/a.m3u8", tmp = false, lb = false, cmd = "https://cdn.example.com/a.m3u8")

    @Test
    fun `ffrt numbered variants strip to a static url`() {
        assertStatic("http://cdn.example.com/x", tmp = false, lb = false, cmd = "ffrt2 http://cdn.example.com/x")
        assertStatic("http://cdn.example.com/x", tmp = false, lb = false, cmd = "ffrt4 http://cdn.example.com/x")
    }

    // --- absence of evidence keeps minting ------------------------------------------------------

    /** Legacy cached rows predate flag storage: no flags = no evidence = keep minting. */
    @Test
    fun `absent flags mean mint`() =
        assertMint(tmp = null, lb = null, cmd = "ffmpeg http://cdn.example.com/ch/1.ts")

    @Test
    fun `a true flag next to an absent one still mints`() =
        assertMint(tmp = true, lb = null, cmd = "ffmpeg http://cdn.example.com/ch/1.ts")

    /** One flag present is portal provenance — stock portals send both on every row. */
    @Test
    fun `one known-false flag is evidence enough for static`() =
        assertStatic("http://cdn.example.com/x", tmp = false, lb = null, cmd = "http://cdn.example.com/x")

    // --- rtp and udp are static (multicast cannot carry a token) --------------------------------

    @Test
    fun `rtp cmd with flags known-false is static`() =
        assertStatic("rtp://239.0.0.1:5000", tmp = false, lb = false, cmd = "rtp://239.0.0.1:5000")

    @Test
    fun `udp cmd with flags known-false is static even behind a launcher`() =
        assertStatic("udp://239.0.0.1:1234", tmp = false, lb = false, cmd = "ffrt udp://239.0.0.1:1234")

    /**
     * The stock listing MASKS a wowza-balancing row as `udp://ch/<id>` with use_http_tmp_link=1
     * (itv.class.php get_all_channels) — that cmd is a placeholder only create_link can resolve,
     * so the flag must win over the scheme.
     */
    @Test
    fun `a flagged udp placeholder still mints`() =
        assertMint(tmp = true, lb = false, cmd = "udp://ch/123")

    @Test
    fun `udp without flag evidence mints`() =
        assertMint(tmp = null, lb = null, cmd = "udp://239.0.0.1:1234")

    // --- shapes only the portal can resolve -----------------------------------------------------

    @Test
    fun `a non-http scheme after the prefix strip mints`() {
        assertMint(tmp = false, lb = false, cmd = "ffrt4://ch/live/1")
        assertMint(tmp = false, lb = false, cmd = "rtmp://host.example.com/live/1")
    }

    /** The VOD has_files rewrite produces exactly this shape. */
    @Test
    fun `a relative cmd mints`() =
        assertMint(tmp = false, lb = false, cmd = "/media/file_12.mpg")

    @Test
    fun `a query-only cmd mints`() =
        assertMint(tmp = false, lb = false, cmd = "?token=abc")

    /** Stock masking with an empty stream_proxy config: `ffrt http:///ch/<id>` — no authority. */
    @Test
    fun `http with an empty authority mints`() =
        assertMint(tmp = false, lb = false, cmd = "ffrt http:///ch/1")

    @Test
    fun `a blank cmd mints`() {
        assertMint(tmp = false, lb = false, cmd = null)
        assertMint(tmp = false, lb = false, cmd = "   ")
    }

    // --- portal-local hosts: an address only the portal could resolve ---------------------------

    @Test
    fun `localhost mints`() =
        assertMint(tmp = false, lb = false, cmd = "ffmpeg http://localhost/ch/291")

    @Test
    fun `localhost with port and trailing dot mints`() {
        assertMint(tmp = false, lb = false, cmd = "http://localhost:8080/ch/1")
        assertMint(tmp = false, lb = false, cmd = "http://localhost./ch/1")
    }

    /** RFC 6761 reserves every name under .localhost for loopback. */
    @Test
    fun `localhost subdomains mint`() {
        assertMint(tmp = false, lb = false, cmd = "http://stream.localhost/ch/1")
        assertMint(tmp = false, lb = false, cmd = "http://localhost.localdomain/ch/1")
    }

    @Test
    fun `the whole ipv4 loopback range mints`() {
        assertMint(tmp = false, lb = false, cmd = "http://127.0.0.1/ch/1")
        assertMint(tmp = false, lb = false, cmd = "http://127.5.4.3:8080/stream")
        assertMint(tmp = false, lb = false, cmd = "http://0.0.0.0/ch/1")
    }

    @Test
    fun `ipv6 loopback mints`() {
        assertMint(tmp = false, lb = false, cmd = "http://[::1]/ch/1")
        assertMint(tmp = false, lb = false, cmd = "http://[0:0:0:0:0:0:0:1]:8080/ch/1")
        assertMint(tmp = false, lb = false, cmd = "http://[::]/ch/1")
    }

    @Test
    fun `ipv6 mapped loopback mints`() {
        assertMint(tmp = false, lb = false, cmd = "http://[::ffff:127.0.0.1]/ch/1")
        assertMint(tmp = false, lb = false, cmd = "http://[::ffff:7f00:1]/ch/1")
    }

    /** A near-miss host must NOT be swallowed by the loopback guards. */
    @Test
    fun `ordinary hosts are not mistaken for loopback`() {
        assertStatic("http://127x.example.com/s", tmp = false, lb = false, cmd = "http://127x.example.com/s")
        assertStatic("http://mylocalhost.example.com/s", tmp = false, lb = false, cmd = "http://mylocalhost.example.com/s")
        assertStatic("http://10.0.0.7:8080/s", tmp = false, lb = false, cmd = "http://10.0.0.7:8080/s")
    }
}
