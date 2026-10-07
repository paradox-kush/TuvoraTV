package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds.Kind
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerIdsTest {
    private val m = "6f3c1a9e2b7d4c58a1e0f9d8c7b6a543"
    private val u = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"
    private val entry = MediaServerEntry("jellyfin|$m|$u", MediaServerType.JELLYFIN, m, u, "Home")

    @Test
    fun contentIdsRoundTrip() {
        val id = MediaServerIds.contentId(entry, Kind.MOVIE, "abc123")
        assertEquals("ms:jellyfin:$m:$u:movie:abc123", id)
        val parsed = MediaServerIds.parse(id)!!
        assertEquals(MediaServerType.JELLYFIN, parsed.type)
        assertEquals(m, parsed.machineId)
        assertEquals(u, parsed.userId)
        assertEquals(Kind.MOVIE, parsed.kind)
        assertEquals("abc123", parsed.itemId)
        assertEquals("jellyfin:$m:$u", parsed.serverKey)
        assertEquals("jellyfin:$m", parsed.sourceKey)
    }

    @Test
    fun everyKindAndBothTypesParse() {
        for (kind in Kind.entries) for (type in MediaServerType.entries) {
            val e = entry.copy(type = type, key = "${type.wire}|$m|$u")
            assertEquals(kind, MediaServerIds.parse(MediaServerIds.contentId(e, kind, "7"))!!.kind)
        }
    }

    @Test
    fun malformedIdsDoNotParse() {
        assertNull(MediaServerIds.parse("xtream:http://a|b:vod:1"))
        assertNull("no item id", MediaServerIds.parse("ms:jellyfin:$m:$u:movie"))
        assertNull("Plex is parked", MediaServerIds.parse("ms:plex:$m:$u:movie:1"))
        assertNull("unknown kind", MediaServerIds.parse("ms:jellyfin:$m:$u:channel:1"))
        assertNull(MediaServerIds.parse("ms:jellyfin::$u:movie:1"))
        assertNull(MediaServerIds.parse("tt0111161"))
        assertNull(MediaServerIds.parse("ms:jellyfin:a:b"))
    }

    @Test
    fun ownershipPredicatesAreDisjointFromIptv() {
        assertTrue(MediaServerIds.isOwnContentId("ms:jellyfin:$m:$u:movie:1"))
        assertFalse(MediaServerIds.isOwnContentId("xtream:http://a|b:vod:1"))
        assertFalse(MediaServerIds.isOwnContentId("tmdb:603"))
        assertTrue(MediaServerIds.isOwnProviderId("ms"))
        assertTrue(MediaServerIds.isOwnProviderId("ms-match:jellyfin:$m:$u"))
        assertFalse(MediaServerIds.isOwnProviderId("xtream"))
        assertFalse(MediaServerIds.isOwnProviderId("xtream-match:http://a|b"))
        assertFalse(MediaServerIds.isOwnProviderId("addon:example"))
        assertFalse("only the exact direct id or the match prefix", MediaServerIds.isOwnProviderId("msx"))
    }

    @Test
    fun deferredUrlsRoundTripAndNeverCarryAToken() {
        val url = MediaServerIds.deferredUrl("jellyfin:$m:$u", "item1", "src9")
        assertEquals("ms-deferred:jellyfin:$m:$u|item1|src9", url)
        assertTrue(MediaServerIds.isDeferredUrl(url))
        val d = MediaServerIds.parseDeferred(url)!!
        assertEquals("jellyfin:$m:$u", d.serverKey)
        assertEquals("item1", d.itemId)
        assertEquals("src9", d.mediaSourceId)
        assertNull(MediaServerIds.parseDeferred(MediaServerIds.deferredUrl("jellyfin:$m:$u", "item1", null))!!.mediaSourceId)
        assertFalse(MediaServerIds.isDeferredUrl("https://nas/Videos/1/stream"))
        assertFalse(MediaServerIds.isDeferredUrl(null))
        assertNull(MediaServerIds.parseDeferred("ms-deferred:onlyonepart"))
    }

    @Test
    fun matchGroupIdsRoundTrip() {
        val g = MediaServerIds.matchGroupId("emby:$m:$u")
        assertEquals("ms-match:emby:$m:$u", g)
        assertEquals("emby:$m:$u", MediaServerIds.serverKeyOfMatchGroup(g))
        assertNull(MediaServerIds.serverKeyOfMatchGroup("ms"))
        assertNull(MediaServerIds.serverKeyOfMatchGroup("xtream-match:x"))
    }

    @Test
    fun serverKeyParsing() {
        assertEquals(Triple(MediaServerType.EMBY, m, u), MediaServerIds.parseServerKey("emby:$m:$u"))
        assertNull(MediaServerIds.parseServerKey("emby:$m"))
        assertNull(MediaServerIds.parseServerKey("plex:$m:$u"))
    }

    @Test
    fun theTelemetryIdNeverCarriesTheMachineOrUserId() {
        val parsed = MediaServerIds.parse("ms:jellyfin:$m:$u:episode:e77")!!
        val t = MediaServerIds.telemetryId(parsed, installSalt = "salt-1")
        assertFalse(t.contains(m))
        assertFalse(t.contains(u))
        assertTrue(t, t.startsWith("ms:jellyfin:") && t.endsWith(":episode:e77"))
        assertEquals("stable", t, MediaServerIds.telemetryId(parsed, "salt-1"))
        assertNotEquals("salted per install", t, MediaServerIds.telemetryId(parsed, "salt-2"))
        assertEquals(16, t.split(':')[2].length)
    }

    @Test
    fun hashMatchesTheKnownFnv1aVector() {
        // FNV-1a 64 of "a" is af63dc4c8601ec8c
        assertEquals("af63dc4c8601ec8c", MediaServerIds.hash("a"))
        assertEquals("cbf29ce484222325", MediaServerIds.hash(""))
    }
}
