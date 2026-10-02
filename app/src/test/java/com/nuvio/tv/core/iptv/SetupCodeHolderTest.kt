package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 contract section 1: a code lives in memory only, and 30 minutes at most. */
class SetupCodeHolderTest {
    private var now = 1_000L
    private val holder = SetupCodeHolder { now }

    @Test
    fun `holds the normalized code until cleared`() {
        assertTrue(holder.set("tuv-abcd-efgh-jkmn"))
        assertEquals("ABCDEFGHJKMN", holder.get())
        holder.clear()
        assertNull(holder.get())
    }

    @Test
    fun `a code expires after 30 minutes`() {
        holder.set("ABCDEFGHJKMN")
        now += SetupCodeHolder.TTL_MS - 1
        assertEquals("ABCDEFGHJKMN", holder.get())
        now += 1
        assertNull(holder.get())
        assertNull("and stays gone", holder.get())
    }

    @Test
    fun `a malformed code is never kept and clears what was there`() {
        holder.set("ABCDEFGHJKMN")
        assertFalse(holder.set("nope"))
        assertNull(holder.get())
    }
}
