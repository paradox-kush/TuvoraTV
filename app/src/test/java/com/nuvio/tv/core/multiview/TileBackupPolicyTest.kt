package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileBackupPolicyTest {
    private data class Ch(override val key: String) : MultiviewChannel

    /** A supplier that hands out [list] in order, skipping keys it is told were tried. */
    private fun supplier(list: List<Ch>, calls: IntArray = IntArray(1)): (Set<String>) -> Ch? = { tried ->
        calls[0]++
        list.firstOrNull { it.key !in tried }
    }

    @Test
    fun `picks the first untried allowed backup`() {
        val p = TileBackupPolicy()
        val pick = p.next(Ch("a"), supplier(listOf(Ch("a"), Ch("b"), Ch("c"))), { true })
        assertEquals(Ch("b"), pick)
    }

    @Test
    fun `skips candidates that may not play and remembers them as tried`() {
        val result = TileBackupPolicy.choose("a", emptySet(), supplier(listOf(Ch("b"), Ch("c"))), { it.key != "b" })
        assertEquals(Ch("c"), result.backup)
        assertTrue("b is tried", "b" in result.tried)
        assertTrue("a is tried", "a" in result.tried)
    }

    @Test
    fun `a chain never repeats a channel`() {
        val p = TileBackupPolicy()
        val all = listOf(Ch("a"), Ch("b"), Ch("c"))
        val first = p.next(Ch("a"), supplier(all), { true })!!
        assertEquals("b", first.key)
        val second = p.next(first, supplier(all), { true })!!
        assertEquals("c", second.key)
        assertNull("a, b and c are all tried", p.next(second, supplier(all), { true }))
    }

    @Test
    fun `gives up after 8 candidates`() {
        val calls = IntArray(1)
        val endless: (Set<String>) -> Ch? = { calls[0]++; Ch("n${calls[0]}") }
        val result = TileBackupPolicy.choose("a", emptySet(), endless, { false })
        assertNull(result.backup)
        assertEquals("supplier asked exactly 8 times", 8, calls[0])
        assertEquals("8 rejected plus the failed one", 9, result.tried.size)
    }

    @Test
    fun `a supplier that keeps returning a tried channel is cut off at 8`() {
        val calls = IntArray(1)
        val stuck: (Set<String>) -> Ch? = { calls[0]++; Ch("a") }
        assertNull(TileBackupPolicy.choose("a", emptySet(), stuck, { true }).backup)
        assertEquals(8, calls[0])
    }

    @Test
    fun `no candidate or a throwing supplier gives null`() {
        assertNull(TileBackupPolicy.choose<Ch>("a", emptySet(), { null }, { true }).backup)
        assertNull(TileBackupPolicy.choose<Ch>("a", emptySet(), { error("boom") }, { true }).backup)
    }

    @Test
    fun `a throwing permission check counts as not allowed`() {
        val result = TileBackupPolicy.choose("a", emptySet(), supplier(listOf(Ch("b"), Ch("c"))), { if (it.key == "b") error("x") else true })
        assertEquals(Ch("c"), result.backup)
    }

    @Test
    fun `the supplier is given the tried keys`() {
        var seen: Set<String> = emptySet()
        TileBackupPolicy.choose<Ch>("a", setOf("z"), { seen = it; null }, { true })
        assertEquals(setOf("a", "z"), seen)
    }

    @Test
    fun `clear forgets chains`() {
        val p = TileBackupPolicy()
        val all = listOf(Ch("a"), Ch("b"))
        val b = p.next(Ch("a"), supplier(all), { true })!!
        assertNull(p.next(b, supplier(all), { true }))
        p.clear()
        assertEquals(Ch("a"), p.next(b, supplier(all), { true }))
    }
}
