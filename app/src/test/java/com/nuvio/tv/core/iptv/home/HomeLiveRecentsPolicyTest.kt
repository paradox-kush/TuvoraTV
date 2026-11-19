package com.nuvio.tv.core.iptv.home

import com.nuvio.tv.core.iptv.content.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLiveRecentsPolicyTest {
    private fun c(id: String, name: String = "Channel $id") = HomeLiveRecentsPolicy.Candidate(id, name, null)

    private fun p(start: Long, end: Long, title: String) = EpgProgramme("x", start, end, title, null)

    @Test
    fun selectKeepsNewestFirstAndCaps() {
        val many = (1..30).map { c("id$it") }
        val picked = HomeLiveRecentsPolicy.select(many)
        assertEquals(HomeLiveRecentsPolicy.MAX_CHANNELS, picked.size)
        assertEquals("id1", picked.first().contentId)
    }

    @Test
    fun selectDropsNamelessAndRepeatedChannels() {
        val picked = HomeLiveRecentsPolicy.select(listOf(c("a"), c("b", name = " "), c("a", name = "Again"), c(""), c("d")))
        assertEquals(listOf("a", "d"), picked.map { it.contentId })
    }

    @Test
    fun nowTitleIsTheProgrammeCoveringNow() {
        val guide = listOf(p(0, 100, "Earlier"), p(100, 200, "Now"), p(200, 300, "Later"))
        assertEquals("Now", HomeLiveRecentsPolicy.nowTitle(guide, 150))
        assertEquals("a programme starting exactly now is on", "Later", HomeLiveRecentsPolicy.nowTitle(guide, 200))
    }

    @Test
    fun nowTitleIsNullWhenTheGuideDoesNotCoverNow() {
        assertNull("a stale guide shows no title", HomeLiveRecentsPolicy.nowTitle(listOf(p(0, 100, "Old")), 500))
        assertNull(HomeLiveRecentsPolicy.nowTitle(emptyList(), 500))
        assertNull("a blank title is not a title", HomeLiveRecentsPolicy.nowTitle(listOf(p(0, 100, "  ")), 50))
        assertNull("an inverted range is ignored", HomeLiveRecentsPolicy.nowTitle(listOf(p(100, 0, "Bad")), 50))
    }
}
