package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Device pass 1, finding 4: leaving the side rail with RIGHT put focus on the tab row, not where the viewer left the
 * content. The rail hands focus back by going unfocusable as it collapses (ModernSidebarBlurPanel `canFocus`), so
 * Compose enters the content from the top and the first group it meets is the tab row. The hub's content root now
 * restores the control the viewer left (01-hub-entry: later visits land exactly where you left).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HubContentFocusRestoreTest {

    @get:Rule
    val rule = createComposeRule()

    private val rail = FocusRequester()
    private val liveTab = FocusRequester()
    private val rows = List(6) { FocusRequester() }
    private var railFocusable by mutableStateOf(true)
    private var focused: String? = null
    private val history = mutableListOf<String>()
    private val contentReturn = HubContentReturn()
    private var aimedRow = 0

    private fun Modifier.named(name: String) = onFocusChanged { if (it.isFocused) { focused = name; history += name } else if (focused == name) focused = null }

    private fun setHub() {
        rule.setContent {
            NuvioTheme {
                Row {
                    Box(
                        Modifier.size(40.dp).focusRequester(rail)
                            .focusProperties { canFocus = railFocusable }
                            .named("rail").focusable()
                    )
                    Column(Modifier.hubContentFocusRestore(contentReturn)) {
                        Row(Modifier.focusRestorer(liveTab).onFocusChanged { if (it.hasFocus) contentReturn.remember(null) }) {
                            Box(Modifier.size(40.dp).focusRequester(liveTab).named("live").focusable())
                            Box(Modifier.size(40.dp).named("movies").focusable())
                        }
                        LazyColumn(
                            Modifier.height(300.dp)
                                // The guide registers its channel list the same way (the aimed row).
                                .onFocusChanged { if (it.hasFocus) contentReturn.remember { rows[aimedRow] } }
                                .focusRestorer(rows[0])
                        ) {
                            items(rows.size) { i ->
                                Box(Modifier.size(40.dp).focusRequester(rows[i]).onFocusChanged { if (it.isFocused) aimedRow = i }.named("row$i").focusable())
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `RIGHT out of the rail returns to the row the viewer left`() {
        setHub()
        // The viewer walks down the list to row 4 (a request from outside would land on the list's restorer fallback).
        rule.runOnIdle { assertTrue(rows[0].requestFocusOrFalse()) }
        repeat(3) { rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        rule.runOnIdle { assertEquals("history=$history", "row3", focused) }
        // LEFT opened the rail: it takes focus by request (MainActivity pendingSidebarFocusRequest).
        rule.runOnIdle { assertTrue(rail.requestFocusOrFalse()) }
        // RIGHT collapses it: the rail items stop being focusable.
        rule.runOnIdle { railFocusable = false }
        rule.waitForIdle()
        history += "collapse:$focused"
        if (focused == null || focused == "rail") rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals("back on the row the viewer left, not the tab row; history=$history", "row3", focused) }
    }

    @Test
    fun `leaving from the tab row comes back to the tab row`() {
        setHub()
        // The viewer walks down the list to row 4 (a request from outside would land on the list's restorer fallback).
        rule.runOnIdle { assertTrue(rows[0].requestFocusOrFalse()) }
        repeat(3) { rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        rule.runOnIdle { assertEquals("history=$history", "row3", focused) }
        rule.runOnIdle { assertTrue(liveTab.requestFocusOrFalse()) }
        rule.runOnIdle { assertTrue(rail.requestFocusOrFalse()) }
        rule.runOnIdle { railFocusable = false }
        rule.waitForIdle()
        if (focused == null || focused == "rail") rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.runOnIdle { assertEquals("LEFT on Live TV went to the rail; RIGHT returns there", "live", focused) }
    }
}
