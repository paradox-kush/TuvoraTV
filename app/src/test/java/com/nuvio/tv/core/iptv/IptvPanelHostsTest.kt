package com.nuvio.tv.core.iptv

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvPanelHostsTest {
    @After
    fun reset() = IptvPanelHosts.clearForTest()

    private fun account(base: String, portal: String = "", backups: List<String>? = null) =
        XtreamAccount(id = base, name = "p", baseUrl = base, username = "u", password = "p", portalUrl = portal, backupUrls = backups)

    @Test
    fun everyServerPortalAndBackupHostOfAnAccountIsAPanelHost() {
        val hosts = IptvPanelHosts.hostsOf(
            listOf(
                account("http://Panel.Example.com:8080", backups = listOf("https://b1.example.net", "http://b2.example.net:25461/")),
                account("", portal = "http://portal.example.org:80/c/")
            )
        )

        assertEquals(setOf("panel.example.com", "b1.example.net", "b2.example.net", "portal.example.org"), hosts)
    }

    @Test
    fun streamHostsHandedOutByTheLanesAreRememberedWithinTheCap() {
        IptvPanelHosts.note("http://cdn.stream-host.net:8000/live/u/p/1.ts")
        IptvPanelHosts.note(null)
        IptvPanelHosts.note("not a url ::")

        assertTrue(IptvPanelHosts.isPanelHost("CDN.stream-host.net"))
        assertFalse(IptvPanelHosts.isPanelHost("other.example.com"))
        repeat(600) { IptvPanelHosts.note("http://h$it.example.com/x") }
        assertFalse("the oldest host aged out (LRU cap)", IptvPanelHosts.isPanelHost("cdn.stream-host.net"))
        assertTrue(IptvPanelHosts.isPanelHost("h599.example.com"))
    }

    @Test
    fun accountHostsAreSwappedWholesale() {
        IptvPanelHosts.setAccounts(listOf(account("http://one.example.com")))
        assertTrue(IptvPanelHosts.isPanelHost("one.example.com"))
        IptvPanelHosts.setAccounts(listOf(account("http://two.example.com")))
        assertFalse(IptvPanelHosts.isPanelHost("one.example.com"))
    }

    @Test
    fun playlistNamesIdentifyMatchedIptvStreamLabels() {
        IptvPanelHosts.setAccounts(listOf(account("http://one.example.com").copy(name = " My Stalker ")))
        assertTrue("a matched IPTV stream carries the playlist name as addonName", IptvPanelHosts.isAccountName("My Stalker"))
        assertFalse("an add-on label is not a playlist", IptvPanelHosts.isAccountName("Torrentio"))
        IptvPanelHosts.setAccounts(emptyList())
        assertFalse("names are swapped wholesale", IptvPanelHosts.isAccountName("My Stalker"))
    }
}
