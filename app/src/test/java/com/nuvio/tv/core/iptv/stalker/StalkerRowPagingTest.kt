package com.nuvio.tv.core.iptv.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B294: the pure paging maths for a hub row that reads one Stalker portal page per window. */
class StalkerRowPagingTest {

    @Test
    fun `offset zero is page one and each full page advances by one`() {
        assertEquals("offset 0", 1, StalkerRowPaging.pageFor(0, 14))
        assertEquals("offset 14", 2, StalkerRowPaging.pageFor(14, 14))
        assertEquals("offset 28", 3, StalkerRowPaging.pageFor(28, 14))
        assertEquals("mid page rounds down", 2, StalkerRowPaging.pageFor(20, 14))
    }

    @Test
    fun `a non positive page size or offset is page one`() {
        assertEquals("size 0", 1, StalkerRowPaging.pageFor(28, 0))
        assertEquals("size -3", 1, StalkerRowPaging.pageFor(28, -3))
        assertEquals("negative offset", 1, StalkerRowPaging.pageFor(-5, 14))
    }

    @Test
    fun `hasMore is false on an empty page`() {
        assertFalse("with total", StalkerRowPaging.hasMore(1, 14, 0, 100))
        assertFalse("without total", StalkerRowPaging.hasMore(1, 14, 0, null))
    }

    @Test
    fun `hasMore follows the total when the portal gives one`() {
        assertTrue("1 of 100", StalkerRowPaging.hasMore(1, 14, 14, 100))
        assertTrue("7 of 100", StalkerRowPaging.hasMore(7, 14, 14, 100))
        assertFalse("8 covers 112 >= 100", StalkerRowPaging.hasMore(8, 14, 14, 100))
        assertFalse("exact fit", StalkerRowPaging.hasMore(2, 14, 14, 28))
    }

    @Test
    fun `without a total a full page means more and a short one means done`() {
        assertTrue("full", StalkerRowPaging.hasMore(1, 14, 14, null))
        assertFalse("short", StalkerRowPaging.hasMore(1, 14, 9, null))
    }

    @Test
    fun `page size prefers max_page_items then the first page then 14`() {
        assertEquals("max_page_items", 10, StalkerRowPaging.pageSizeOf(10, 14))
        assertEquals("zero max falls to rows", 12, StalkerRowPaging.pageSizeOf(0, 12))
        assertEquals("null max falls to rows", 12, StalkerRowPaging.pageSizeOf(null, 12))
        assertEquals("nothing known", 14, StalkerRowPaging.pageSizeOf(null, 0))
        assertEquals("negative max, no rows", 14, StalkerRowPaging.pageSizeOf(-1, 0))
    }
}
