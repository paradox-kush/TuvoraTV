package com.nuvio.tv.core.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit (TV) — note assertEquals(message, expected, actual), the opposite order from kotlin.test. */
class RecEventQueueRestorePolicyTest {
    private fun select(raw: String?, max: Int = 500) =
        RecEventQueueRestorePolicy.select(raw, "\n", max)

    @Test
    fun `null or blank yields nothing and is not oversized`() {
        assertEquals(emptyList<String>(), select(null).lines)
        assertFalse(select(null).oversized)
        assertEquals(emptyList<String>(), select("   ").lines)
    }

    @Test
    fun `a healthy queue is returned in on-disk order`() {
        val out = select(listOf("a", "b", "c").joinToString("\n"))
        assertEquals(listOf("a", "b", "c"), out.lines)
        assertFalse(out.oversized)
    }

    @Test
    fun `only the newest maxRecords survive preserving order`() {
        val raw = (1..10).joinToString("\n") { "r$it" }
        assertEquals("keeps newest 3 in on-disk order", listOf("r8", "r9", "r10"), select(raw, max = 3).lines)
    }

    @Test
    fun `an over-length record is dropped but valid records survive`() {
        val huge = "x".repeat(RecEventQueueRestorePolicy.MAX_RECORD_CHARS + 1)
        val out = select("ok1\n$huge\nok2")
        assertEquals("pathological line skipped", listOf("ok1", "ok2"), out.lines)
        assertFalse(out.oversized)
    }

    @Test
    fun `an oversized blob is rejected wholesale`() {
        val out = select("y".repeat(RecEventQueueRestorePolicy.MAX_QUEUE_CHARS + 1))
        assertTrue("over the total cap", out.oversized)
        assertEquals(emptyList<String>(), out.lines)
    }

    @Test
    fun `boundLines keeps newest N within the total-char budget`() {
        val lines = (1..10).map { "aaaa" } // 4 chars + 1 sep = 5; budget 12 fits 2, not 3
        val out = RecEventQueueRestorePolicy.boundLines(lines, 500, 12)
        assertEquals("total-char budget bounds the set even under the record cap", 2, out.size)
    }

    @Test
    fun `boundLines drops blank and over-length lines`() {
        val huge = "x".repeat(RecEventQueueRestorePolicy.MAX_RECORD_CHARS + 1)
        val out = RecEventQueueRestorePolicy.boundLines(listOf("a", "", huge, "b"), 500)
        assertEquals(listOf("a", "b"), out)
    }

    @Test
    fun `boundLines caps to the newest maxRecords in order`() {
        val out = RecEventQueueRestorePolicy.boundLines((1..10).map { "r$it" }, 3)
        assertEquals(listOf("r8", "r9", "r10"), out)
    }
}
