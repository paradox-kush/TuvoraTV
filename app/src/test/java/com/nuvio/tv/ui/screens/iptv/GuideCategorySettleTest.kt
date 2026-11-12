package com.nuvio.tv.ui.screens.iptv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Scrolling the Live TV category list used to switch the guide on EVERY focus move: twenty
 * categories passed meant twenty cancelled channel loads and forty guide-pane rebuilds, measured on
 * the Onn box as a 37 ms median / 105 ms p90 frame against 19 ms for the channel list next to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideCategorySettleTest {

    @Test
    fun `policy - a different category waits for focus to settle`() {
        assertEquals(
            GuideCategorySettlePolicy.SETTLE_MS,
            GuideCategorySettlePolicy.delayMs(focusedId = "b", selectedId = "a"),
        )
    }

    @Test
    fun `policy - the category already shown is never delayed`() {
        assertEquals(0L, GuideCategorySettlePolicy.delayMs(focusedId = "a", selectedId = "a"))
    }

    @Test
    fun `policy - the settle wait outlasts a held key repeat`() {
        // A held D-pad key is gated to one move per VERTICAL_GATE_MS; a shorter wait would still
        // switch the guide on every repeat.
        assertEquals(true, GuideCategorySettlePolicy.SETTLE_MS > GuideKeyRepeatPolicy.VERTICAL_GATE_MS)
    }

    @Test
    fun `passing twenty categories switches the guide once to the last one`() = runTest {
        val selected = mutableListOf<String?>()
        val settler = GuideCategorySettler(this) { selected += it }
        repeat(20) { i ->
            settler.focus("cat$i", selectedId = "all")
            advanceTimeBy(GuideKeyRepeatPolicy.VERTICAL_GATE_MS)
        }
        // Old behaviour: twenty selections, one per focus move.
        assertEquals(emptyList<String?>(), selected)
        advanceTimeBy(GuideCategorySettlePolicy.SETTLE_MS)
        assertEquals(listOf<String?>("cat19"), selected)
    }

    @Test
    fun `coming back to the shown category drops the pending switch`() = runTest {
        val selected = mutableListOf<String?>()
        val settler = GuideCategorySettler(this) { selected += it }
        settler.focus("b", selectedId = "a")
        settler.focus("a", selectedId = "a")
        advanceTimeBy(GuideCategorySettlePolicy.SETTLE_MS * 2)
        // "a" is re-asked at once (the view model ignores it when its channels are showing, and
        // reloads it when they failed); "b" never loads.
        assertEquals(listOf<String?>("a"), selected)
    }

    @Test
    fun `entering the category commits the pending switch at once and only once`() = runTest {
        val selected = mutableListOf<String?>()
        val settler = GuideCategorySettler(this) { selected += it }
        settler.focus("b", selectedId = "a")
        settler.commitNow()
        assertEquals(listOf<String?>("b"), selected)
        advanceTimeBy(GuideCategorySettlePolicy.SETTLE_MS * 2)
        assertEquals(listOf<String?>("b"), selected)
    }

    @Test
    fun `commit with nothing pending does nothing`() = runTest {
        val selected = mutableListOf<String?>()
        val settler = GuideCategorySettler(this) { selected += it }
        settler.commitNow()
        settler.focus("b", selectedId = "a")
        settler.cancel()
        settler.commitNow()
        advanceTimeBy(GuideCategorySettlePolicy.SETTLE_MS * 2)
        assertEquals(emptyList<String?>(), selected)
    }

    // B254: leaving the category column (UP through Search to the tabs) used to leave the settle
    // job running, so Search was selected 200 ms after the viewer had already gone.
    @Test
    fun `cancel when focus leaves the column drops the pending switch`() = runTest {
        val selected = mutableListOf<String?>()
        val settler = GuideCategorySettler(this) { selected += it }
        settler.focus("b", selectedId = "a")
        settler.cancel()
        advanceTimeBy(GuideCategorySettlePolicy.SETTLE_MS * 2)
        assertEquals(emptyList<String?>(), selected)
    }

    @Test
    fun `a commit before the focus-loss cancel still selects`() = runTest {
        // RIGHT commits in the key handler, before focus crosses into the channel list and the
        // column reports focus lost; the cancel that follows must find nothing left to drop.
        val selected = mutableListOf<String?>()
        val settler = GuideCategorySettler(this) { selected += it }
        settler.focus("b", selectedId = "a")
        settler.commitNow()
        settler.cancel()
        advanceTimeBy(GuideCategorySettlePolicy.SETTLE_MS * 2)
        assertEquals(listOf<String?>("b"), selected)
    }

    @Test
    fun `policy - Search with a blank query never selects on focus`() {
        val search = XtreamLiveGuideViewModel.SEARCH_ID
        assertEquals("empty", false, GuideCategorySettlePolicy.selectsOnFocus(search, ""))
        assertEquals("spaces", false, GuideCategorySettlePolicy.selectsOnFocus(search, "  "))
    }

    @Test
    fun `policy - Search with a query and every other category select on focus`() {
        val search = XtreamLiveGuideViewModel.SEARCH_ID
        assertEquals("search with query", true, GuideCategorySettlePolicy.selectsOnFocus(search, "bbc"))
        assertEquals("other category", true, GuideCategorySettlePolicy.selectsOnFocus("cat1", ""))
    }

    @Test
    fun `focusing blank Search leaves the shown category and drops any pending switch`() = runTest {
        val selected = mutableListOf<String?>()
        val settler = GuideCategorySettler(this) { selected += it }
        settler.focus("b", selectedId = "a")
        settler.focus(XtreamLiveGuideViewModel.SEARCH_ID, selectedId = "a", searchQuery = "")
        advanceTimeBy(GuideCategorySettlePolicy.SETTLE_MS * 2)
        assertEquals(emptyList<String?>(), selected)
        // RIGHT from blank Search has nothing to commit: the shown category stays.
        settler.commitNow()
        assertEquals(emptyList<String?>(), selected)
    }

    @Test
    fun `focusing Search with a query still shows its results`() = runTest {
        val selected = mutableListOf<String?>()
        val settler = GuideCategorySettler(this) { selected += it }
        settler.focus(XtreamLiveGuideViewModel.SEARCH_ID, selectedId = "a", searchQuery = "bbc")
        advanceTimeBy(GuideCategorySettlePolicy.SETTLE_MS + 1)
        assertEquals(listOf<String?>(XtreamLiveGuideViewModel.SEARCH_ID), selected)
    }
}
