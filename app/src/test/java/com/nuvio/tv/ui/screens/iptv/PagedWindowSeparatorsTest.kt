package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.ChannelSeparatorPolicy

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B179 paging: hidden divider rows still occupy provider offsets. The next window must start at the RAW
 * rows consumed, never at the shown count — with the shown count, enough dividers made the next window
 * all duplicates and cut the category short.
 */
class PagedWindowSeparatorsTest {

    private val page = 5

    /** A provider category: rows in order; the fake serves windows exactly like the hub (PAGE+1 raw rows, show PAGE minus dividers). */
    private class Provider(val rows: List<String>, val page: Int) {
        val asked = mutableListOf<Int>()
        fun window(offset: Int): RawWindow<String> {
            asked += offset
            val raw = rows.drop(offset).take(page + 1)
            val taken = raw.take(page)
            val shown = ChannelSeparatorPolicy.withoutSeparators(taken, { it }, { null }, { false })
            return RawWindow(shown, raw.size > page, taken.size)
        }
    }

    private val divider = "################ Bulgaria Channels"

    private fun rows(vararg spec: Any): List<String> = spec.flatMap {
        when (it) { is Int -> List(it) { divider }; else -> listOf(it as String) }
    }

    private suspend fun loadAll(p: Provider, maxEmpty: Int = 20): List<String> {
        val first = p.window(0)
        var state = PagedState(first.items, first.hasMore, first.consumed)
        var guard = 0
        while (state.hasMore && guard++ < 100) {
            val before = state.items.size
            state = advancePaged(state, maxEmpty, { it }) { p.window(it) }
            if (state.items.size == before && state.hasMore) break
        }
        return state.items
    }

    @Test
    fun `every real row loads exactly once however many dividers are hidden`() = runTest {
        val real = (1..23).map { "Ch $it" }
        // a divider before every second real row: far more hidden rows than the old shown-count offset could absorb
        val all = real.flatMap { listOf(divider, it) }
        val p = Provider(all, page)
        assertEquals(real, loadAll(p))
    }

    @Test
    fun `a window that is entirely dividers does not end the category`() = runTest {
        val all = rows("A", "B", 12, "C", "D")   // the middle windows hold nothing but dividers
        val p = Provider(all, page)
        assertEquals(listOf("A", "B", "C", "D"), loadAll(p))
    }

    @Test
    fun `the next window starts at the raw rows consumed`() = runTest {
        val p = Provider(rows("A", divider, "B", divider, "C", "D", "E", "F", "G"), page)
        val first = p.window(0)
        assertEquals(listOf("A", "B", "C"), first.items)
        assertEquals(5, first.consumed)
        advancePaged(PagedState(first.items, first.hasMore, first.consumed), id = { it }) { p.window(it) }
        assertEquals("offset 5 (raw), not 3 (shown)", listOf(0, 5), p.asked)
    }

    @Test
    fun `a stuck source that consumes nothing stops instead of looping`() = runTest {
        var calls = 0
        val state = advancePaged(PagedState(listOf("A"), true, 1), maxEmptyWindows = 50, id = { it }) {
            calls++
            RawWindow(emptyList(), true, 0)
        }
        assertEquals(1, calls)
        assertTrue(!state.hasMore)
    }

    @Test
    fun `an endless run of empty windows is bounded`() = runTest {
        var calls = 0
        advancePaged(PagedState(listOf("A"), true, 1), maxEmptyWindows = 3, id = { it }) {
            calls++
            RawWindow(emptyList(), true, 5)
        }
        assertEquals(3, calls)
    }

    @Test
    fun `a failed fetch keeps what is loaded`() = runTest {
        val start = PagedState(listOf("A"), true, 1)
        assertEquals(start, advancePaged(start, id = { it }) { null })
    }
}
