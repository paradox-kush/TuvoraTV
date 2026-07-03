package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
