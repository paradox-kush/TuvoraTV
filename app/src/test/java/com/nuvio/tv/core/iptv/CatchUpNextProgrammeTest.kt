package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.CatchUpNextProgramme.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatchUpNextProgrammeTest {
    private val m = 60_000L

    @Test
    fun `in a gap the earliest programme starting after the playhead wins whatever the list order`() {
        val later = Row("later", 200 * m, 260 * m)
        val sooner = Row("sooner", 130 * m, 190 * m)
        assertEquals(sooner, CatchUpNextProgramme.pick(listOf(later, sooner), atMs = 100 * m))
    }

    @Test
    fun `a programme covering the playhead beats a later one`() {
        val now = Row("now", 90 * m, 150 * m)
        assertEquals(now, CatchUpNextProgramme.pick(listOf(Row("next", 150 * m, 200 * m), now), atMs = 100 * m))
    }

    @Test
    fun `nothing usable is null`() {
        assertNull(CatchUpNextProgramme.pick(listOf(Row("bad", 0, 0), Row("past", 10 * m, 20 * m)), atMs = 100 * m))
    }
}
