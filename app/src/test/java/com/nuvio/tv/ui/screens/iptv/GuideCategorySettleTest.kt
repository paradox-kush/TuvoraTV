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
}
