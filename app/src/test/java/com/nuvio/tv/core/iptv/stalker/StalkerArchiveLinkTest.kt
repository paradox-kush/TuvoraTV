package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.CatchUpFailure
import com.nuvio.tv.core.iptv.CatchUpFailure.StalkerLinkError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Stalker archive replay is a `create_link` of type `tv_archive` whose cmd names the portal's own
 * EPG programme id (Ministra c/tv_archive.js: `'auto /media/' + id + '.mpg'`), never a time range.
 */
class StalkerArchiveLinkTest {

    @Test
    fun `the archive cmd is the programme id on the auto media path`() {
        assertEquals("auto /media/123456.mpg", StalkerArchiveLink.cmd("123456"))
    }

    @Test
    fun `the request is a tv_archive create_link carrying that cmd`() {
        assertEquals(
            mapOf(
                "type" to "tv_archive",
                "action" to "create_link",
                "cmd" to "auto /media/77.mpg",
                "forced_storage" to "undefined",
                "disable_ad" to "0",
                "download" to "0",
            ),
            StalkerArchiveLink.params("77"),
        )
    }

    @Test
    fun `a live create_link keeps its shape`() {
        assertEquals(
            mapOf(
                "type" to "itv",
                "action" to "create_link",
                "cmd" to "ffrt http://h/ch/1",
                "forced_storage" to "undefined",
                "disable_ad" to "0",
            ),
            StalkerArchiveLink.createLinkParams("itv", "ffrt http://h/ch/1", emptyMap()),
        )
    }

    @Test
    fun `the portal's three answers are told apart`() {
        fun of(raw: String) = CatchUpFailure.stalkerLinkErrorOf(raw)
        assertEquals(StalkerLinkError.SESSION_LIMIT, of("""{"js":{"error":"limit"}}"""))
        assertEquals(StalkerLinkError.NOTHING_TO_PLAY, of("""{"js":{"error":"nothing_to_play"}}"""))
        assertEquals(StalkerLinkError.LINK_FAULT, of("""{"js":{"cmd":"link_fault"}}"""))
        assertEquals(StalkerLinkError.NONE, of("""{"js":{"cmd":"http://h/archive.ts"}}"""))
        assertEquals(
            "a recording named like an error is not an error",
            StalkerLinkError.NONE,
            of("""{"js":{"cmd":"http://h/nothing_to_play.ts"}}"""),
        )
    }

    @Test
    fun `each failure reads as its own viewer message`() {
        val limit = StalkerArchiveLink.messageFor(StalkerLinkError.SESSION_LIMIT)
        val nothing = StalkerArchiveLink.messageFor(StalkerLinkError.NOTHING_TO_PLAY)
        val fault = StalkerArchiveLink.messageFor(StalkerLinkError.LINK_FAULT)
        assertEquals("This portal is already streaming on another device — close it and try again", limit)
        assertEquals("The portal has no recording of this programme", nothing)
        assertEquals("The portal couldn't build a playback link for this programme", fault)
        assertEquals(null, StalkerArchiveLink.messageFor(StalkerLinkError.NONE))
    }
}
