package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "Add Playlist" form field->XtreamAccount mapping: the shared options the form collects
 * (EPG URL, DNS provider, auto-refresh) must land on the persisted account. This is the exact
 * pure mapping the settings ViewModel uses (withPlaylistOptions), so it guards the save path
 * without needing the Hilt-bound ViewModel.
 */
class XtreamPlaylistOptionsTest {

    @Test
    fun `manual fields plus options persist epg dns and auto-refresh`() {
        val account = xtreamAccountFromFields("host:8080", "u1", "p1", name = "Panel")!!
            .withPlaylistOptions(
                epgUrl = "http://epg.example/xmltv.php",
                dnsProvider = XtreamAccount.DNS_CLOUDFLARE,
                autoRefreshHours = 48
            )

        assertEquals("http://epg.example/xmltv.php", account.epgUrl)
        assertEquals(XtreamAccount.DNS_CLOUDFLARE, account.dnsProvider)
        assertEquals(48, account.autoRefreshHours)
        // credentials + source type unaffected by the options mapping
        assertEquals("http://host:8080", account.baseUrl)
        assertEquals("u1", account.username)
        assertEquals(XtreamAccount.SOURCE_XTREAM, account.sourceType)
    }

    /**
     * The edit path skips its live verify when the connection is unchanged. This pins what
     * "unchanged" means: shared options may move freely, anything that decides WHERE we connect
     * or WHO we connect as does not. Without this, a playlist whose provider is unreachable can
     * never be edited — including to switch its DNS resolver, which is what fixes some of those
     * unreachable providers in the first place.
     */
    @Test
    fun `same connection ignores shared options but not identity`() {
        val stalker = XtreamAccount(
            id = "stalker|http://portal:80|00:1A:79:32:31:37",
            name = "Portal",
            baseUrl = "http://portal:80",
            username = "",
            password = "",
            sourceType = XtreamAccount.SOURCE_STALKER,
            portalUrl = "http://portal:80",
            macAddress = "00:1A:79:32:31:37"
        )

        // Options-only edits: same connection, so the save must not need a reachable portal.
        assertTrue(stalker.copy(dnsProvider = XtreamAccount.DNS_CLOUDFLARE).sameConnectionAs(stalker))
        assertTrue(stalker.copy(epgUrl = "http://epg/x.xml").sameConnectionAs(stalker))
        assertTrue(stalker.copy(autoRefreshHours = 0).sameConnectionAs(stalker))
        assertTrue(stalker.copy(name = "Renamed").sameConnectionAs(stalker))
        assertTrue(stalker.copy(enabled = false).sameConnectionAs(stalker))

        // Anything that changes where/who we connect as: verify still runs.
        assertFalse(stalker.copy(portalUrl = "http://other:80").sameConnectionAs(stalker))
        assertFalse(stalker.copy(macAddress = "00:1A:79:00:00:01").sameConnectionAs(stalker))
        assertFalse(stalker.copy(serialNumber = "SN1").sameConnectionAs(stalker))
        assertFalse(stalker.copy(deviceId = "DEV1").sameConnectionAs(stalker))
        assertFalse(stalker.copy(sendDeviceId = false).sameConnectionAs(stalker))
        assertFalse(stalker.copy(stalkerUsername = "u").sameConnectionAs(stalker))
        assertFalse(stalker.copy(sourceType = XtreamAccount.SOURCE_URL).sameConnectionAs(stalker))

        val xtream = xtreamAccountFromFields("host:8080", "u1", "p1")!!
        assertTrue(xtream.copy(dnsProvider = XtreamAccount.DNS_CLOUDFLARE).sameConnectionAs(xtream))
        assertFalse(xtream.copy(username = "u2").sameConnectionAs(xtream))
        assertFalse(xtream.copy(password = "p2").sameConnectionAs(xtream))
        assertFalse(xtream.copy(baseUrl = "http://other:8080").sameConnectionAs(xtream))
    }

    @Test
    fun `blank epg url normalizes to null`() {
        val account = xtreamAccountFromFields("http://host:8080", "u1", "p1")!!
            .withPlaylistOptions(epgUrl = "   ", dnsProvider = XtreamAccount.DNS_SYSTEM, autoRefreshHours = 24)

        assertNull(account.epgUrl)
        assertEquals(XtreamAccount.DNS_SYSTEM, account.dnsProvider)
        assertEquals(24, account.autoRefreshHours)
    }

    @Test
    fun `pasted url path also carries the form options`() {
        val account = parseXtreamAccount(
            "http://host:8080/get.php?username=u1&password=p1&type=m3u_plus"
        )!!.withPlaylistOptions(
            epgUrl = null,
            dnsProvider = XtreamAccount.DNS_GOOGLE,
            autoRefreshHours = 0
        )

        assertEquals("u1", account.username)
        assertNull(account.epgUrl)
        assertEquals(XtreamAccount.DNS_GOOGLE, account.dnsProvider)
        assertEquals(0, account.autoRefreshHours)   // 0 = Off
    }

    @Test
    fun `options mapping preserves content selections`() {
        val base = xtreamAccountFromFields("http://host:8080", "u1", "p1")!!
            .copy(
                contentTypes = setOf(XtreamAccount.TYPE_MOVIES),
                categorySelections = CategorySelections(movies = listOf("10", "20"))
            )

        val account = base.withPlaylistOptions(
            epgUrl = "http://epg",
            dnsProvider = XtreamAccount.DNS_MULLVAD,
            autoRefreshHours = 72
        )

        assertEquals(setOf(XtreamAccount.TYPE_MOVIES), account.contentTypes)
        assertEquals(listOf("10", "20"), account.categorySelections.movies)
        assertEquals(XtreamAccount.DNS_MULLVAD, account.dnsProvider)
    }

    @Test
    fun `auto-refresh option set matches the form choices`() {
        // The picker offers Off / 6h / 12h / 24h / 48h / 72h; default is 24.
        assertEquals(listOf(0, 6, 12, 24, 48, 72), XtreamAccount.AUTO_REFRESH_OPTIONS)
        assertEquals(24, XtreamAccount.DEFAULT_AUTO_REFRESH_HOURS)
    }
}
