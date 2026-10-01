package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Step 0 — the pure "which local ids must be re-keyed to which pulled keys" decision (TV twin). */
class PlaylistKeyAdoptionTest {

    private fun xtream(id: String, base: String, user: String = "u") =
        XtreamAccount(id = id, name = "P", baseUrl = base, username = user, password = "p")

    private fun m3u(id: String, url: String) =
        XtreamAccount(id = id, name = "M", baseUrl = url, username = "", password = "", sourceType = XtreamAccount.SOURCE_URL)

    private fun file(id: String, fileName: String) =
        XtreamAccount(id = id, name = "F", baseUrl = "", username = "", password = "", sourceType = XtreamAccount.SOURCE_FILE, fileName = fileName)

    private fun stalker(id: String, portal: String, mac: String) = XtreamAccount(
        id = id, name = "S", baseUrl = portal, username = "", password = "",
        sourceType = XtreamAccount.SOURCE_STALKER, portalUrl = portal, macAddress = mac,
    )

    private fun keyed(a: XtreamAccount) = PulledPlaylist(a, serverKeyed = true)
    private fun derived(a: XtreamAccount) = PulledPlaylist(a, serverKeyed = false)

    @Test
    fun `TV's old m3u colon id is re-keyed onto the server's key for the same link`() {
        val local = m3u("m3u:http://h/list.m3u", "http://h/list.m3u?u=1")
        val pulled = m3u("m3u|http://h/list.m3u?u=1", "http://h/list.m3u?u=1")
        val result = PlaylistKeyAdoption.resolve(listOf(keyed(pulled)), listOf(local))
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("m3u:http://h/list.m3u", "m3u|http://h/list.m3u?u=1")), result.rekeys)
        assertEquals(listOf("m3u|http://h/list.m3u?u=1"), result.accounts.map { it.id })
    }

    @Test
    fun `a rescued orphan key moves the current-address id onto the old key`() {
        val result = PlaylistKeyAdoption.resolve(
            listOf(keyed(xtream("http://old.example|u", "http://new.example"))),
            listOf(xtream("http://new.example|u", "http://new.example")),
        )
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("http://new.example|u", "http://old.example|u")), result.rekeys)
    }

    @Test
    fun `a TV file playlist adopts the server's file key`() {
        val result = PlaylistKeyAdoption.resolve(
            listOf(keyed(file("m3u_file|tv.m3u|synced", "tv.m3u"))),
            listOf(file("file:0b8f6c1e-uuid", "tv.m3u")),
        )
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("file:0b8f6c1e-uuid", "m3u_file|tv.m3u|synced")), result.rekeys)
    }

    @Test
    fun `a stalker portal matches on the normalized portal and MAC`() {
        val result = PlaylistKeyAdoption.resolve(
            listOf(keyed(stalker("stalker|http://p.example|00:1A:79:AA:BB:CC", "http://P.example:80/c/", "00:1a:79:aa:bb:cc"))),
            listOf(stalker("stalker|http://p.example/c/|00:1a:79:aa:bb:cc", "http://p.example/c/", "00:1a:79:aa:bb:cc")),
        )
        assertEquals(1, result.rekeys.size)
    }

    @Test
    fun `a row without a server key keeps the local id instead of re-deriving it`() {
        val result = PlaylistKeyAdoption.resolve(
            listOf(derived(xtream("http://new.example|u", "http://new.example"))),
            listOf(xtream("http://old.example|u", "http://new.example")),
        )
        assertEquals("no data moves for an un-keyed row", emptyList<PlaylistKeyAdoption.Rekey>(), result.rekeys)
        assertEquals(listOf("http://old.example|u"), result.accounts.map { it.id })
    }

    @Test
    fun `adoption is idempotent - the next pull re-keys nothing`() {
        val pulled = listOf(keyed(m3u("m3u|http://h/a.m3u", "http://h/a.m3u")))
        val first = PlaylistKeyAdoption.resolve(pulled, listOf(m3u("m3u:http://h/a.m3u", "http://h/a.m3u")))
        assertEquals(1, first.rekeys.size)
        val second = PlaylistKeyAdoption.resolve(pulled, first.accounts)
        assertEquals(emptyList<PlaylistKeyAdoption.Rekey>(), second.rekeys)
    }

    @Test
    fun `a local id the pull still lists is never re-keyed and matching is one to one`() {
        val kept = PlaylistKeyAdoption.resolve(
            listOf(keyed(xtream("k2", "http://a.example")), keyed(xtream("k1", "http://a.example"))),
            listOf(xtream("k1", "http://a.example")),
        )
        assertEquals(emptyList<PlaylistKeyAdoption.Rekey>(), kept.rekeys)
        val oneToOne = PlaylistKeyAdoption.resolve(
            listOf(keyed(xtream("key", "http://A.example:80/"))),
            listOf(xtream("old-a", "http://a.example"), xtream("old-b", "http://a.example")),
        )
        assertEquals(listOf(PlaylistKeyAdoption.Rekey("old-a", "key")), oneToOne.rekeys)
    }

    @Test
    fun `pending ops are rewritten onto the adopted key`() {
        val edited = xtream("old", "http://a.example")
        val out = PlaylistKeyAdoption.rewritePending(
            listOf(PendingOpDto("update", "old", edited, base = edited), PendingOpDto("delete", "untouched")),
            listOf(PlaylistKeyAdoption.Rekey("old", "new")),
        )
        assertEquals(listOf("new", "untouched"), out.map { it.id })
        assertEquals("new", out[0].account?.id)
        assertEquals("new", out[0].base?.id)
    }
}
