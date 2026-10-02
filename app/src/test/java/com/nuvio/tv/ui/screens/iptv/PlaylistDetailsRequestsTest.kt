package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2: "open this playlist's details when IPTV settings is next shown" — one-shot, in memory, goes stale. */
class PlaylistDetailsRequestsTest {

    @Test
    fun `a request is one-shot`() {
        val r = PlaylistDetailsRequests()
        assertNull(r.pending.value)
        r.open("k1", "Acme", nowMs = 1_000)
        assertEquals("k1", r.pending.value?.playlistKey)
        assertEquals("Acme", r.pending.value?.addedBy)
        r.consume()
        assertNull(r.pending.value)
    }

    @Test
    fun `a request that never finds its playlist goes stale after two minutes`() {
        val r = PlaylistDetailsRequests()
        r.open("k1", null, nowMs = 1_000)
        val req = r.pending.value!!
        assertFalse(req.isStale(1_000 + PlaylistDetailsRequests.MAX_AGE_MS))
        assertTrue(req.isStale(1_001 + PlaylistDetailsRequests.MAX_AGE_MS))
    }

    @Test
    fun `the host line never shows a playlist url's credentials`() {
        fun acc(base: String) = XtreamAccount(id = "k", name = "n", baseUrl = base, username = "u", password = "p")
        assertEquals("panel.example.com", hostLine(acc("http://panel.example.com:8080")))
        assertEquals("host.example.com", hostLine(acc("http://host.example.com/get.php?username=u&password=secret&type=m3u")))
        assertEquals("a.m3u", hostLine(acc("").copy(sourceType = XtreamAccount.SOURCE_FILE, fileName = "a.m3u")))
        assertEquals("portal.example.com", hostLine(acc("").copy(sourceType = XtreamAccount.SOURCE_STALKER, portalUrl = "http://portal.example.com/c")))
    }

    @Test
    fun `a stale request is dropped even when its playlist finally arrives`() {
        val req = PlaylistDetailsRequests.Request("k1", "Acme", createdAtMs = 0)
        assertEquals(PlaylistDetailsRequests.Decision.OPEN, PlaylistDetailsRequests.decide(req, hasPlaylist = true, nowMs = 1_000))
        assertEquals(PlaylistDetailsRequests.Decision.WAIT, PlaylistDetailsRequests.decide(req, hasPlaylist = false, nowMs = 1_000))
        val late = PlaylistDetailsRequests.MAX_AGE_MS + 1
        assertEquals("a playlist that arrives minutes later must not open a page unprompted",
            PlaylistDetailsRequests.Decision.DROP, PlaylistDetailsRequests.decide(req, hasPlaylist = true, nowMs = late))
        assertEquals(PlaylistDetailsRequests.Decision.DROP, PlaylistDetailsRequests.decide(req, hasPlaylist = false, nowMs = late))
    }
}
