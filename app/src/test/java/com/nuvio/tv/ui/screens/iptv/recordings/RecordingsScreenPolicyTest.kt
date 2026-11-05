package com.nuvio.tv.ui.screens.iptv.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingsScreenPolicyTest {
    private val rows = listOf("s:a", "s:b", "r:c", "r:d")

    @Test
    fun `entry lands on the first row and on nothing for an empty page`() {
        assertEquals("s:a", RecordingsFocusPolicy.entry(rows))
        assertNull(RecordingsFocusPolicy.entry(emptyList()))
    }

    @Test
    fun `restore keeps the remembered row while it exists and falls back to the first`() {
        assertEquals("r:c", RecordingsFocusPolicy.restore("r:c", rows))
        assertEquals("s:a", RecordingsFocusPolicy.restore("r:gone", rows))
        assertEquals("s:a", RecordingsFocusPolicy.restore(null, rows))
    }

    @Test
    fun `removing a middle row focuses the row that followed it`() {
        assertEquals("r:c", RecordingsFocusPolicy.afterRemoval(rows, "s:b", rows - "s:b"))
    }

    @Test
    fun `removing the last row focuses the one before it`() {
        assertEquals("r:c", RecordingsFocusPolicy.afterRemoval(rows, "r:d", rows - "r:d"))
    }

    @Test
    fun `removing the only row leaves nothing to focus`() {
        assertNull(RecordingsFocusPolicy.afterRemoval(listOf("r:a"), "r:a", emptyList()))
    }

    @Test
    fun `the next row is skipped when it left the page too`() {
        assertEquals("r:d", RecordingsFocusPolicy.afterRemoval(rows, "s:b", listOf("s:a", "r:d")))
    }

    @Test
    fun `a retry that turns a failed row into a scheduled one still lands near where it was`() {
        val before = listOf("s:a", "r:failed", "r:old")
        val after = listOf("s:a", "s:retry", "r:old")
        assertEquals("r:old", RecordingsFocusPolicy.afterRemoval(before, "r:failed", after))
    }

    @Test
    fun `an id that was never on the page falls back to the first row after`() {
        assertEquals("s:a", RecordingsFocusPolicy.afterRemoval(rows, "zzz", rows))
    }

    @Test
    fun `sizes read in the unit a person would say`() {
        assertEquals("", RecordingRowFormat.size(0))
        assertEquals("<1 MB", RecordingRowFormat.size(5_000))
        assertEquals("5 MB", RecordingRowFormat.size(5L * 1024 * 1024))
        assertEquals("1023 MB", RecordingRowFormat.size(1023L * 1024 * 1024))
        assertEquals("1.5 GB", RecordingRowFormat.size(1536L * 1024 * 1024))
    }
}
