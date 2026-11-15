package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiviewPickerPolicyTest {
    private val none = emptySet<String>()

    @Test
    fun `the picker looks at recent first`() {
        assertEquals(PickerGroup.RECENT, PickerOpeningPolicy.first)
    }

    @Test
    fun `it settles on the first channel that is not on screen`() {
        val step = PickerOpeningPolicy.step(PickerGroup.RECENT, listOf("a", "b", "c"), setOf("a"))
        assertEquals(PickerOpeningPolicy.Step.Settle(1), step)
    }

    @Test
    fun `recent with everything already on screen moves on to favorites then all channels`() {
        assertEquals(
            PickerOpeningPolicy.Step.TryNext(PickerGroup.FAVORITES),
            PickerOpeningPolicy.step(PickerGroup.RECENT, listOf("a"), setOf("a")),
        )
        assertEquals(
            "an empty recent list is skipped too",
            PickerOpeningPolicy.Step.TryNext(PickerGroup.FAVORITES),
            PickerOpeningPolicy.step(PickerGroup.RECENT, emptyList(), none),
        )
        assertEquals(
            PickerOpeningPolicy.Step.TryNext(PickerGroup.ALL),
            PickerOpeningPolicy.step(PickerGroup.FAVORITES, emptyList(), none),
        )
    }

    @Test
    fun `all channels is where it stops, on the first channel when all of them are on screen`() {
        assertEquals(PickerOpeningPolicy.Step.Settle(0), PickerOpeningPolicy.step(PickerGroup.ALL, listOf("a", "b"), setOf("a", "b")))
        assertEquals(PickerOpeningPolicy.Step.Settle(1), PickerOpeningPolicy.step(PickerGroup.ALL, listOf("a", "b"), setOf("a")))
        assertEquals("nothing to focus: stay on the group", PickerOpeningPolicy.Step.Settle(null), PickerOpeningPolicy.step(PickerGroup.ALL, emptyList(), none))
    }

    @Test
    fun `first off screen is null when every channel is on screen`() {
        assertNull(PickerOpeningPolicy.firstOffScreen(listOf("a"), setOf("a")))
        assertEquals(2, PickerOpeningPolicy.firstOffScreen(listOf("a", "b", "c"), setOf("a", "b")))
    }

    @Test
    fun `each empty case has its own text`() {
        val f = PickerEmptyPolicy::forColumn
        val search = PickerEmptyPolicy.Row.SEARCH
        val other = PickerEmptyPolicy.Row.OTHER
        assertEquals("search focused before typing", PickerEmpty.SEARCH_IDLE, f(search, PickerEmptyPolicy.Selected.OTHER, "", false))
        assertEquals("even with channels from the group before", PickerEmpty.SEARCH_IDLE, f(search, PickerEmptyPolicy.Selected.OTHER, "", true))
        assertEquals("a search with no hits", PickerEmpty.SEARCH_NONE, f(search, PickerEmptyPolicy.Selected.SEARCH, "zzz", false))
        assertEquals("favorites", PickerEmpty.FAVORITES, f(other, PickerEmptyPolicy.Selected.FAVORITES, "", false))
        assertEquals("recent", PickerEmpty.RECENT, f(other, PickerEmptyPolicy.Selected.RECENT, "", false))
        assertEquals("any other group", PickerEmpty.GROUP, f(other, PickerEmptyPolicy.Selected.OTHER, "", false))
        assertEquals("the playlist row", PickerEmpty.PLAYLIST_ROW, f(PickerEmptyPolicy.Row.PLAYLIST, PickerEmptyPolicy.Selected.OTHER, "", true))
        assertNull("a group with channels has no empty text", f(other, PickerEmptyPolicy.Selected.OTHER, "", true))
    }
}
