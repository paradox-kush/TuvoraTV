package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Playlist-edit id migration invariant: rewriting an account's content-id prefix must
 * survive the colon-riddled accountId ("http://host:port|user") and keep kind + streamId.
 */
class XtreamIdMigrationTest {

    @Test
    fun `account prefix rewrite preserves kind and stream id across colon-heavy account ids`() {
        val oldAcc = "http://old.example.com:8080|user1"
        val newAcc = "http://new.example.net:2095|user1"
        val oldPrefix = XtreamItemRegistry.accountPrefix(oldAcc)
        val newPrefix = XtreamItemRegistry.accountPrefix(newAcc)

        listOf(
            XtreamItemRegistry.vodId(oldAcc, 42) to "vod",
            XtreamItemRegistry.seriesId(oldAcc, 42) to "series",
            XtreamItemRegistry.liveId(oldAcc, 42) to "live",
        ).forEach { (id, kind) ->
            val moved = newPrefix + id.removePrefix(oldPrefix)
            val parsed = XtreamItemRegistry.parseId(moved)!!
            assertEquals(newAcc, parsed.accountId)
            assertEquals(kind, parsed.kind)
            assertEquals("42", parsed.streamId)
        }
    }

    @Test
    fun `prefix matching cannot cross accounts sharing a username or host`() {
        val acc = "http://host.example.com:8080|user1"
        val lookalike = "http://host.example.com:8080|user12" // same host, longer username
        val id = XtreamItemRegistry.vodId(lookalike, 7)
        assertFalse(id.startsWith(XtreamItemRegistry.accountPrefix(acc)))
    }
}
