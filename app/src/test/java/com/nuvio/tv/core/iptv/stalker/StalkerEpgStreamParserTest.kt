package com.nuvio.tv.core.iptv.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Stalker programme's archive is addressed by the portal's own EPG row id, so the bulk guide
 * parser has to keep it (and the row's archive mark) next to the title and times.
 */
class StalkerEpgStreamParserTest {

    private val body = """{"js":{"data":{"12":[
        {"id":"9001","name":"News","descr":"Headlines","start_timestamp":"1000","stop_timestamp":"2000","mark_archive":1},
        {"id":"9002","name":"Film","descr":"","start_timestamp":"2000","stop_timestamp":"3000","mark_archive":0},
        {"name":"No id","start_timestamp":"3000","stop_timestamp":"4000"}
    ]}}}"""

    private fun parse(chunk: Int = body.length): List<StalkerEpgRow> {
        val rows = ArrayList<StalkerEpgRow>()
        val parser = StalkerEpgStreamParser { rows.add(it) }
        body.chunked(chunk).forEach(parser::feed)
        return rows
    }

    @Test
    fun `the portal's programme id and archive mark are kept`() {
        val rows = parse()

        assertEquals(3, rows.size)
        assertEquals("9001", rows[0].programmeId)
        assertTrue("mark_archive=1 is a kept recording", rows[0].hasArchive)
        assertEquals("9002", rows[1].programmeId)
        assertFalse("mark_archive=0 is not", rows[1].hasArchive)
        assertEquals(12, rows[0].channelId)
        assertEquals("News", rows[0].title)
        assertEquals(1_000_000L, rows[0].startMs)
    }

    @Test
    fun `a row without an id has none rather than a made-up one`() {
        assertNull(parse()[2].programmeId)
    }

    @Test
    fun `chunk boundaries anywhere give the same rows`() {
        assertEquals(parse(), parse(chunk = 7))
    }
}
