package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TV twin of NuvioMobile's XtreamUrlPastedAsM3uTest: an Xtream `get.php` URL pasted as an M3U
 * playlist is recognised as an Xtream panel at ADD time via [xtreamPanelInM3uUrl]. The M3U identity
 * builder [m3uAccountFromUrl] is untouched — it also runs on edit, pairing and sync, where an
 * existing `m3u:…` playlist must keep its id (M3UUrlParserTest pins that derivation).
 */
class XtreamUrlPastedAsM3uTest {

    @Test
    fun `an xtream get php url pasted as m3u is recognised as an xtream panel at add time`() {
        val account = xtreamPanelInM3uUrl(
            "http://panel.example.com:8080/get.php?username=u&password=p&type=m3u_plus&output=ts",
            userAgent = " VLC/3.0 ",
        )
        assertNotNull(account)
        account!!
        assertEquals("get.php with username+password is an Xtream panel, not a bare M3U", XtreamAccount.SOURCE_XTREAM, account.sourceType)
        assertEquals("http://panel.example.com:8080", account.baseUrl)
        assertEquals("u", account.username)
        assertEquals("p", account.password)
        assertEquals("the UA carries over", "VLC/3.0", account.userAgent)
    }

    @Test
    fun `the player api root is recognised too`() {
        val account = xtreamPanelInM3uUrl("http://panel.example.com/player_api.php?username=u&password=p")
        assertNotNull(account)
        assertEquals(XtreamAccount.SOURCE_XTREAM, account!!.sourceType)
        assertEquals("http://panel.example.com", account.baseUrl)
    }

    @Test
    fun `a plain m3u url is not a panel`() {
        assertNull(xtreamPanelInM3uUrl("http://lists.example.com/uk.m3u"))
        assertNull("no credentials means no panel", xtreamPanelInM3uUrl("http://lists.example.com/get.php?type=m3u"))
    }

    @Test
    fun `the m3u identity builder itself is unchanged`() {
        val account = m3uAccountFromUrl("http://panel.example.com:8080/get.php?username=u&password=p&type=m3u_plus")
        assertNotNull(account)
        assertEquals(XtreamAccount.SOURCE_URL, account!!.sourceType)
        assertEquals("m3u:http://panel.example.com:8080/get.php", account.id)
    }
}
