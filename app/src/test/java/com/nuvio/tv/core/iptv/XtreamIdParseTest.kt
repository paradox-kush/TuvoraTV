package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [XtreamItemRegistry.parseId] invariants. Two id segments legitimately contain ':' — the
 * accountId ("http://host:port|user") and a Stalker episode streamId ("seriesId:season:epNum") —
 * so the parser anchors on the rightmost known ":kind:" marker.
 *
 * Regression: the old "last two ':' segments" parse misread every Stalker episode id
 * ("...:episode:12:3:4" → kind "3"), which killed play-time resolve for direct-catalog stalker
 * episodes, the expired-link refresh for them, DoH provider lookup, and the Continue Watching
 * account-orphan filter (misparsed accountId never matched a configured account).
 */
class XtreamIdParseTest {

    private val colonAcc = "http://host.example.com:8080|user1"

    @Test
    fun `vod, series and live parse with colon-heavy account ids`() {
        listOf(
            XtreamItemRegistry.vodId(colonAcc, 42) to "vod",
            XtreamItemRegistry.seriesId(colonAcc, 42) to "series",
            XtreamItemRegistry.liveId(colonAcc, 42) to "live",
        ).forEach { (id, kind) ->
            val parsed = XtreamItemRegistry.parseId(id)!!
            assertEquals(colonAcc, parsed.accountId)
            assertEquals(kind, parsed.kind)
            assertEquals("42", parsed.streamId)
        }
    }

    @Test
    fun `stalker three-part episode id keeps seriesId season epNum in streamId`() {
        val id = XtreamItemRegistry.episodeId(colonAcc, "12:3:4")
        val parsed = XtreamItemRegistry.parseId(id)!!
        assertEquals(colonAcc, parsed.accountId)
        assertEquals("episode", parsed.kind)
        assertEquals("12:3:4", parsed.streamId)
    }

    @Test
    fun `legacy two-part episode id parses`() {
        val parsed = XtreamItemRegistry.parseId(XtreamItemRegistry.episodeId(colonAcc, "12:4"))!!
        assertEquals(colonAcc, parsed.accountId)
        assertEquals("episode", parsed.kind)
        assertEquals("12:4", parsed.streamId)
    }

    @Test
    fun `plain xtream numeric episode id parses`() {
        val parsed = XtreamItemRegistry.parseId(XtreamItemRegistry.episodeId("acc", "987"))!!
        assertEquals("acc", parsed.accountId)
        assertEquals("episode", parsed.kind)
        assertEquals("987", parsed.streamId)
    }

    @Test
    fun `malformed ids return null`() {
        assertNull(XtreamItemRegistry.parseId("tt0111161"))                  // not an xtream id
        assertNull(XtreamItemRegistry.parseId("xtream:acc:banana:42"))       // unknown kind
        assertNull(XtreamItemRegistry.parseId("xtream::vod:42"))             // empty account
        assertNull(XtreamItemRegistry.parseId("xtream:acc:vod:"))            // empty stream id
    }
}
