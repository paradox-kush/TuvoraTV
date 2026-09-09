package com.nuvio.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class EpgFetchBoundsTest {
    @Test
    fun `reads content within the cap`() {
        assertEquals("hello world", readBoundedChars(StringReader("hello world"), 1024))
    }

    @Test
    fun `content exactly at the cap is kept`() {
        assertEquals(1000, readBoundedChars(StringReader("x".repeat(1000)), 1000)?.length)
    }

    @Test
    fun `rejects content over the cap`() {
        assertNull("over the cap so reject and keep prior generation", readBoundedChars(StringReader("y".repeat(2000)), 1000))
    }

    @Test
    fun `empty reader yields empty string`() {
        assertEquals("", readBoundedChars(StringReader(""), 16))
    }
}
