package com.nuvio.tv.ui.screens.iptv

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B282: a provider page that lists the same stream twice put two items under one Compose key and
 * crashed the hub ("Key already used" / an item measured twice). The merge used to drop only ids
 * already loaded, so a repeat INSIDE the new window got through. Twin: NuvioMobile PagedWindowMergeTest.
 */
class PagedWindowDedupeTest {

    private data class Row(val id: String)

    @Test
    fun windowRepeatingAnIdInsideItselfAddsItOnce() = runTest {
        val start = PagedState(listOf(Row("a")), hasMore = true, rawOffset = 1)
        val next = advancePaged(start, id = { it.id }) {
            RawWindow(listOf(Row("b"), Row("c"), Row("b")), hasMore = false, consumed = 3)
        }
        assertEquals("a repeated id inside one window is added once", listOf("a", "b", "c"), next.items.map { it.id })
    }

    @Test
    fun mergedIdsStayUniqueAcrossWindows() = runTest {
        val windows = listOf(
            RawWindow(listOf(Row("a"), Row("a")), hasMore = true, consumed = 2),
            RawWindow(listOf(Row("b"), Row("a"), Row("b"), Row("c")), hasMore = false, consumed = 4),
        )
        var state = PagedState(emptyList<Row>(), hasMore = true, rawOffset = 0)
        var i = 0
        while (state.hasMore) state = advancePaged(state, id = { it.id }) { windows.getOrNull(i++) }
        val ids = state.items.map { it.id }
        assertEquals("ids are unique, so safe as LazyRow/Grid keys", ids.distinct(), ids)
        assertEquals("first appearance order is kept", listOf("a", "b", "c"), ids)
    }
}
